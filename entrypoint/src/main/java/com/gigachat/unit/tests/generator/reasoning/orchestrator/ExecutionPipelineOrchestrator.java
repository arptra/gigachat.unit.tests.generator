package com.gigachat.unit.tests.generator.reasoning.orchestrator;

import com.gigachat.unit.tests.generator.cleaner.parser.ExecutionFailureLogParser;
import com.gigachat.unit.tests.generator.cleaner.parser.ExecutionFailureParseResult;
import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.compile.classification.classify.CompilationErrorClassifier;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.AgentState;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.reasoning.model.ProjectContextSummary;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningMemory;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningStage;
import com.gigachat.unit.tests.generator.reasoning.model.ToolAction;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionStep;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;
import com.gigachat.unit.tests.generator.reasoning.service.ExecutionFailureContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.NextContextBuilder;
import com.gigachat.unit.tests.generator.reasoning.service.ProjectContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.CompilationReasoningService;
import com.gigachat.unit.tests.generator.reasoning.service.ToolActionExecutor;
import com.gigachat.unit.tests.generator.reasoning.workflow.exception.FixingFailureException;
import com.gigachat.unit.tests.generator.report.parser.ExecutionReportParser;
import com.gigachat.unit.tests.generator.report.parser.TestReportFailure;
import com.gigachat.unit.tests.generator.resources.ReasoningLoopPolicy;
import com.gigachat.unit.tests.generator.resources.ReasoningLoopPolicyCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns the execution-stage bounded repair loop. It is intentionally separate from the generation
 * step so runtime state transitions and reruns no longer live in one giant pipeline class.
 */
public class ExecutionPipelineOrchestrator {
    private static final String EXECUTION_PROMPT_SUFFIX = """
            Execution-only hard constraint:
            - While the failure is runtime-only and the test still compiles, prefer REQUEST_CONTEXT or APPLY_FIX before STOP.
            """;

    private final PipelineLogger logger;
    private final CompilerInvoker compilerInvoker;
    private final ExecutionInvoker executionInvoker;
    private final ExecutionFailureLogParser executionFailureLogParser;
    private final ExecutionReportParser executionReportParser;
    private final ExecutionFailureContextCollector executionFailureContextCollector;
    private final CompilationPipelineOrchestrator compilationOrchestrator;
    private final CompilationReasoningService reasoningService;
    private final ReasoningLoopPolicy loopPolicy;
    private final NextContextBuilder nextContextBuilder;

