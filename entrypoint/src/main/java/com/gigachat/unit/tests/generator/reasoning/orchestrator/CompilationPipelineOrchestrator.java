package com.gigachat.unit.tests.generator.reasoning.orchestrator;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.compile.classification.classify.CompilationErrorClassifier;
import com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorReport;
import com.gigachat.unit.tests.generator.compile.classification.model.CompilationError;
import com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorClass;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfoBuilder;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningMemory;
import com.gigachat.unit.tests.generator.reasoning.model.AgentState;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningStage;
import com.gigachat.unit.tests.generator.reasoning.service.CompilationReasoningService;
import com.gigachat.unit.tests.generator.reasoning.service.DeterministicCompilationRecipeBuilder;
import com.gigachat.unit.tests.generator.reasoning.service.NextContextBuilder;
import com.gigachat.unit.tests.generator.reasoning.service.ProjectContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.ToolActionExecutor;
import com.gigachat.unit.tests.generator.reasoning.workflow.exception.FixingFailureException;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.resources.FalseDependencyPolicy;
import com.gigachat.unit.tests.generator.resources.ReasoningLoopPolicy;
import com.gigachat.unit.tests.generator.resources.ReasoningLoopPolicyCatalog;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Drives the compile → reasoning → apply loop until compilation succeeds or a
 * failure threshold is reached.
 */
