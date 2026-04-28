package com.gigachat.unit.tests.generator.reasoning.orchestrator;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.coverage.CoverageResult;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
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
import com.gigachat.unit.tests.generator.reasoning.service.CompilationReasoningService;
import com.gigachat.unit.tests.generator.reasoning.service.DeterministicCoverageRecipeBuilder;
import com.gigachat.unit.tests.generator.reasoning.service.NextContextBuilder;
import com.gigachat.unit.tests.generator.reasoning.service.ProjectContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.ToolActionExecutor;
import com.gigachat.unit.tests.generator.reasoning.workflow.exception.FixingFailureException;
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
import java.util.function.Supplier;

/**
 * Owns the bounded coverage stage so method orchestration no longer embeds JaCoCo-specific
 * control flow inline. The current implementation measures and classifies coverage; a repair loop
 * can be plugged into the same stage boundary later.
 */
public class CoveragePipelineOrchestrator {

    private final PipelineLogger logger;
    private final CompilerInvoker compilerInvoker;
    private final ExecutionInvoker executionInvoker;
    private final CompilationReasoningService reasoningService;
    private final ReasoningLoopPolicy loopPolicy;
    private final NextContextBuilder nextContextBuilder;
    private final DeterministicCoverageRecipeBuilder coverageRecipeBuilder;