    public ExecutionPipelineOrchestrator(PipelineLogger logger,
                                         CompilerInvoker compilerInvoker,
                                         ExecutionInvoker executionInvoker,
                                         ExecutionFailureLogParser executionFailureLogParser,
                                         ExecutionReportParser executionReportParser,
                                         ExecutionFailureContextCollector executionFailureContextCollector,
                                         CompilationPipelineOrchestrator compilationOrchestrator,
                                         CompilationReasoningService reasoningService) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.executionInvoker = Objects.requireNonNull(executionInvoker, "executionInvoker");
        this.executionFailureLogParser = Objects.requireNonNull(executionFailureLogParser, "executionFailureLogParser");
        this.executionReportParser = Objects.requireNonNull(executionReportParser, "executionReportParser");
        this.executionFailureContextCollector = Objects.requireNonNull(executionFailureContextCollector, "executionFailureContextCollector");
        this.compilationOrchestrator = Objects.requireNonNull(compilationOrchestrator, "compilationOrchestrator");
        this.reasoningService = Objects.requireNonNull(reasoningService, "reasoningService");
        this.loopPolicy = new ReasoningLoopPolicyCatalog().executionPolicy();
        this.nextContextBuilder = new NextContextBuilder();
    }

    public ExecutionRepairResult runRepairLoop(AgentConfig config,
                                               TestClassInfo classInfo,
                                               TestMethodInfo methodInfo,
                                               Analyze.AnalysisSummary analysisSummary,
                                               ToolActionExecutor actionExecutor,
                                               CompileResult compileResult,
                                               ExecuteResult executeResult,
                                               String generatedMethodName,
                                               String executionMethodName,
                                               ExecutionFailureParseResult failureParseResult,
                                               List<TestReportFailure> reportFailures) {
        StateGraphController controller = new StateGraphController(
                logger,
                generatedMethodName,
                loopPolicy,
                AgentState.S2_2_EXECUTION_FAILED,
                "starting execution reasoning loop");
        ReasoningMemory memory = controller.memory();
        ActionExecutionResult cumulativeResult = executionFailureContextCollector.collect(config.getProjectPath(),
                classInfo,
                methodInfo,
                analysisSummary,
                executeResult,
                failureParseResult,
                reportFailures);
        logExecutionReasoningStart(generatedMethodName, executeResult, cumulativeResult);

        CompileResult currentCompileResult = compileResult;
        ExecuteResult currentExecuteResult = executeResult;
        ExecutionFailureParseResult currentFailureParseResult = failureParseResult;
        List<TestReportFailure> currentReportFailures = reportFailures == null ? List.of() : List.copyOf(reportFailures);
        int repeatedSignatureGraceRounds = 0;
        boolean finalContextFollowUpGranted = false;

        for (int iteration = 0; iteration < loopPolicy.maxIterations(); iteration++) {
            controller.incrementAttempt();
            List<Map<String, Object>> deterministicRecipes = extractDeterministicRepairRecipes(cumulativeResult);
            actionExecutor.setAvailableRecipes(deterministicRecipes);
            if (!deterministicRecipes.isEmpty()) {
                controller.addForbiddenAction("APPLY_PATCH");
            }
            ToolAction deterministicRecipeAction = singleDeterministicRecipeAction(deterministicRecipes, memory);
            if (deterministicRecipeAction != null) {
                Object recipeId = deterministicRecipes.get(0).get("id");
                logger.info("[EXECUTION_REASONING] action=APPLY_DETERMINISTIC_RECIPE method="
                        + generatedMethodName
                        + " recipeId="
                        + recipeId);
                ActionExecutionResult recipeResult = actionExecutor.execute(deterministicRecipeAction);
                cumulativeResult = cumulativeResult.merge(recipeResult);
                if (recipeId != null) {
                    memory.addAppliedFixSignature("RECIPE:" + recipeId);
                }
                logExecutionReasoningActionResult("APPLY_RECIPE", generatedMethodName, recipeResult);
                if (recipeResult.getPerformedActions().isEmpty()) {
                    logger.warn("[EXECUTION_REASONING] Deterministic recipe produced no persisted change for method "
                            + generatedMethodName
                            + ". Falling back to reasoning.");
                } else {
                    currentCompileResult = compilerInvoker.compileWithoutCache(config.getProjectPath(),
                            classInfo.getTargetPath(),
                            generatedMethodName);
                    logger.info("[EXECUTION_REASONING] Recompile after deterministic recipe for "
                            + generatedMethodName
                            + " -> success="
                            + currentCompileResult.success());
                    if (!currentCompileResult.success()) {
                        try {
                            logger.warn("[EXECUTION_REASONING] Deterministic recipe caused compilation failure for "
                                    + generatedMethodName
                                    + ". Trying compile-fix loop before falling back.");
                            currentCompileResult = compilationOrchestrator.runFixingLoop();
                        } catch (FixingFailureException exception) {
                            logger.error("Compilation fixing loop failed during deterministic execution repair: "
                                    + exception.getMessage(), exception);
                            currentCompileResult = exception.getLastResult();
                        }
                        if (currentCompileResult == null || !currentCompileResult.success()) {
                            return new ExecutionRepairResult(false,
                                    true,
                                    currentCompileResult,
                                    currentExecuteResult,
                                    currentFailureParseResult,
                                    currentReportFailures,
                                    cumulativeResult);
                        }
                    }
                    currentExecuteResult = executionInvoker.execute(config.getProjectPath(),
                            classInfo.getTargetPath(),
                            executionMethodName);
                    logger.info("[EXECUTION_REASONING] Reran test after deterministic recipe for "
                            + generatedMethodName
                            + " -> success="
                            + currentExecuteResult.success());
                    if (currentExecuteResult.success()) {
                        logger.info("[EXECUTION_REASONING] Deterministic execution recipe fixed runtime failure for method "
                                + generatedMethodName);
                        controller.move("RESULT", AgentState.S5_COMPILATION_SUCCESS, "execution repaired by deterministic recipe");
                        return new ExecutionRepairResult(true,
                                false,
                                currentCompileResult,
                                currentExecuteResult,
                                new ExecutionFailureParseResult(List.of(), Optional.empty()),
                                List.of(),
                                cumulativeResult);
                    }
                    currentFailureParseResult = parseExecutionLog(currentExecuteResult);
                    currentReportFailures = parseExecutionReport(config.getProjectPath(), currentFailureParseResult);
                    cumulativeResult = cumulativeResult.merge(executionFailureContextCollector.collect(config.getProjectPath(),
                            classInfo,
                            methodInfo,
                            analysisSummary,
                            currentExecuteResult,
                            currentFailureParseResult,
                            currentReportFailures));
                    controller.move("RESULT",
                            AgentState.S2_2_EXECUTION_FAILED,
                            "runtime still failing after deterministic execution recipe");
                }
            }

            String signature = deriveExecutionErrorSignature(currentExecuteResult, currentReportFailures);
            controller.addErrorSignature(signature);
            logger.info("[EXECUTION_REASONING] Attempt "
                    + memory.getAttempt()
                    + " for "
                    + generatedMethodName
                    + "; signature="
                    + signature);
            if (controller.countOccurrences(signature) >= loopPolicy.repeatedSignatureThreshold()) {
                if (repeatedSignatureGraceRounds > 0) {
                    repeatedSignatureGraceRounds--;
                    logger.info("[EXECUTION_REASONING] Allowing repeated runtime signature after REQUEST_CONTEXT for method "
                            + generatedMethodName
                            + "; remainingGraceRounds="
                            + repeatedSignatureGraceRounds);
                } else {
                    logger.warn("[EXECUTION_REASONING] Repeated runtime failure signature detected for method "
                            + generatedMethodName
                            + ". Stopping execution reasoning without regeneration.");
                    break;
                }
            }

            ReasoningResponse response = triggerReasoningWorkflow(config,
                    classInfo,
                    methodInfo,
                    generatedMethodName,
                    currentCompileResult,
                    currentExecuteResult,
                    cumulativeResult,
                    memory,
                    currentFailureParseResult,
                    currentReportFailures);
            logReasoningResponse("execute", response);
            logExecutionReasoningDecision(generatedMethodName, response);
            String decision = response == null || response.getDecision() == null
                    ? "STOP"
                    : response.getDecision().trim().toUpperCase();

            if ("STOP".equals(decision)) {
                if (iteration < loopPolicy.stopGraceRounds()) {
                    logger.warn("[EXECUTION_REASONING] Model stopped before fixing runtime failure for method "
                            + generatedMethodName
                            + ". Forcing one more reasoning round with explicit feedback.");
                    cumulativeResult = cumulativeResult.merge(new ActionExecutionResult(Map.of(
                            "agentFeedback", List.of(Map.of(
                                    "stage", "execute",
                                    "message", "Runtime failure is still present. Do not STOP yet. Request context or apply a concrete fix in test code."
                            )))));
                    continue;
                }
                logger.warn("[EXECUTION_REASONING] Agent returned STOP for method "
                        + generatedMethodName
                        + ". Keeping current test state and not regenerating.");
                controller.moveForDecision("RESULT", "STOP", AgentState.S6_GIVE_UP, "execution reasoning stopped");
                break;
            }

            if ("REQUEST_CONTEXT".equals(decision)) {
                ToolAction toolAction = response.toToolAction();
                logExecutionReasoningToolAction("REQUEST_CONTEXT", generatedMethodName, toolAction);
                ActionExecutionResult iterationResult = actionExecutor.execute(toolAction);
                cumulativeResult = cumulativeResult.merge(iterationResult);
                applyMemoryUpdates(memory, response, iterationResult);
                boolean usefulContext = producedUsefulContext(iterationResult);
                controller.moveForDecision("STATE",
                        decision,
                        AgentState.S2_1_NEED_MORE_CONTEXT,
                        "context requested during execution reasoning");
                logExecutionReasoningActionResult("REQUEST_CONTEXT", generatedMethodName, iterationResult);
                if (usefulContext) {
                    repeatedSignatureGraceRounds++;
                    logger.info("[EXECUTION_REASONING] Allowing follow-up reasoning after REQUEST_CONTEXT for method "
                            + generatedMethodName
                            + "; graceRounds="
                            + repeatedSignatureGraceRounds
                            + " infoKeys="
                            + iterationResult.getInformation().keySet());
                }
                controller.decrementContextBudget();
                boolean allowFinalFollowUp = usefulContext
                        && controller.contextBudgetRemaining() <= 0
                        && !finalContextFollowUpGranted;
                if (controller.contextBudgetRemaining() <= 0 && !allowFinalFollowUp) {
                    logger.warn("[EXECUTION_REASONING] Context budget exhausted for method "
                            + generatedMethodName
                            + ". Keeping current test state and not regenerating.");
                    controller.move("RESULT", AgentState.S6_GIVE_UP, "execution context request budget exhausted");
                    break;
                }
                if (allowFinalFollowUp) {
                    finalContextFollowUpGranted = true;
                    logger.info("[EXECUTION_REASONING] Context budget reached zero after useful context for method "
                            + generatedMethodName
                            + ". Allowing one final follow-up reasoning round with cached sources.");
                }
                continue;
            }

            if ("MARK_FALSE_DEPENDENCY".equals(decision)) {
                applyMemoryUpdates(memory, response, ActionExecutionResult.empty());
                controller.moveForDecision("TRANSITION",
                        decision,
                        AgentState.S3_FALSE_DEPENDENCY_DETECTED,
                        "marked false dependency");
                logger.info("[EXECUTION_REASONING] Marked dependency as false/missing for method " + generatedMethodName);
                continue;
            }

            if (!"APPLY_FIX".equals(decision)) {
                logger.warn("[EXECUTION_REASONING] Unsupported decision "
                        + decision
                        + " for method "
                        + generatedMethodName
                        + ". Stopping execution reasoning.");
                controller.move("RESULT", AgentState.S6_GIVE_UP, "unsupported execution reasoning decision=" + decision);
                break;
            }

            ToolAction toolAction = response.toToolAction();
            logExecutionReasoningToolAction("APPLY_FIX", generatedMethodName, toolAction);
            ActionExecutionResult iterationResult = actionExecutor.execute(toolAction);
            cumulativeResult = cumulativeResult.merge(iterationResult);
            applyMemoryUpdates(memory, response, iterationResult);
            if (iterationResult.getPerformedActions().isEmpty()) {
                logger.info("[EXECUTION_REASONING] APPLY_FIX produced no persisted change for method "
                        + generatedMethodName
                        + "; skipping recompile/rerun.");
                logExecutionReasoningActionResult("APPLY_FIX", generatedMethodName, iterationResult);
                continue;
            }
            controller.moveForDecision("TRANSITION",
                    decision,
                    AgentState.S4_FIX_APPLIED,
                    "execution fix applied; recompiling");
            logExecutionReasoningActionResult("APPLY_FIX", generatedMethodName, iterationResult);

            currentCompileResult = compilerInvoker.compileWithoutCache(config.getProjectPath(),
                    classInfo.getTargetPath(),
                    generatedMethodName);
            logger.info("[EXECUTION_REASONING] Recompile after execution fix for "
                    + generatedMethodName
                    + " -> success="
                    + currentCompileResult.success());
            if (!currentCompileResult.success()) {
                try {
                    logger.warn("[EXECUTION_REASONING] Execution fix caused compilation failure for "
                            + generatedMethodName
                            + ". Trying compile-fix loop before regeneration.");
                    currentCompileResult = compilationOrchestrator.runFixingLoop();
                } catch (FixingFailureException exception) {
                    logger.error("Compilation fixing loop failed during execution repair: " + exception.getMessage(), exception);
                    currentCompileResult = exception.getLastResult();
                }
                if (currentCompileResult == null || !currentCompileResult.success()) {
                    return new ExecutionRepairResult(false,
                            true,
                            currentCompileResult,
                            currentExecuteResult,
                            currentFailureParseResult,
                            currentReportFailures,
                            cumulativeResult);
                }
                logger.info("[EXECUTION_REASONING] Compilation recovered after execution fix for " + generatedMethodName);
            }

            currentExecuteResult = executionInvoker.execute(config.getProjectPath(),
                    classInfo.getTargetPath(),
                    executionMethodName);
            logger.info("[EXECUTION_REASONING] Reran test after fix for "
                    + generatedMethodName
                    + " -> success="
                    + currentExecuteResult.success());
            if (currentExecuteResult.success()) {
                logger.info("[EXECUTION_REASONING] Execution fixed successfully for method " + generatedMethodName);
                controller.move("RESULT", AgentState.S5_COMPILATION_SUCCESS, "execution repaired successfully");
                return new ExecutionRepairResult(true,
                        false,
                        currentCompileResult,
                        currentExecuteResult,
                        new ExecutionFailureParseResult(List.of(), Optional.empty()),
                        List.of(),
                        cumulativeResult);
            }

            currentFailureParseResult = parseExecutionLog(currentExecuteResult);
            currentReportFailures = parseExecutionReport(config.getProjectPath(), currentFailureParseResult);
            cumulativeResult = cumulativeResult.merge(executionFailureContextCollector.collect(config.getProjectPath(),
                    classInfo,
                    methodInfo,
                    analysisSummary,
                    currentExecuteResult,
                    currentFailureParseResult,
                    currentReportFailures));
            controller.move("RESULT",
                    AgentState.S2_2_EXECUTION_FAILED,
                    "runtime still failing after bounded execution repair");
            logger.warn("[EXECUTION_REASONING] Test is still failing at runtime for method "
                    + generatedMethodName
                    + ". Continuing execution reasoning without regeneration.");
        }

        return new ExecutionRepairResult(false,
                false,
                currentCompileResult,
                currentExecuteResult,
                currentFailureParseResult,
                currentReportFailures,
                cumulativeResult);
    }

    private ReasoningResponse triggerReasoningWorkflow(AgentConfig config,
                                                       TestClassInfo classInfo,
                                                       TestMethodInfo methodInfo,
                                                       String generatedMethodName,
                                                       CompileResult compileResult,
                                                       ExecuteResult executeResult,
                                                       ActionExecutionResult actionResult,
                                                       ReasoningMemory memory,
                                                       ExecutionFailureParseResult failureParseResult,
                                                       List<TestReportFailure> reportFailures) {
        try {
            boolean compileFailure = compileResult != null && !compileResult.success();
            CompilationErrorInfo errorInfo = compileFailure
                    ? buildCompilationErrorInfo(compileResult, classInfo)
                    : buildExecutionErrorInfo(executeResult, classInfo, methodInfo, failureParseResult, reportFailures);
            ProjectContextSummary summary = buildProjectContextSummary(config, classInfo);
            com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorReport errorReport = compileFailure
                    ? new CompilationErrorClassifier().classify(compileResult.stderr())
                    : null;
            ReasoningLoopContext loopContext = nextContextBuilder.build(errorInfo,
                    summary,
                    actionResult,
                    errorReport,
                    memory,
                    compileFailure ? ReasoningStage.COMPILATION : ReasoningStage.EXECUTION);
            return reasoningService.reasonAboutError(loopContext, executionReasoningOptions(config.getProjectPath(), generatedMethodName));
        } catch (Exception exception) {
            logger.error("Reasoning workflow failed for method " + methodInfo.getSignature()
                    + ": " + exception.getMessage(), exception);
            return null;
        }
    }

    private CompilationReasoningService.ReasoningOptions executionReasoningOptions(Path projectRoot,
                                                                                   String generatedMethodName) {
        return new CompilationReasoningService.ReasoningOptions(
                EXECUTION_PROMPT_SUFFIX,
                new CompilationReasoningService.AttemptListener() {
                    @Override
                    public void beforeSend(int attempt, String prompt) {
                        Path promptFile = writeExecutionReasoningArtifact(projectRoot, generatedMethodName, attempt, "prompt", prompt);
                        logger.info("[EXECUTION_REASONING] Stage=SEND_TO_GIGACHAT method="
                                + generatedMethodName
                                + ", attempt="
                                + attempt
                                + ", promptFile="
                                + describeArtifactPath(promptFile));
                        logger.info("[EXECUTION_REASONING] LLM prompt attempt " + attempt + ":\n" + prompt);
                    }

                    @Override
                    public void afterReceive(int attempt, String prompt, String rawResponse) {
                        Path responseFile = writeExecutionReasoningArtifact(projectRoot,
                                generatedMethodName,
                                attempt,
                                "response",
                                rawResponse);
                        logger.info("[EXECUTION_REASONING] Stage=RECEIVE_FROM_GIGACHAT method="
                                + generatedMethodName
                                + ", attempt="
                                + attempt
                                + ", responseFile="
                                + describeArtifactPath(responseFile)
                                + ", responseLength="
                                + rawResponse.length());
                        logger.info("[EXECUTION_REASONING] LLM raw response attempt " + attempt + ":\n" + rawResponse);
                        logger.info("[EXECUTION_REASONING] Stage=PARSE_GIGACHAT_RESPONSE method="
                                + generatedMethodName
                                + ", attempt="
                                + attempt);
                    }

                    @Override
                    public void afterParse(int attempt, ReasoningResponse response) {
                        logger.info("[EXECUTION_REASONING] Parsed LLM response attempt "
                                + attempt
                                + ": decision="
                                + response.getDecision()
                                + ", actions="
                                + (response.getActions() == null ? 0 : response.getActions().size()));
                    }

                    @Override
                    public void onParseFailure(int attempt, String rawResponse, RuntimeException exception) {
                        logger.warn("[EXECUTION_REASONING] Failed to parse LLM response on attempt "
                                + attempt
                                + ": "
                                + exception.getMessage());
                        if (attempt >= 3) {
                            logger.warn("[EXECUTION_REASONING] Falling back to STOP after exhausting execution-only LLM retries.");
                        }
                    }
                });
    }

    private CompilationErrorInfo buildCompilationErrorInfo(CompileResult compileResult, TestClassInfo classInfo) {
        String primaryMessage = compileResult.messages().isEmpty()
                ? compileResult.stderr()
                : compileResult.messages().get(0);
        String compilerOutput = (compileResult.stdout() + System.lineSeparator() + compileResult.stderr()).trim();
        if (compilerOutput.isBlank()) {
            compilerOutput = String.join(System.lineSeparator(), compileResult.messages());
        }
        return new CompilationErrorInfo(
                compilerOutput,
                primaryMessage,
                classInfo.getTestClassName(),
                classInfo.getTargetPath().toString(),
                null,
                null
        );
    }

    private CompilationErrorInfo buildExecutionErrorInfo(ExecuteResult executeResult,
                                                         TestClassInfo classInfo,
                                                         TestMethodInfo methodInfo,
                                                         ExecutionFailureParseResult failureParseResult,
                                                         List<TestReportFailure> reportFailures) {
        TestReportFailure primaryFailure = reportFailures == null || reportFailures.isEmpty()
                ? null
                : reportFailures.get(0);
        String primaryMessage = executeResult.failedTests().isEmpty()
                ? executeResult.stderr()
                : executeResult.failedTests().get(0);
        if (primaryFailure != null && !primaryFailure.message().isBlank()) {
            primaryMessage = primaryFailure.message();
        }
        String output = (executeResult.stdout() + System.lineSeparator() + executeResult.stderr()).trim();
        if (output.isBlank()) {
            output = "Execution failed for " + methodInfo.getSignature();
        }
        if (failureParseResult != null && !failureParseResult.failures().isEmpty()) {
            output = output + System.lineSeparator()
                    + "Parsed failing tests: "
                    + failureParseResult.failures().stream()
                    .map(failure -> failure.className() + "." + failure.methodName())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
        }
        String stackTrace = primaryFailure == null || primaryFailure.stackTrace().isEmpty()
                ? null
                : String.join(System.lineSeparator(), primaryFailure.stackTrace());
        return new CompilationErrorInfo(
                output,
                primaryMessage,
                classInfo.getTestClassName(),
                classInfo.getTargetPath().toString(),
                null,
                stackTrace
        );
    }

    private ProjectContextSummary buildProjectContextSummary(AgentConfig config, TestClassInfo classInfo) {
        Path projectRoot = config.getProjectPath();
        Path testDirectory = classInfo.getTargetPath().getParent();
        ProjectContextSummary summary = new ProjectContextCollector(projectRoot).collect();
        if (testDirectory != null && !summary.getTestSourceRoots().contains(testDirectory.toString())) {
            java.util.LinkedHashSet<String> testRoots = new java.util.LinkedHashSet<>(summary.getTestSourceRoots());
            testRoots.add(testDirectory.toString());
            summary.setTestSourceRoots(List.copyOf(testRoots));
        }
        return summary;
    }

    private void logReasoningResponse(String stage, ReasoningResponse response) {
        if (response == null) {
            logger.warn("Reasoning workflow returned no response for " + stage + " failure.");
            return;
        }
        logger.info("Reasoning workflow result for "
                + stage
                + " failure: decision="
                + response.getDecision()
                + ", actions="
                + summariseReasoningActions(response)
                + ", memoryUpdates="
                + summariseMemoryUpdates(response));
    }

    private void applyMemoryUpdates(ReasoningMemory memory,
                                    ReasoningResponse response,
                                    ActionExecutionResult actionResult) {
        if (memory == null || response == null) {
            return;
        }
        memory.applyUpdates(response.getMemoryUpdates().getKnownMissingSymbols(),
                mergeAppliedFixSignatures(response.getMemoryUpdates().getAppliedFixSignatures(), actionResult),
                mergeContextCache(response.getMemoryUpdates().getContextCache(), extractContextCache(actionResult)));
    }

    private java.util.Set<String> mergeAppliedFixSignatures(java.util.Set<String> declaredFixes,
                                                            ActionExecutionResult actionResult) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (declaredFixes != null) {
            merged.addAll(declaredFixes);
        }
        merged.addAll(extractAppliedFixSignatures(actionResult));
        return merged;
    }

    private Map<String, String> mergeContextCache(Map<String, String> declaredUpdates,
                                                  Map<String, String> executionUpdates) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        if (declaredUpdates != null) {
            merged.putAll(declaredUpdates);
        }
        if (executionUpdates != null) {
            merged.putAll(executionUpdates);
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> extractContextCache(ActionExecutionResult result) {
        if (result == null || result.getInformation().isEmpty()) {
            return Map.of();
        }
        Object updates = result.getInformation().get("contextCacheUpdates");
        if (updates instanceof Map<?, ?> map) {
            LinkedHashMap<String, String> converted = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null && value != null) {
                    converted.put(key.toString(), value.toString());
                }
            });
            return converted;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private java.util.Set<String> extractAppliedFixSignatures(ActionExecutionResult result) {
        if (result == null || result.getInformation().isEmpty()) {
            return java.util.Set.of();
        }
        Object appliedRecipes = result.getInformation().get("appliedRecipeIds");
        if (!(appliedRecipes instanceof List<?> recipes) || recipes.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.LinkedHashSet<String> signatures = new java.util.LinkedHashSet<>();
        for (Object recipeId : recipes) {
            if (recipeId != null && !recipeId.toString().isBlank()) {
                signatures.add("RECIPE:" + recipeId);
            }
        }
        return signatures;
    }

    private boolean producedUsefulContext(ActionExecutionResult result) {
        if (result == null || result.getInformation().isEmpty()) {
            return false;
        }
        return result.getInformation().keySet().stream().anyMatch(key -> !"errors".equals(key));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractDeterministicRepairRecipes(ActionExecutionResult result) {
        if (result == null || result.getInformation().isEmpty()) {
            return List.of();
        }
        Object rawRecipes = result.getInformation().get("deterministicRepairRecipes");
        if (!(rawRecipes instanceof List<?> recipes)) {
            return List.of();
        }
        LinkedHashMap<String, Map<String, Object>> deduplicated = new LinkedHashMap<>();
        for (Object recipe : recipes) {
            if (recipe instanceof Map<?, ?> map && map.get("id") != null) {
                deduplicated.put(map.get("id").toString(), (Map<String, Object>) map);
            }
        }
        return List.copyOf(deduplicated.values());
    }

    private ToolAction singleDeterministicRecipeAction(List<Map<String, Object>> recipes,
                                                       ReasoningMemory memory) {
        if (recipes == null || recipes.size() != 1) {
            return null;
        }
        Object recipeId = recipes.get(0).get("id");
        if (recipeId == null || recipeId.toString().isBlank()) {
            return null;
        }
        String signature = "RECIPE:" + recipeId;
        if (memory != null && memory.getAppliedFixSignatures().contains(signature)) {
            return null;
        }
        ToolActionStep step = new ToolActionStep(ToolActionType.APPLY_RECIPE,
                Map.of("recipeId", recipeId.toString()));
        return new ToolAction(ToolActionType.APPLY_RECIPE, null, step);
    }

    private String deriveExecutionErrorSignature(ExecuteResult executeResult,
                                                 List<TestReportFailure> reportFailures) {
        if (reportFailures != null && !reportFailures.isEmpty()) {
            TestReportFailure failure = reportFailures.get(0);
            return failure.className() + "|" + failure.methodName() + "|" + failure.message();
        }
        if (executeResult == null) {
            return "execute|unknown";
        }
        String failedTest = executeResult.failedTests().isEmpty()
                ? "unknown"
                : executeResult.failedTests().get(0);
        return failedTest + "|" + executeResult.stderr();
    }

    private void logExecutionReasoningStart(String methodName,
                                            ExecuteResult executeResult,
                                            ActionExecutionResult cumulativeResult) {
        logger.info("[EXECUTION_REASONING] Starting execution reasoning for method " + methodName);
        if (executeResult != null) {
            logger.info("[EXECUTION_REASONING] Initial runtime failure summary for "
                    + methodName
                    + ": failedTests="
                    + executeResult.failedTests());
        }
        if (cumulativeResult != null && !cumulativeResult.getInformation().isEmpty()) {
            logger.info("[EXECUTION_REASONING] Collected execution context keys for "
                    + methodName
                    + ": "
                    + cumulativeResult.getInformation().keySet());
        }
    }

    private void logExecutionReasoningDecision(String methodName, ReasoningResponse response) {
        if (response == null) {
            logger.warn("[EXECUTION_REASONING] No reasoning response for method " + methodName);
            logger.trace("DECISION", methodName, AgentState.S2_2_EXECUTION_FAILED.name(), "no reasoning response");
            return;
        }
        logger.info("[EXECUTION_REASONING] Decision for "
                + methodName
                + ": "
                + response.getDecision()
                + "; actions="
                + summariseReasoningActions(response));
        logger.trace("DECISION",
                methodName,
                AgentState.S2_2_EXECUTION_FAILED.name(),
                "gigachat chose " + response.getDecision() + " actions=" + summariseReasoningActions(response));
    }

    private void logExecutionReasoningActionResult(String phase,
                                                   String methodName,
                                                   ActionExecutionResult result) {
        if (result == null) {
            logger.info("[EXECUTION_REASONING] " + phase + " for " + methodName + " produced no action result.");
            logger.trace("ACTION", methodName, AgentState.S2_2_EXECUTION_FAILED.name(), phase + " produced no action result");
            return;
        }
        logger.info("[EXECUTION_REASONING] "
                + phase
                + " for "
                + methodName
                + " -> performedActions="
                + result.getPerformedActions()
                + ", infoKeys="
                + result.getInformation().keySet());
        logger.trace("ACTION",
                methodName,
                AgentState.S2_2_EXECUTION_FAILED.name(),
                phase + " performedActions=" + result.getPerformedActions() + " infoKeys=" + result.getInformation().keySet());
    }

    private void logExecutionReasoningToolAction(String phase,
                                                 String methodName,
                                                 ToolAction toolAction) {
        if (toolAction == null) {
            logger.warn("[EXECUTION_REASONING] "
                    + phase
                    + " for "
                    + methodName
                    + " did not include executable tool actions.");
            logger.trace("ACTION", methodName, AgentState.S2_2_EXECUTION_FAILED.name(), phase + " did not include executable tool actions");
            return;
        }
        logger.info("[EXECUTION_REASONING] "
                + phase
                + " tool action for "
                + methodName
                + ": "
                + toolAction);
        logger.trace("ACTION",
                methodName,
                AgentState.S2_2_EXECUTION_FAILED.name(),
                phase + " executing " + toolAction);
    }

    private String summariseReasoningActions(ReasoningResponse response) {
        if (response == null || response.getActions() == null || response.getActions().isEmpty()) {
            return "[]";
        }
        return response.getActions().stream()
                .map(action -> {
                    String type = action.getType() == null ? "UNKNOWN" : action.getType();
                    Map<String, Object> args = action.getArgs() == null ? Map.of() : action.getArgs();
                    return type + args;
                })
                .reduce((left, right) -> left + ", " + right)
                .map(value -> "[" + value + "]")
                .orElse("[]");
    }

    private String summariseMemoryUpdates(ReasoningResponse response) {
        if (response == null) {
            return "{}";
        }
        ReasoningResponse.MemoryUpdate updates = response.getMemoryUpdates();
        return "{knownMissingSymbols="
                + updates.getKnownMissingSymbols()
                + ", appliedFixSignatures="
                + updates.getAppliedFixSignatures()
                + ", contextCacheKeys="
                + updates.getContextCache().keySet()
                + "}";
    }

    private Path writeExecutionReasoningArtifact(Path projectRoot,
                                                 String generatedMethodName,
                                                 int attempt,
                                                 String suffix,
                                                 String content) {
        Path artifactDir = projectRoot.resolve(".agent").resolve("logs").resolve("execution-reasoning");
        try {
            Files.createDirectories(artifactDir);
            Path artifact = artifactDir.resolve(sanitizeExecutionArtifactName(generatedMethodName)
                    + "-attempt-"
                    + attempt
                    + "."
                    + suffix
                    + ".txt");
            Files.writeString(artifact,
                    content == null ? "" : content,
                    StandardCharsets.UTF_8);
            return artifact.toAbsolutePath().normalize();
        } catch (IOException exception) {
            logger.warn("[EXECUTION_REASONING] Failed to persist "
                    + suffix
                    + " artifact for "
                    + generatedMethodName
                    + " attempt "
                    + attempt
                    + ": "
                    + exception.getMessage());
            return null;
        }
    }

    private String sanitizeExecutionArtifactName(String value) {
        String candidate = value == null || value.isBlank() ? "unknown-method" : value;
        return candidate.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private String describeArtifactPath(Path artifact) {
        return artifact == null ? "unavailable" : artifact.toString();
    }

    private ExecutionFailureParseResult parseExecutionLog(ExecuteResult executeResult) {
        if (executeResult == null) {
            return new ExecutionFailureParseResult(List.of(), Optional.empty());
        }
        String combined = (executeResult.stdout() + System.lineSeparator() + executeResult.stderr()).trim();
        return executionFailureLogParser.parse(combined);
    }

    private List<TestReportFailure> parseExecutionReport(Path projectRoot, ExecutionFailureParseResult parseResult) {
        if (parseResult == null || parseResult.reportPath().isEmpty()) {
            return List.of();
        }
        Path reportPath = parseResult.reportPath().get();
        Path resolved = reportPath.isAbsolute() ? reportPath : projectRoot.resolve(reportPath);
        try {
            return executionReportParser.parse(resolved);
        } catch (Exception exception) {
            logger.warn("Failed to parse execution report at " + resolved + ": " + exception.getMessage());
            return List.of();
        }
    }

    public record ExecutionRepairResult(boolean success,
                                        boolean shouldRegenerate,
                                        CompileResult compileResult,
                                        ExecuteResult executeResult,
                                        ExecutionFailureParseResult failureParseResult,
                                        List<TestReportFailure> reportFailures,
                                        ActionExecutionResult actionExecutionResult) {
    }
}
