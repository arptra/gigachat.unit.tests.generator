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
import com.gigachat.unit.tests.generator.reasoning.service.NextContextBuilder;
import com.gigachat.unit.tests.generator.reasoning.service.ProjectContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.ToolActionExecutor;
import com.gigachat.unit.tests.generator.reasoning.workflow.ReasoningWorkflow;
import com.gigachat.unit.tests.generator.reasoning.workflow.exception.FixingFailureException;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Drives the compile → reasoning → apply loop until compilation succeeds or a
 * failure threshold is reached.
 */
public class CompilationPipelineOrchestrator {

    private static final int MAX_ITERATIONS = 10;

    private final CompilerInvoker compilerInvoker;
    private final ReasoningWorkflow reasoningWorkflow;
    private final ProjectContextCollector projectContextCollector;
    private final ToolActionExecutor actionExecutor;
    private final NextContextBuilder nextContextBuilder;
    private final CompilationErrorClassifier errorClassifier;
    private final Path projectRoot;
    private final Path testFile;
    private final String testFileFqcn;
    private final String methodName;

    public CompilationPipelineOrchestrator(CompilerInvoker compilerInvoker,
                                           ReasoningWorkflow reasoningWorkflow,
                                           ProjectContextCollector projectContextCollector,
                                           ToolActionExecutor actionExecutor,
                                           CompilationErrorClassifier errorClassifier,
                                           Path projectRoot,
                                           Path testFile,
                                           String testFileFqcn,
                                           String methodName) {
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.reasoningWorkflow = Objects.requireNonNull(reasoningWorkflow, "reasoningWorkflow");
        this.projectContextCollector = Objects.requireNonNull(projectContextCollector, "projectContextCollector");
        this.actionExecutor = Objects.requireNonNull(actionExecutor, "actionExecutor");
        this.nextContextBuilder = new NextContextBuilder();
        this.errorClassifier = errorClassifier == null ? new CompilationErrorClassifier() : errorClassifier;
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.testFile = Objects.requireNonNull(testFile, "testFile");
        this.testFileFqcn = testFileFqcn;
        this.methodName = methodName;
    }

    /**
     * Runs the iterative fixing loop. Returns the last successful compile result or throws when
     * fixes could not be applied.
     */
    public CompileResult runFixingLoop() {
        CompileResult lastResult = null;
        ActionExecutionResult cumulativeResult = ActionExecutionResult.empty();
        ReasoningMemory memory = new ReasoningMemory();
        memory.setState(AgentState.S0_INIT);
        for (int attempt = 0; attempt < MAX_ITERATIONS; attempt++) {
            memory.incrementAttempt();
            lastResult = compilerInvoker.compileWithoutCache(projectRoot, testFile, methodName);
            if (lastResult.success()) {
                memory.setState(AgentState.S5_COMPILATION_SUCCESS);
                return lastResult;
            }
            memory.setState(AgentState.S2_COMPILATION_FAILED);
            CompilationErrorInfo errorInfo = CompilationErrorInfoBuilder.from(lastResult, testFile, testFileFqcn);
            CompilationErrorReport report = errorClassifier.classify(lastResult.stderr());
            String signature = deriveSignature(report, errorInfo);
            memory.addErrorSignature(signature);

            String missingSymbol = findMissingSymbol(report);
            if (missingSymbol != null) {
                memory.addKnownMissingSymbol(missingSymbol);
            }

            ReasoningLoopContext loopContext = nextContextBuilder.build(errorInfo, projectContextCollector.collect(), cumulativeResult, report, memory);
            ReasoningResponse response = reasoningWorkflow.process(loopContext);
            String decision = response == null ? "STOP" : response.getDecision();
            if (decision == null || decision.isBlank()) {
                decision = "STOP";
            }
            decision = decision.trim().toUpperCase(Locale.ROOT);

            if ("REQUEST_CONTEXT".equals(decision)) {
                memory.setState(AgentState.S2_1_NEED_MORE_CONTEXT);
                ActionExecutionResult iterationResult = actionExecutor.execute(response.toToolAction());
                cumulativeResult = cumulativeResult.merge(iterationResult);
                memory.applyUpdates(response.getMemoryUpdates().getKnownMissingSymbols(),
                        response.getMemoryUpdates().getAppliedFixSignatures(),
                        extractContextCache(iterationResult));
                memory.decrementContextBudget();
                if (memory.getContextRequestBudgetRemaining() <= 0) {
                    memory.setState(AgentState.S6_GIVE_UP);
                    throw new FixingFailureException("Context request budget exhausted", lastResult);
                }
                continue;
            } else if ("APPLY_FIX".equals(decision)) {
                ActionExecutionResult iterationResult = actionExecutor.execute(response.toToolAction());
                cumulativeResult = cumulativeResult.merge(iterationResult);
                memory.applyUpdates(response.getMemoryUpdates().getKnownMissingSymbols(),
                        response.getMemoryUpdates().getAppliedFixSignatures(),
                        extractContextCache(iterationResult));
                if (!iterationResult.getPerformedActions().isEmpty()) {
                    memory.setState(AgentState.S4_FIX_APPLIED);
                    memory.resetContextBudget();
                }
            } else if ("MARK_FALSE_DEPENDENCY".equals(decision)) {
                memory.applyUpdates(response.getMemoryUpdates().getKnownMissingSymbols(),
                        response.getMemoryUpdates().getAppliedFixSignatures(),
                        response.getMemoryUpdates().getContextCache());
                memory.setState(AgentState.S3_FALSE_DEPENDENCY_DETECTED);
                continue;
            } else {
                memory.setState(AgentState.S6_GIVE_UP);
                throw new FixingFailureException("Reasoning agent stopped after repeated failures", lastResult);
            }
        }
        throw new FixingFailureException("Reached maximum reasoning iterations without a successful compile", lastResult);
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