    public CoveragePipelineOrchestrator(PipelineLogger logger,
                                        CompilerInvoker compilerInvoker,
                                        ExecutionInvoker executionInvoker,
                                        CompilationReasoningService reasoningService) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.executionInvoker = Objects.requireNonNull(executionInvoker, "executionInvoker");
        this.reasoningService = Objects.requireNonNull(reasoningService, "reasoningService");
        this.loopPolicy = new ReasoningLoopPolicyCatalog().coveragePolicy();
        this.nextContextBuilder = new NextContextBuilder();
        this.coverageRecipeBuilder = new DeterministicCoverageRecipeBuilder();
    }

    public CoverageStageResult run(Path projectRoot,
                                   Path testFile,
                                   String testFileFqcn,
                                   String generatedMethodName,
                                   List<Integer> coverageGoals,
                                   Supplier<CoverageResult> measurement,
                                   ToolActionExecutor actionExecutor,
                                   ProjectContextCollector projectContextCollector,
                                   CompilationPipelineOrchestrator fixingOrchestrator,
                                   RuntimeRegressionHandler runtimeRegressionHandler) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(testFile, "testFile");
        Objects.requireNonNull(generatedMethodName, "generatedMethodName");
        Objects.requireNonNull(measurement, "measurement");
        Objects.requireNonNull(actionExecutor, "actionExecutor");
        Objects.requireNonNull(projectContextCollector, "projectContextCollector");
        Objects.requireNonNull(fixingOrchestrator, "fixingOrchestrator");
        List<Integer> goals = normaliseCoverageGoals(coverageGoals);

        StateGraphController controller = new StateGraphController(
                logger,
                generatedMethodName,
                loopPolicy,
                AgentState.S5_COMPILATION_SUCCESS,
                "starting coverage stage");
        logger.trace("STATE",
                generatedMethodName,
                ReasoningStage.COVERAGE.name(),
                "running jacoco coverage goals=" + goals);

        CoverageResult coverageResult = normaliseCoverageResult(measurement.get());
        if (coverageResult == null) {
            coverageResult = new CoverageResult(false, false, null, null, "", "Coverage stage returned no result");
        }

        ActionExecutionResult cumulativeResult = buildInitialCoverageContext(testFile, generatedMethodName);
        ProjectContextSummary projectContextSummary = projectContextCollector.collect();
        ReasoningMemory memory = controller.memory();
        logCoverageReasoningStart(generatedMethodName, coverageResult);
        int runtimeRegressionRepairAttempts = 0;
        int maxRuntimeRegressionRepairAttempts = loopPolicy.runtimeRegressionPolicyInt(
                "maxRuntimeRepairAttempts",
                1);

        for (int goal : goals) {
            logger.trace("STATE",
                    generatedMethodName,
                    ReasoningStage.COVERAGE.name(),
                    "coverage goal=" + goal + " current=" + describeCoverageProgress(coverageResult));
            if (coverageResult.meetsGoal(goal)) {
                logger.trace("RESULT",
                        generatedMethodName,
                        "COVERAGE_GOAL_REACHED",
                        "goal=" + goal + " current=" + describeCoverageProgress(coverageResult));
                continue;
            }

            boolean goalReached = false;
            for (int iteration = 0; iteration < loopPolicy.maxIterations(); iteration++) {
                controller.incrementAttempt();
                if (isRuntimeFailureBlockingCoverage(coverageResult) && runtimeRegressionHandler == null) {
                    controller.move("RESULT",
                            AgentState.S2_2_EXECUTION_FAILED,
                            "coverage measurement detected failing sibling tests and no runtime repair handler is available");
                    controller.move("RESULT",
                            AgentState.S6_GIVE_UP,
                            "coverage blocked by failing sibling tests without runtime repair handoff");
                    return new CoverageStageResult(false, failureCoverageResult(
                            "Coverage validation is blocked by failing sibling tests and no runtime repair handler is configured",
                            coverageResult));
                }
                RuntimeRepairAttempt runtimeRepairAttempt = attemptRuntimeRepairBeforeCoverageReasoning(projectRoot,
                        testFile,
                        coverageResult,
                        measurement,
                        runtimeRegressionHandler,
                        controller);
                if (runtimeRepairAttempt != null) {
                    if (runtimeRepairAttempt.actionExecutionResult() != null) {
                        cumulativeResult = cumulativeResult.merge(runtimeRepairAttempt.actionExecutionResult());
                    }
                    CoverageResult repairedCoverageResult = runtimeRepairAttempt.coverageResult();
                    if (repairedCoverageResult.meetsGoal(goal)) {
                        logger.trace("RESULT",
                                generatedMethodName,
                                "COVERAGE_GOAL_REACHED",
                                "goal=" + goal + " current=" + describeCoverageProgress(repairedCoverageResult));
                        coverageResult = repairedCoverageResult;
                        goalReached = true;
                        break;
                    }
                    coverageResult = repairedCoverageResult;
                    if (controller.memory().getState() == AgentState.S2_2_EXECUTION_FAILED) {
                        controller.move("RESULT",
                                AgentState.S6_GIVE_UP,
                                "coverage blocked by unresolved runtime failures in generated sibling tests");
                        return new CoverageStageResult(false, coverageResult);
                    }
                }
                String signature = "goal=" + goal
                        + " :: contextBudget=" + controller.contextBudgetRemaining()
                        + " :: " + coverageResult.describeFailure(goal);
                controller.addErrorSignature(signature);
                controller.move("RESULT", AgentState.S8_COVERAGE_FAILED, signature);
                if (controller.countOccurrences(signature) >= loopPolicy.repeatedSignatureThreshold()) {
                    controller.move("RESULT", AgentState.S6_GIVE_UP, "repeated coverage failure signature=" + signature);
                    return new CoverageStageResult(false, coverageResult);
                }

                List<Map<String, Object>> deterministicRecipes = coverageRecipeBuilder.build(
                        testFile,
                        generatedMethodName,
                        coverageResult,
                        goal);
                actionExecutor.setAvailableRecipes(deterministicRecipes);
                ActionExecutionResult promptContext = appendDeterministicRecipes(cumulativeResult, deterministicRecipes);
                ReasoningLoopContext loopContext = nextContextBuilder.build(
                        coverageErrorInfo(coverageResult, goal, testFile, testFileFqcn),
                        projectContextSummary,
                        promptContext,
                        null,
                        memory,
                        ReasoningStage.COVERAGE);
                ReasoningResponse response = reasoningService.reasonAboutError(
                        loopContext,
                        coverageReasoningOptions(projectRoot, generatedMethodName));
                logReasoningResponse("coverage", response);
                logCoverageReasoningDecision(generatedMethodName, response, coverageResult, goal);
                String decision = normaliseDecision(response);
                controller.logDecision("coverage goal=" + goal
                        + " current=" + describeCoverageProgress(coverageResult)
                        + " reasoning chose " + decision);

                ToolAction deterministicStopFallbackAction = null;
                if ("STOP".equals(decision)) {
                    deterministicStopFallbackAction = singleDeterministicRecipeAction(deterministicRecipes);
                    if (deterministicStopFallbackAction == null) {
                        controller.moveForDecision("RESULT", decision, AgentState.S6_GIVE_UP, "coverage reasoning stopped");
                        return new CoverageStageResult(false, coverageResult);
                    }
                    decision = "APPLY_FIX";
                    logger.info("[COVERAGE_REASONING] Reasoning stopped, applying single deterministic coverage recipe for "
                            + generatedMethodName);
                    controller.logDecision("coverage reasoning stopped; applying single deterministic recipe fallback");
                }

                if ("REQUEST_CONTEXT".equals(decision)) {
                    ToolAction toolAction = response == null ? null : response.toToolAction();
                    logCoverageReasoningToolAction("REQUEST_CONTEXT", generatedMethodName, toolAction);
                    ActionExecutionResult iterationResult = actionExecutor.execute(toolAction);
                    cumulativeResult = cumulativeResult.merge(iterationResult);
                    applyMemoryUpdates(memory, response, iterationResult);
                    logCoverageReasoningActionResult("REQUEST_CONTEXT", generatedMethodName, iterationResult);
                    controller.logAction("coverage context result performedActions="
                            + iterationResult.getPerformedActions()
                            + " infoKeys="
                            + iterationResult.getInformation().keySet()
                            + " contextKeys="
                            + extractContextCache(iterationResult).keySet());
                    controller.moveForDecision("STATE", decision, AgentState.S2_1_NEED_MORE_CONTEXT, "coverage context requested");
                    controller.decrementContextBudget();
                    if (controller.contextBudgetRemaining() <= 0) {
                        controller.move("RESULT", AgentState.S6_GIVE_UP, "coverage context budget exhausted");
                        return new CoverageStageResult(false, coverageResult);
                    }
                    controller.move("STATE",
                            AgentState.S5_COMPILATION_SUCCESS,
                            "coverage context collected; reevaluating current goal");
                    continue;
                }

                if (!"APPLY_FIX".equals(decision)) {
                    controller.move("RESULT", AgentState.S6_GIVE_UP, "unsupported coverage reasoning decision=" + decision);
                    return new CoverageStageResult(false, coverageResult);
                }

                ToolAction toolAction = deterministicStopFallbackAction == null
                        ? (response == null ? null : response.toToolAction())
                        : deterministicStopFallbackAction;
                logCoverageReasoningToolAction("APPLY_FIX", generatedMethodName, toolAction);
                ActionExecutionResult iterationResult = actionExecutor.execute(toolAction);
                cumulativeResult = cumulativeResult.merge(iterationResult);
                applyMemoryUpdates(memory, response, iterationResult);
                logCoverageReasoningActionResult("APPLY_FIX", generatedMethodName, iterationResult);
                controller.logAction("coverage goal=" + goal
                        + " fix result performedActions="
                        + iterationResult.getPerformedActions()
                        + " infoKeys="
                        + iterationResult.getInformation().keySet()
                        + " contextKeys="
                        + extractContextCache(iterationResult).keySet());
                if (iterationResult.getPerformedActions().isEmpty()) {
                    controller.logAction("coverage fix produced no persisted change; skipping validation cycle");
                    continue;
                }
                controller.moveForDecision("TRANSITION", decision, AgentState.S4_FIX_APPLIED, "coverage fix applied; recompiling");

                CompileResult compileResult = compilerInvoker.compileWithoutCache(projectRoot, testFile, generatedMethodName);
                if (!compileResult.success()) {
                    try {
                        compileResult = fixingOrchestrator.runFixingLoop();
                    } catch (FixingFailureException exception) {
                        compileResult = exception.getLastResult();
                    }
                }
                if (compileResult == null || !compileResult.success()) {
                    return new CoverageStageResult(false, failureCoverageResult(
                            "Coverage fix broke compilation: " + summariseCompileFailure(compileResult),
                            coverageResult));
                }

                ExecuteResult executeResult = executionInvoker.execute(projectRoot, testFile, "");
                if (executeResult == null || !executeResult.success()) {
                    controller.move("RESULT",
                            AgentState.S2_2_EXECUTION_FAILED,
                            "coverage fix regressed runtime behavior");
                    if (runtimeRegressionHandler == null) {
                        return new CoverageStageResult(false, failureCoverageResult(
                                "Coverage fix broke test execution: " + summariseExecutionFailure(executeResult),
                                coverageResult));
                    }
                    runtimeRegressionRepairAttempts++;
                    if (runtimeRegressionRepairAttempts > maxRuntimeRegressionRepairAttempts) {
                        controller.move("RESULT",
                                AgentState.S6_GIVE_UP,
                                "coverage fix repeatedly regressed runtime behavior after deterministic runtime reroute");
                        return new CoverageStageResult(false, failureCoverageResult(
                                "Coverage fix repeatedly broke test execution after deterministic runtime repair: "
                                        + summariseExecutionFailure(executeResult),
                                coverageResult));
                    }
                    RuntimeRegressionResult runtimeRepairResult = runtimeRegressionHandler.repair(compileResult, executeResult);
                    if (runtimeRepairResult == null || !runtimeRepairResult.success()) {
                        return new CoverageStageResult(false, failureCoverageResult(
                                "Coverage fix broke test execution and runtime repair failed: "
                                        + summariseExecutionFailure(executeResult),
                                coverageResult));
                    }
                    if (runtimeRepairResult.actionExecutionResult() != null) {
                        cumulativeResult = cumulativeResult.merge(runtimeRepairResult.actionExecutionResult());
                    }
                    compileResult = runtimeRepairResult.compileResult();
                    executeResult = runtimeRepairResult.executeResult();
                    controller.resetContextBudget();
                    controller.move("TRANSITION",
                            AgentState.S4_FIX_APPLIED,
                            "runtime regression repaired during coverage loop");
                    controller.move("STATE",
                            AgentState.S5_COMPILATION_SUCCESS,
                            "runtime regression repaired during coverage loop");
                }

                if (executeResult != null && executeResult.success()) {
                    controller.move("STATE",
                            AgentState.S5_COMPILATION_SUCCESS,
                            "coverage fix validated; rerunning coverage measurement");
                }
                coverageResult = normaliseCoverageResult(measurement.get());
                logger.trace("RESULT",
                        generatedMethodName,
                        "COVERAGE_REMEASURE",
                        "goal=" + goal + " current=" + describeCoverageProgress(coverageResult));
                if (coverageResult.meetsGoal(goal)) {
                    logger.trace("RESULT",
                            generatedMethodName,
                            "COVERAGE_GOAL_REACHED",
                            "goal=" + goal + " current=" + describeCoverageProgress(coverageResult));
                    goalReached = true;
                    break;
                }
            }

            if (!goalReached) {
                controller.move("RESULT", AgentState.S6_GIVE_UP, "reached max coverage-fix iterations for goal=" + goal);
                return new CoverageStageResult(false, coverageResult);
            }
        }

        logger.trace("RESULT",
                generatedMethodName,
                "SUCCESS",
                "coverage goals reached goals=" + goals + " current=" + describeCoverageProgress(coverageResult));
        return new CoverageStageResult(true, coverageResult);
    }

    public record CoverageStageResult(boolean success, CoverageResult coverageResult) {
    }

    public interface RuntimeRegressionHandler {
        RuntimeRegressionResult repair(CompileResult compileResult, ExecuteResult executeResult);
    }

    public record RuntimeRegressionResult(boolean success,
                                          CompileResult compileResult,
                                          ExecuteResult executeResult,
                                          ActionExecutionResult actionExecutionResult) {
    }

    private record RuntimeRepairAttempt(CoverageResult coverageResult,
                                        ActionExecutionResult actionExecutionResult) {
    }

    private CoverageResult normaliseCoverageResult(CoverageResult coverageResult) {
        if (coverageResult != null) {
            return coverageResult;
        }
        return new CoverageResult(false, false, null, null, "", "Coverage stage returned no result");
    }

    private RuntimeRepairAttempt attemptRuntimeRepairBeforeCoverageReasoning(Path projectRoot,
                                                                            Path testFile,
                                                                            CoverageResult coverageResult,
                                                                            Supplier<CoverageResult> measurement,
                                                                            RuntimeRegressionHandler runtimeRegressionHandler,
                                                                            StateGraphController controller) {
        if (!isRuntimeFailureBlockingCoverage(coverageResult) || runtimeRegressionHandler == null) {
            return null;
        }
        controller.move("RESULT",
                AgentState.S2_2_EXECUTION_FAILED,
                "coverage measurement detected failing tests in the validated test class");
        CompileResult compileResult = compilerInvoker.compileWithoutCache(projectRoot, testFile, "");
        if (compileResult == null || !compileResult.success()) {
            return new RuntimeRepairAttempt(failureCoverageResult(
                    "Coverage validation detected failing tests and recompilation also failed: "
                            + summariseCompileFailure(compileResult),
                    coverageResult), ActionExecutionResult.empty());
        }
        ExecuteResult executeResult = executionInvoker.execute(projectRoot, testFile, "");
        if (executeResult == null || executeResult.success()) {
            return null;
        }
        RuntimeRegressionResult runtimeRepairResult = runtimeRegressionHandler.repair(compileResult, executeResult);
        if (runtimeRepairResult == null || !runtimeRepairResult.success()) {
            return new RuntimeRepairAttempt(failureCoverageResult(
                    "Coverage validation detected failing tests and runtime repair failed: "
                            + summariseExecutionFailure(executeResult),
                    coverageResult),
                    runtimeRepairResult == null || runtimeRepairResult.actionExecutionResult() == null
                            ? ActionExecutionResult.empty()
                            : runtimeRepairResult.actionExecutionResult());
        }
        controller.resetContextBudget();
        controller.move("TRANSITION",
                AgentState.S4_FIX_APPLIED,
                "runtime regression repaired before coverage reasoning");
        controller.move("STATE",
                AgentState.S5_COMPILATION_SUCCESS,
                "runtime regression repaired before coverage reasoning");
        return new RuntimeRepairAttempt(
                normaliseCoverageResult(measurement.get()),
                runtimeRepairResult.actionExecutionResult() == null
                        ? ActionExecutionResult.empty()
                        : runtimeRepairResult.actionExecutionResult());
    }

    private boolean isRuntimeFailureBlockingCoverage(CoverageResult coverageResult) {
        if (coverageResult == null || coverageResult.success()) {
            return false;
        }
        String combined = (coverageResult.stdout() + "\n" + coverageResult.stderr() + "\n" + coverageResult.describeFailure())
                .toLowerCase(java.util.Locale.ROOT);
        return combined.contains("there were failing tests")
                || combined.contains("tests completed,")
                || combined.contains("execution failed for task ':test'")
                || combined.contains("failures (");
    }

    private CompilationErrorInfo coverageErrorInfo(CoverageResult coverageResult,
                                                   int goalPercent,
                                                   Path testFile,
                                                   String testFileFqcn) {
        return new CompilationErrorInfo(
                coverageResult == null ? "" : coverageResult.stdout(),
                coverageResult == null ? "Coverage stage returned no result" : coverageResult.describeFailure(goalPercent),
                testFileFqcn,
                testFile == null ? "" : testFile.toAbsolutePath().normalize().toString(),
                null,
                coverageResult == null ? "" : coverageResult.stderr());
    }

    private ActionExecutionResult appendDeterministicRecipes(ActionExecutionResult base,
                                                             List<Map<String, Object>> recipes) {
        if (recipes == null || recipes.isEmpty()) {
            return base;
        }
        return base.merge(new ActionExecutionResult(Map.of("deterministicRepairRecipes", recipes)));
    }

    private ToolAction singleDeterministicRecipeAction(List<Map<String, Object>> recipes) {
        if (recipes == null || recipes.size() != 1) {
            return null;
        }
        Object recipeId = recipes.get(0).get("id");
        if (recipeId == null || recipeId.toString().isBlank()) {
            return null;
        }
        ToolActionStep step = new ToolActionStep(ToolActionType.APPLY_RECIPE,
                Map.of("recipeId", recipeId.toString()));
        return new ToolAction(ToolActionType.APPLY_RECIPE, null, step);
    }

    private void applyMemoryUpdates(ReasoningMemory memory,
                                    ReasoningResponse response,
                                    ActionExecutionResult iterationResult) {
        if (memory == null || response == null) {
            return;
        }
        memory.applyUpdates(response.getMemoryUpdates().getKnownMissingSymbols(),
                response.getMemoryUpdates().getAppliedFixSignatures(),
                extractContextCache(iterationResult));
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, String> extractContextCache(ActionExecutionResult result) {
        if (result == null || result.getInformation().isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        Object updates = result.getInformation().get("contextCacheUpdates");
        if (updates instanceof java.util.Map<?, ?> map) {
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

    private String normaliseDecision(ReasoningResponse response) {
        if (response == null || response.getDecision() == null || response.getDecision().isBlank()) {
            return "STOP";
        }
        return response.getDecision().trim().toUpperCase();
    }

    private CoverageResult failureCoverageResult(String message, CoverageResult previous) {
        return new CoverageResult(false,
                previous != null && previous.reportGenerated(),
                null,
                previous == null ? null : previous.xmlReport(),
                previous == null ? "" : previous.stdout(),
                message);
    }

    private String summariseCompileFailure(CompileResult compileResult) {
        if (compileResult == null) {
            return "unknown compilation failure";
        }
        if (compileResult.stderr() != null && !compileResult.stderr().isBlank()) {
            return compileResult.stderr().replaceAll("\\s+", " ").trim();
        }
        if (compileResult.messages() != null && !compileResult.messages().isEmpty()) {
            return String.join(" | ", compileResult.messages()).replaceAll("\\s+", " ").trim();
        }
        return "unknown compilation failure";
    }

    private String summariseExecutionFailure(ExecuteResult executeResult) {
        if (executeResult == null) {
            return "unknown execution failure";
        }
        if (executeResult.stderr() != null && !executeResult.stderr().isBlank()) {
            return executeResult.stderr().replaceAll("\\s+", " ").trim();
        }
        if (executeResult.failedTests() != null && !executeResult.failedTests().isEmpty()) {
            return String.join(" | ", executeResult.failedTests()).replaceAll("\\s+", " ").trim();
        }
        return "unknown execution failure";
    }

    private List<Integer> normaliseCoverageGoals(List<Integer> coverageGoals) {
        if (coverageGoals == null || coverageGoals.isEmpty()) {
            return List.of(100);
        }
        return coverageGoals.stream()
                .filter(Objects::nonNull)
                .map(goal -> Math.max(1, Math.min(100, goal)))
                .distinct()
                .sorted()
                .toList();
    }

    private String describeCoverageProgress(CoverageResult coverageResult) {
        if (coverageResult == null || coverageResult.summary() == null) {
            return "unknown";
        }
        return String.format(java.util.Locale.ROOT,
                "%.1f%% (lines=%.1f%%, branches=%.1f%%)",
                coverageResult.summary().combinedCoveragePercent(),
                coverageResult.summary().lineCoveragePercent(),
                coverageResult.summary().branchCoveragePercent());
    }

    private ActionExecutionResult buildInitialCoverageContext(Path testFile, String generatedMethodName) {
        LinkedHashMap<String, Object> information = new LinkedHashMap<>();
        LinkedHashMap<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("generatedMethodName", generatedMethodName);
        if (testFile != null) {
            baseline.put("testFilePath", testFile.toAbsolutePath().normalize().toString());
        }
        information.put("coverageBaseline", baseline);
        if (testFile != null && Files.exists(testFile)) {
            try {
                information.put("fileContents", List.of(Map.of(
                        "path", testFile.toAbsolutePath().normalize().toString(),
                        "content", Files.readString(testFile, StandardCharsets.UTF_8))));
            } catch (IOException exception) {
                logger.warn("[COVERAGE_REASONING] Failed to capture current test file baseline for "
                        + generatedMethodName
                        + ": "
                        + exception.getMessage());
            }
        }
        return new ActionExecutionResult(information);
    }

    private CompilationReasoningService.ReasoningOptions coverageReasoningOptions(Path projectRoot,
                                                                                  String generatedMethodName) {
        return new CompilationReasoningService.ReasoningOptions(
                "",
                new CompilationReasoningService.AttemptListener() {
                    @Override
                    public void beforeSend(int attempt, String prompt) {
                        Path promptFile = writeCoverageReasoningArtifact(projectRoot, generatedMethodName, attempt, "prompt", prompt);
                        logger.info("[COVERAGE_REASONING] Stage=SEND_TO_GIGACHAT method="
                                + generatedMethodName
                                + ", attempt="
                                + attempt
                                + ", promptFile="
                                + describeArtifactPath(promptFile));
                        logger.info("[COVERAGE_REASONING] LLM prompt attempt " + attempt + ":\n" + prompt);
                    }

                    @Override
                    public void afterReceive(int attempt, String prompt, String rawResponse) {
                        Path responseFile = writeCoverageReasoningArtifact(projectRoot,
                                generatedMethodName,
                                attempt,
                                "response",
                                rawResponse);
                        logger.info("[COVERAGE_REASONING] Stage=RECEIVE_FROM_GIGACHAT method="
                                + generatedMethodName
                                + ", attempt="
                                + attempt
                                + ", responseFile="
                                + describeArtifactPath(responseFile)
                                + ", responseLength="
                                + rawResponse.length());
                        logger.info("[COVERAGE_REASONING] LLM raw response attempt " + attempt + ":\n" + rawResponse);
                    }

                    @Override
                    public void afterParse(int attempt, ReasoningResponse response) {
                        logger.info("[COVERAGE_REASONING] Parsed LLM response attempt "
                                + attempt
                                + ": decision="
                                + response.getDecision()
                                + ", actions="
                                + (response.getActions() == null ? 0 : response.getActions().size()));
                    }

                    @Override
                    public void onParseFailure(int attempt, String rawResponse, RuntimeException exception) {
                        logger.warn("[COVERAGE_REASONING] Failed to parse LLM response on attempt "
                                + attempt
                                + ": "
                                + exception.getMessage());
                        if (attempt >= 3) {
                            logger.warn("[COVERAGE_REASONING] Falling back to STOP after exhausting coverage LLM retries.");
                        }
                    }
                });
    }

    private void logCoverageReasoningStart(String methodName, CoverageResult coverageResult) {
        logger.info("[COVERAGE_REASONING] Starting coverage reasoning for method "
                + methodName
                + " with current="
                + describeCoverageProgress(coverageResult));
    }

    private void logCoverageReasoningDecision(String methodName,
                                              ReasoningResponse response,
                                              CoverageResult coverageResult,
                                              int goal) {
        if (response == null) {
            logger.warn("[COVERAGE_REASONING] No reasoning response for method " + methodName);
            return;
        }
        logger.info("[COVERAGE_REASONING] Decision for "
                + methodName
                + ": "
                + response.getDecision()
                + "; goal="
                + goal
                + "; current="
                + describeCoverageProgress(coverageResult)
                + "; actions="
                + summariseReasoningActions(response));
    }

    private void logCoverageReasoningActionResult(String phase,
                                                  String methodName,
                                                  ActionExecutionResult result) {
        if (result == null) {
            logger.info("[COVERAGE_REASONING] " + phase + " for " + methodName + " produced no action result.");
            return;
        }
        logger.info("[COVERAGE_REASONING] "
                + phase
                + " for "
                + methodName
                + " -> performedActions="
                + result.getPerformedActions()
                + ", infoKeys="
                + result.getInformation().keySet()
                + ", contextKeys="
                + extractContextCache(result).keySet());
    }

    private void logCoverageReasoningToolAction(String phase,
                                                String methodName,
                                                ToolAction toolAction) {
        if (toolAction == null) {
            logger.warn("[COVERAGE_REASONING] "
                    + phase
                    + " for "
                    + methodName
                    + " did not include executable tool actions.");
            return;
        }
        logger.info("[COVERAGE_REASONING] "
                + phase
                + " tool action for "
                + methodName
                + ": "
                + toolAction);
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

    private Path writeCoverageReasoningArtifact(Path projectRoot,
                                                String generatedMethodName,
                                                int attempt,
                                                String suffix,
                                                String content) {
        Path artifactDir = projectRoot.resolve(".agent").resolve("logs").resolve("coverage-reasoning");
        try {
            Files.createDirectories(artifactDir);
            Path artifact = artifactDir.resolve(sanitizeCoverageArtifactName(generatedMethodName)
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
            logger.warn("[COVERAGE_REASONING] Failed to persist "
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

    private String sanitizeCoverageArtifactName(String value) {
        String candidate = value == null || value.isBlank() ? "unknown-method" : value;
        return candidate.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private String describeArtifactPath(Path artifact) {
        return artifact == null ? "unavailable" : artifact.toString();
    }
}