public class CompilationPipelineOrchestrator {
    private static final Pattern CLASS_SYMBOL_PATTERN = Pattern.compile("symbol:\\s+class\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern METHOD_SYMBOL_PATTERN = Pattern.compile("symbol:\\s+method\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern VARIABLE_SYMBOL_PATTERN = Pattern.compile("symbol:\\s+variable\\s+", Pattern.CASE_INSENSITIVE);

    private final CompilerInvoker compilerInvoker;
    private final CompilationReasoningService reasoningService;
    private final ProjectContextCollector projectContextCollector;
    private final ToolActionExecutor actionExecutor;
    private final NextContextBuilder nextContextBuilder;
    private final CompilationErrorClassifier errorClassifier;
    private final PipelineLogger logger;
    private final Path projectRoot;
    private final Path testFile;
    private final String testFileFqcn;
    private final String methodName;
    private final ReasoningLoopPolicy loopPolicy;
    private final DeterministicCompilationRecipeBuilder compilationRecipeBuilder;

    public CompilationPipelineOrchestrator(CompilerInvoker compilerInvoker,
                                           CompilationReasoningService reasoningService,
                                           ProjectContextCollector projectContextCollector,
                                           ToolActionExecutor actionExecutor,
                                           CompilationErrorClassifier errorClassifier,
                                           PipelineLogger logger,
                                           Path projectRoot,
                                           Path testFile,
                                           String testFileFqcn,
                                           String methodName) {
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.reasoningService = Objects.requireNonNull(reasoningService, "reasoningService");
        this.projectContextCollector = Objects.requireNonNull(projectContextCollector, "projectContextCollector");
        this.actionExecutor = Objects.requireNonNull(actionExecutor, "actionExecutor");
        this.nextContextBuilder = new NextContextBuilder();
        this.errorClassifier = errorClassifier == null ? new CompilationErrorClassifier() : errorClassifier;
        this.logger = Objects.requireNonNull(logger, "logger");
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.testFile = Objects.requireNonNull(testFile, "testFile");
        this.testFileFqcn = testFileFqcn;
        this.methodName = methodName;
        this.loopPolicy = new ReasoningLoopPolicyCatalog().compilationPolicy();
        this.compilationRecipeBuilder = new DeterministicCompilationRecipeBuilder();
    }

    /**
     * Runs the iterative fixing loop. Returns the last successful compile result or throws when
     * fixes could not be applied.
     */
    public CompileResult runFixingLoop() {
        CompileResult lastResult = null;
        ActionExecutionResult cumulativeResult = ActionExecutionResult.empty();
        StateGraphController controller = new StateGraphController(
                logger,
                methodName,
                loopPolicy,
                AgentState.S0_INIT,
                "starting compile-fix loop");
        ReasoningMemory memory = controller.memory();
        for (int attempt = 0; attempt < loopPolicy.maxIterations(); attempt++) {
            controller.incrementAttempt();
            lastResult = compilerInvoker.compileWithoutCache(projectRoot, testFile, methodName);
            if (lastResult.success()) {
                controller.move("RESULT", AgentState.S5_COMPILATION_SUCCESS, "compile-fix loop finished successfully");
                return lastResult;
            }
            controller.move("RESULT", AgentState.S2_COMPILATION_FAILED, "compile failed");
            CompilationErrorInfo errorInfo = CompilationErrorInfoBuilder.from(lastResult, testFile, testFileFqcn);
            CompilationErrorReport report = errorClassifier.classify(lastResult.stderr());
            ActionExecutionResult deterministicRepair = tryDeterministicImportRepair(report, controller, memory);
            if (!deterministicRepair.getPerformedActions().isEmpty()) {
                cumulativeResult = cumulativeResult.merge(deterministicRepair);
                continue;
            }
            List<Map<String, Object>> deterministicRecipes = compilationRecipeBuilder.build(report, errorInfo);
            actionExecutor.setAvailableRecipes(deterministicRecipes);
            String signature = deriveSignature(report, errorInfo);
            controller.addErrorSignature(signature);
            logger.trace("RESULT", methodName, AgentState.S2_COMPILATION_FAILED.name(), "compile failed signature=" + signature);
            if (controller.countOccurrences(signature) >= loopPolicy.repeatedSignatureThreshold()) {
                controller.move("RESULT", AgentState.S6_GIVE_UP, "repeated compilation failure signature=" + signature);
                throw new FixingFailureException("Repeated compilation errors detected. Giving up.", lastResult);
            }

            if (looksLikeFrameworkClasspathFault(lastResult, report)) {
                controller.move("RESULT",
                        AgentState.S6_GIVE_UP,
                        "compile environment missing framework classpath entries; skipping reasoning loop");
                throw new FixingFailureException("Compilation environment is missing test framework dependencies/classpath", lastResult);
            }

            String missingSymbol = findMissingSymbol(report);
            if (missingSymbol != null && !actionExecutor.symbolExistsInProjectOrClasspath(missingSymbol)) {
                FalseDependencyPolicy falseDependencyPolicy = loopPolicy.falseDependencyPolicy();
                if (falseDependencyPolicy != null) {
                    controller.move("RESULT", falseDependencyPolicy.nextState(), "false dependency detected");
                    falseDependencyPolicy.forbiddenActions().forEach(controller::addForbiddenAction);
                } else {
                    controller.move("RESULT", AgentState.S3_FALSE_DEPENDENCY_DETECTED, "false dependency detected");
                }
                memory.addKnownMissingSymbol(missingSymbol);
                logger.trace("RESULT",
                        methodName,
                        memory.getState().name(),
                        "agent tools could not resolve symbol=" + missingSymbol + "; treating as generated-test artifact");
            }

            ActionExecutionResult promptContext = appendDeterministicRecipes(cumulativeResult, deterministicRecipes);
            ReasoningLoopContext loopContext = nextContextBuilder.build(errorInfo,
                    projectContextCollector.collect(),
                    promptContext,
                    report,
                    memory,
                    ReasoningStage.COMPILATION);
            ReasoningResponse response = reasoningService.reasonAboutError(loopContext);
            String decision = response == null ? "STOP" : response.getDecision();
            if (decision == null || decision.isBlank()) {
                decision = "STOP";
            }
            logger.trace("DECISION", methodName, memory.getState().name(), "compile reasoning chose " + decision + " actions=" + summariseReasoningActions(response));

            if ("REQUEST_CONTEXT".equals(decision)) {
                controller.moveForDecision("STATE", decision, AgentState.S2_1_NEED_MORE_CONTEXT, "context requested during compilation reasoning");
                controller.logAction("executing REQUEST_CONTEXT");
                ActionExecutionResult iterationResult = actionExecutor.execute(response.toToolAction());
                cumulativeResult = cumulativeResult.merge(iterationResult);
                memory.applyUpdates(response.getMemoryUpdates().getKnownMissingSymbols(),
                        response.getMemoryUpdates().getAppliedFixSignatures(),
                        extractContextCache(iterationResult));
                controller.logAction("request-context result actions=" + iterationResult.getPerformedActions() + " infoKeys=" + iterationResult.getInformation().keySet());
                controller.decrementContextBudget();
                if (controller.contextBudgetRemaining() <= 0) {
                    controller.move("RESULT", AgentState.S6_GIVE_UP, "context request budget exhausted");
                    throw new FixingFailureException("Context request budget exhausted", lastResult);
                }
                continue;
            } else if ("APPLY_FIX".equals(decision)) {
                controller.logAction("executing APPLY_FIX");
                ActionExecutionResult iterationResult = actionExecutor.execute(response.toToolAction());
                cumulativeResult = cumulativeResult.merge(iterationResult);
                memory.applyUpdates(response.getMemoryUpdates().getKnownMissingSymbols(),
                        response.getMemoryUpdates().getAppliedFixSignatures(),
                        extractContextCache(iterationResult));
                controller.logAction("apply-fix result actions=" + iterationResult.getPerformedActions() + " infoKeys=" + iterationResult.getInformation().keySet());
                if (!iterationResult.getPerformedActions().isEmpty()) {
                    controller.moveForDecision("TRANSITION", decision, AgentState.S4_FIX_APPLIED, "fix applied, recompiling");
                    controller.resetContextBudget();
                }
            } else if ("MARK_FALSE_DEPENDENCY".equals(decision)) {
                memory.applyUpdates(response.getMemoryUpdates().getKnownMissingSymbols(),
                        response.getMemoryUpdates().getAppliedFixSignatures(),
                        response.getMemoryUpdates().getContextCache());
                controller.moveForDecision("TRANSITION", decision, AgentState.S3_FALSE_DEPENDENCY_DETECTED, "marked false dependency");
                continue;
            } else {
                controller.moveForDecision("RESULT", "STOP", AgentState.S6_GIVE_UP, "compile reasoning stopped");
                throw new FixingFailureException("Reasoning agent stopped after repeated failures", lastResult);
            }
        }
        controller.move("RESULT", AgentState.S6_GIVE_UP, "reached max compile-fix iterations");
        throw new FixingFailureException("Reached maximum reasoning iterations without a successful compile", lastResult);
    }

    private ActionExecutionResult appendDeterministicRecipes(ActionExecutionResult base,
                                                             List<Map<String, Object>> recipes) {
        if (recipes == null || recipes.isEmpty()) {
            return base;
        }
        return base.merge(new ActionExecutionResult(Map.of("deterministicRepairRecipes", recipes)));
    }

    private String summariseReasoningActions(ReasoningResponse response) {
        if (response == null || response.getActions() == null || response.getActions().isEmpty()) {
            return "[]";
        }
        return response.getActions().toString().replaceAll("\\s+", " ").trim();
    }

    private String deriveSignature(CompilationErrorReport report, CompilationErrorInfo fallback) {
        if (report != null && report.getErrors() != null && !report.getErrors().isEmpty()) {
            CompilationError first = report.getErrors().get(0);
            String symbol = first.getSymbol() == null ? first.getNormalizedMessage() : first.getSymbol();
            String file = first.getFilePath() == null ? "" : first.getFilePath();
            return first.getErrorClass().name() + "|" + symbol + "|" + file;
        }
        return "UNKNOWN|" + fallback.getPrimaryMessage() + "|" + fallback.getTestFilePath();
    }

    private String findMissingSymbol(CompilationErrorReport report) {
        if (report == null || report.getErrors() == null) {
            return null;
        }
        return report.getErrors().stream()
                .filter(error -> error.getErrorClass() == CompilationErrorClass.MISSING_IMPORT_OR_SYMBOL)
                .map(CompilationError::getSymbol)
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .findFirst()
                .orElse(null);
    }

    private ActionExecutionResult tryDeterministicImportRepair(CompilationErrorReport report,
                                                               StateGraphController controller,
                                                               ReasoningMemory memory) {
        CompilationError missingSymbolError = firstMissingImportOrSymbolError(report);
        if (!isDeterministicImportCandidate(missingSymbolError)) {
            return ActionExecutionResult.empty();
        }
        String missingSymbol = missingSymbolError.getSymbol();
        if (missingSymbol == null || missingSymbol.isBlank()) {
            return ActionExecutionResult.empty();
        }
        String autoFixSignature = "AUTO_IMPORT_ALIGN|" + missingSymbol;
        if (memory.getAppliedFixSignatures().contains(autoFixSignature)) {
            return ActionExecutionResult.empty();
        }
        boolean preferStaticImport = prefersStaticImport(missingSymbolError);
        ActionExecutionResult repairResult = actionExecutor.alignImportWithUniqueProjectSymbol(testFile, missingSymbol, preferStaticImport);
        if (repairResult.getPerformedActions().isEmpty()) {
            return ActionExecutionResult.empty();
        }
        memory.addAppliedFixSignature(autoFixSignature);
        controller.logAction("deterministic compile repair actions=" + repairResult.getPerformedActions());
        controller.move("TRANSITION",
                AgentState.S4_FIX_APPLIED,
                "applied deterministic import repair for symbol=" + missingSymbol);
        controller.resetContextBudget();
        return repairResult;
    }

    private CompilationError firstMissingImportOrSymbolError(CompilationErrorReport report) {
        if (report == null || report.getErrors() == null) {
            return null;
        }
        return report.getErrors().stream()
                .filter(error -> error.getErrorClass() == CompilationErrorClass.MISSING_IMPORT_OR_SYMBOL)
                .findFirst()
                .orElse(null);
    }

    private boolean isDeterministicImportCandidate(CompilationError error) {
        if (error == null) {
            return false;
        }
        String message = error.getNormalizedMessage();
        if (message == null) {
            return false;
        }
        if (METHOD_SYMBOL_PATTERN.matcher(message).find()) {
            return true;
        }
        if (VARIABLE_SYMBOL_PATTERN.matcher(message).find()) {
            return false;
        }
        if (CLASS_SYMBOL_PATTERN.matcher(message).find()) {
            return true;
        }
        String symbol = error.getSymbol();
        return symbol != null
                && !symbol.isBlank()
                && Character.isUpperCase(symbol.charAt(0));
    }

    private boolean prefersStaticImport(CompilationError error) {
        if (error == null || error.getNormalizedMessage() == null) {
            return false;
        }
        String message = error.getNormalizedMessage();
        if (METHOD_SYMBOL_PATTERN.matcher(message).find()) {
            return true;
        }
        if (VARIABLE_SYMBOL_PATTERN.matcher(message).find()) {
            return false;
        }
        String symbol = error.getSymbol();
        return symbol != null
                && !symbol.isBlank()
                && Character.isLowerCase(symbol.charAt(0));
    }

    private boolean looksLikeFrameworkClasspathFault(CompileResult compileResult, CompilationErrorReport report) {
        if (compileResult == null || report == null || report.getTopMissingPackages().isEmpty()) {
            return false;
        }
        boolean classpathFallbackDetected = compileResult.messages().stream()
                .anyMatch(message -> message.contains("Using current JVM classpath only")
                        || message.contains("falling back to JVM classpath")
                        || message.contains("Gradle classpath task exited with code")
                        || message.contains("Tooling API classpath resolution failed"));
        if (!classpathFallbackDetected) {
            return false;
        }
        List<String> frameworkPrefixes = List.of("org.junit.", "org.mockito.", "org.assertj.");
        return report.getTopMissingPackages().stream()
                .anyMatch(pkg -> frameworkPrefixes.stream().anyMatch(pkg::startsWith));
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, String> extractContextCache(ActionExecutionResult result) {
        if (result == null || result.getInformation().isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        Object updates = result.getInformation().get("contextCacheUpdates");
        if (updates instanceof Map<?, ?> map) {
            java.util.Map<String, String> converted = new java.util.HashMap<>();
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    converted.put(k.toString(), v.toString());
                }
            });
            return converted;
        }
        return java.util.Collections.emptyMap();
    }
}
