package com.gigachat.unit.tests.generator.pipeline.orchestrator;

import com.gigachat.unit.tests.generator.cleaner.parser.ExecutionFailureParseResult;
import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.coverage.CoverageResult;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.PipelineModuleConfig;
import com.gigachat.unit.tests.generator.dto.CompileErrors;
import com.gigachat.unit.tests.generator.dto.ErrorsReport;
import com.gigachat.unit.tests.generator.dto.ExecuteErrors;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.dto.CoverageErrors;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.DiffEngine;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.pipeline.helpers.PreMergeScratchValidator;
import com.gigachat.unit.tests.generator.pipeline.helpers.PromptBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.SkeletonPromptBuilder;
import com.gigachat.unit.tests.generator.reasoning.model.AgentState;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningStage;
import com.gigachat.unit.tests.generator.reasoning.orchestrator.CompilationPipelineOrchestrator;
import com.gigachat.unit.tests.generator.reasoning.orchestrator.CoveragePipelineOrchestrator;
import com.gigachat.unit.tests.generator.reasoning.orchestrator.CoveragePipelineOrchestrator.CoverageStageResult;
import com.gigachat.unit.tests.generator.reasoning.orchestrator.CoveragePipelineOrchestrator.RuntimeRegressionResult;
import com.gigachat.unit.tests.generator.reasoning.orchestrator.ExecutionPipelineOrchestrator.ExecutionRepairResult;
import com.gigachat.unit.tests.generator.reasoning.service.ProjectContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.ToolActionExecutor;
import com.gigachat.unit.tests.generator.reasoning.workflow.exception.FixingFailureException;
import com.gigachat.unit.tests.generator.report.parser.TestReportFailure;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Owns the per-method generation lifecycle:
 * generate -> merge -> compile -> execute -> coverage -> regenerate.
 *
 * <p>The large validation helpers still live in {@code InitialGenerationStep}, but the procedural
 * stage loop is isolated here so the main pipeline entrypoint no longer carries the full method
 * orchestration logic inline.</p>
 */
public class GenerationMethodOrchestrator {

    private static final int MAX_ATTEMPTS = 5;

    private final PipelineLogger logger;
    private final SkeletonPromptBuilder skeletonPromptBuilder;
    private final Analyze analyze;
    private final PromptBuilder promptBuilder;
    private final DiffEngine diffEngine;
    private final CompilerInvoker compilerInvoker;
    private final ExecutionInvoker executionInvoker;
    private final CoveragePipelineOrchestrator coverageOrchestrator;
    private final PreMergeScratchValidator preMergeScratchValidator;
    private final Support support;

    public GenerationMethodOrchestrator(PipelineLogger logger,
                                        SkeletonPromptBuilder skeletonPromptBuilder,
                                        Analyze analyze,
                                        PromptBuilder promptBuilder,
                                        DiffEngine diffEngine,
                                        CompilerInvoker compilerInvoker,
                                        ExecutionInvoker executionInvoker,
                                        CoveragePipelineOrchestrator coverageOrchestrator,
                                        Support support) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.skeletonPromptBuilder = Objects.requireNonNull(skeletonPromptBuilder, "skeletonPromptBuilder");
        this.analyze = Objects.requireNonNull(analyze, "analyze");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.diffEngine = Objects.requireNonNull(diffEngine, "diffEngine");
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.executionInvoker = Objects.requireNonNull(executionInvoker, "executionInvoker");
        this.coverageOrchestrator = Objects.requireNonNull(coverageOrchestrator, "coverageOrchestrator");
        this.preMergeScratchValidator = new PreMergeScratchValidator(logger, compilerInvoker, executionInvoker);
        this.support = Objects.requireNonNull(support, "support");
    }

    public void processMethod(AgentConfig config,
                              TestClassInfo classInfo,
                              TestMethodInfo methodInfo,
                              PipelineModuleConfig moduleConfig,
                              ErrorsReport report) {
        logger.info("Processing method " + methodInfo.getSignature() + " for class " + classInfo.getClassName());
        logger.trace("FLOW", methodInfo.getSignature(), AgentState.S0_INIT.name(), "starting generation pipeline");

        String skeletonPrompt = skeletonPromptBuilder.build(classInfo, methodInfo);
        Analyze.AnalysisSummary analysisSummary = analyze.analyze(config, classInfo, methodInfo);
        if (!analysisSummary.invalidCalls().isEmpty()) {
            logger.warn("[WARN] Some inferred invocations were excluded (nonexistent in class metadata):");
            for (String invalidCall : analysisSummary.invalidCalls()) {
                logger.warn("  - " + invalidCall);
            }
        }

        MockPlan plan = analysisSummary.mockPlan();
        String promptJson = promptBuilder.build(config, classInfo, methodInfo, skeletonPrompt, analysisSummary);
        JSONObject contextJson = toJsonObject(promptJson, methodInfo);
        logger.info("-> DEBUG info about tested method \n" + methodInfo);

        GeneratedTestSnippet snippet = support.requestSnippet(config,
                classInfo,
                methodInfo,
                plan,
                contextJson,
                analysisSummary,
                moduleConfig,
                false);
        if (snippet == null) {
            logger.warn("LLM did not return a snippet for method " + methodInfo.getSignature());
            logger.trace("RESULT", methodInfo.getSignature(), AgentState.S6_GIVE_UP.name(), "generation produced no valid snippet");
            return;
        }

        ProjectContextCollector projectContextCollector = new ProjectContextCollector(config.getProjectPath());
        boolean success = false;
        int attempt = 0;
        JSONObject repairContext = contextJson;
        DiffEngine.MergeResult mergeResult = null;
        CompileResult lastCompileResult = null;
        ExecuteResult lastExecuteResult = null;
        ExecutionFailureParseResult lastFailureParseResult = null;
        List<TestReportFailure> lastReportFailures = List.of();
        List<String> recordedCompileErrorMethods = new ArrayList<>();
        List<String> recordedExecuteErrorMethods = new ArrayList<>();

        while (attempt < MAX_ATTEMPTS) {
            PreMergeScratchValidator.ValidationResult scratchValidation = preMergeScratchValidator.validate(
                    config.getProjectPath(),
                    classInfo,
                    snippet,
                    moduleConfig.compileEnabled(),
                    moduleConfig.executeEnabled(),
                    scratchFailure -> {
                        logger.warn("[COMPILATION_REASONING] Pre-merge scratch compile errors for "
                                + scratchFailure.generatedMethodName()
                                + ": "
                                + summariseCompileFailure(scratchFailure.compileResult()));
                        logger.info("[COMPILATION_REASONING] action=START_PRE_MERGE_SCRATCH_COMPILE_REPAIR method="
                                + scratchFailure.generatedMethodName()
                                + " scratchFile="
                                + scratchFailure.scratchFile().toAbsolutePath().normalize());
                        PreMergeScratchValidator.ScratchRepairOutcome repairOutcome = support.repairPreMergeScratchCompilation(
                                config,
                                classInfo,
                                projectContextCollector,
                                scratchFailure);
                        CompileResult repairCompileResult = repairOutcome == null
                                ? scratchFailure.compileResult()
                                : repairOutcome.compileResult();
                        if (repairOutcome != null && repairOutcome.success() && repairCompileResult != null && repairCompileResult.success()) {
                            logger.info("[COMPILATION_REASONING] action=KEEP_PRE_MERGE_SCRATCH_REPAIR method="
                                    + scratchFailure.generatedMethodName()
                                    + " result=COMPILE_SUCCESS");
                            return repairOutcome;
                        }
                        logger.info("[COMPILATION_REASONING] action=REGENERATE_TEST method="
                                + scratchFailure.generatedMethodName()
                                + " reason=PRE_MERGE_SCRATCH_COMPILE_REPAIR_FAILED errors="
                                + summariseCompileFailure(repairCompileResult));
                        return repairOutcome == null
                                ? PreMergeScratchValidator.ScratchRepairOutcome.failed(scratchFailure.compileResult())
                                : repairOutcome;
                    },
                    scratchFailure -> {
                        logger.warn("[EXECUTION_REASONING] Pre-merge scratch runtime failure for "
                                + scratchFailure.generatedMethodName()
                                + ": "
                                + summariseExecutionFailure(scratchFailure.executeResult()));
                        logger.info("[EXECUTION_REASONING] action=START_PRE_MERGE_SCRATCH_EXECUTION_REPAIR method="
                                + scratchFailure.generatedMethodName()
                                + " scratchFile="
                                + scratchFailure.scratchFile().toAbsolutePath().normalize()
                                + " executionScope="
                                + (scratchFailure.executeWholeSuite() ? "WHOLE_SCRATCH_SUITE" : "GENERATED_METHOD_ONLY"));
                        PreMergeScratchValidator.ScratchExecutionRepairOutcome repairOutcome = support.repairPreMergeScratchExecution(
                                config,
                                classInfo,
                                methodInfo,
                                analysisSummary,
                                projectContextCollector,
                                scratchFailure);
                        CompileResult repairCompileResult = repairOutcome == null
                                ? scratchFailure.compileResult()
                                : repairOutcome.compileResult();
                        ExecuteResult repairExecuteResult = repairOutcome == null
                                ? scratchFailure.executeResult()
                                : repairOutcome.executeResult();
                        if (repairOutcome != null
                                && repairOutcome.success()
                                && (repairCompileResult == null || repairCompileResult.success())
                                && repairExecuteResult != null
                                && repairExecuteResult.success()) {
                            logger.info("[EXECUTION_REASONING] action=KEEP_PRE_MERGE_SCRATCH_EXECUTION_REPAIR method="
                                    + scratchFailure.generatedMethodName()
                                    + " result=EXECUTION_SUCCESS");
                            return repairOutcome;
                        }
                        logger.info("[EXECUTION_REASONING] action=REGENERATE_TEST method="
                                + scratchFailure.generatedMethodName()
                                + " reason=PRE_MERGE_SCRATCH_EXECUTION_REPAIR_FAILED runtime="
                                + summariseExecutionFailure(repairExecuteResult));
                        return repairOutcome == null
                                ? PreMergeScratchValidator.ScratchExecutionRepairOutcome.failed(
                                scratchFailure.compileResult(),
                                scratchFailure.executeResult())
                                : repairOutcome;
                    });
            if (!scratchValidation.success()) {
                logger.warn("Pre-merge sibling-isolation validation rejected method "
                        + snippet.methodName()
                        + " ["
                        + scratchValidation.failureReason()
                        + "]");
                if (scratchValidation.compileResult() != null && !scratchValidation.compileResult().success()) {
                    logger.warn("[COMPILATION_REASONING] Pre-merge scratch compile errors for "
                            + snippet.methodName()
                            + ": "
                            + summariseCompileFailure(scratchValidation.compileResult()));
                    logger.info("[COMPILATION_REASONING] action=REGENERATE_TEST method="
                            + snippet.methodName()
                            + " reason="
                            + scratchValidation.failureReason()
                            + " compileReasoning=FAILED_PRE_MERGE_SCRATCH_REPAIR");
                    logger.trace("ACTION",
                            snippet.methodName(),
                            AgentState.S2_COMPILATION_FAILED.name(),
                            "action=REGENERATE_TEST compileReasoning=FAILED_PRE_MERGE_SCRATCH_REPAIR reason="
                                    + scratchValidation.failureReason());
                }
                if (scratchValidation.executeResult() != null && !scratchValidation.executeResult().success()) {
                    logger.warn("[EXECUTION_REASONING] Pre-merge scratch runtime failure for "
                            + snippet.methodName()
                            + ": "
                            + summariseExecutionFailure(scratchValidation.executeResult()));
                    logger.info("[EXECUTION_REASONING] action=REGENERATE_TEST method="
                            + snippet.methodName()
                            + " reason="
                            + scratchValidation.failureReason()
                            + " executionReasoning=FAILED_PRE_MERGE_SCRATCH_REPAIR");
                    logger.trace("ACTION",
                            snippet.methodName(),
                            AgentState.S2_2_EXECUTION_FAILED.name(),
                            "action=REGENERATE_TEST executionReasoning=FAILED_PRE_MERGE_SCRATCH_REPAIR reason="
                                    + scratchValidation.failureReason());
                }
                logger.trace("RESULT",
                        snippet.methodName(),
                        scratchValidation.executeResult() == null
                                ? AgentState.S2_COMPILATION_FAILED.name()
                                : AgentState.S2_2_EXECUTION_FAILED.name(),
                        "pre-merge sibling-isolation validation failed [" + scratchValidation.failureReason() + "]");
                repairContext = support.buildRepairContext(repairContext,
                        scratchValidation.compileResult(),
                        scratchValidation.executeResult(),
                        null,
                        List.of(),
                        snippet,
                        attempt + 1);
                snippet = support.requestSnippet(config,
                        classInfo,
                        methodInfo,
                        plan,
                        repairContext,
                        analysisSummary,
                        moduleConfig,
                        true);
                if (snippet == null) {
                    logger.warn("LLM did not return a repair snippet after pre-merge sibling-isolation failure for method "
                            + methodInfo.getSignature());
                    logger.trace("RESULT",
                            methodInfo.getSignature(),
                            AgentState.S6_GIVE_UP.name(),
                            "regeneration after pre-merge sibling-isolation failure produced no valid snippet");
                    break;
                }
                attempt++;
                continue;
            }
            if (scratchValidation.validatedSnippet() != null) {
                snippet = scratchValidation.validatedSnippet();
            }
            try {
                if (scratchValidation.replaceTargetClassSource()
                        && scratchValidation.replacementSource() != null
                        && !scratchValidation.replacementSource().isBlank()) {
                    mergeResult = applyValidatedScratchSource(classInfo,
                            snippet,
                            scratchValidation.replacementSource(),
                            scratchValidation.replacementDiagnostic());
                } else {
                    mergeResult = diffEngine.merge(classInfo, snippet);
                }
            } catch (RuntimeException exception) {
                String failure = "Merge failed before compilation: " + exception.getMessage();
                logger.warn(failure);
                logger.trace("RESULT",
                        methodInfo.getSignature(),
                        AgentState.S2_COMPILATION_FAILED.name(),
                        "merge failed before compile [" + exception.getClass().getSimpleName() + "]");
                CompileResult mergeFailure = new CompileResult(false,
                        List.of(failure),
                        "",
                        exception.toString());
                repairContext = support.buildRepairContext(repairContext,
                        mergeFailure,
                        null,
                        null,
                        List.of(),
                        snippet,
                        attempt + 1);
                snippet = support.requestSnippet(config,
                        classInfo,
                        methodInfo,
                        plan,
                        repairContext,
                        analysisSummary,
                        moduleConfig,
                        true);
                if (snippet == null) {
                    logger.warn("LLM did not return a repair snippet after merge failure for method "
                            + methodInfo.getSignature());
                    logger.trace("RESULT",
                            methodInfo.getSignature(),
                            AgentState.S6_GIVE_UP.name(),
                            "regeneration after merge failure produced no valid snippet");
                    break;
                }
                attempt++;
                continue;
            }
            snippet = mergeResult.mergedSnippet();
            if (!mergeResult.changed()) {
                logger.warn("Merge step did not change target class for method "
                        + snippet.methodName()
                        + " [" + mergeResult.diagnostic() + "]");
                logger.trace("RESULT",
                        snippet.methodName(),
                        AgentState.S6_GIVE_UP.name(),
                        "merge produced no changes [" + mergeResult.diagnostic() + "]");
                break;
            }
            preMergeScratchValidator.enrichResolvableImports(config.getProjectPath(), classInfo.getTargetPath());
            ToolActionExecutor actionExecutor = support.createActionExecutor(config, classInfo, snippet);
            CompilationPipelineOrchestrator fixingOrchestrator = support.createFixingOrchestrator(config,
                    classInfo,
                    projectContextCollector,
                    actionExecutor,
                    snippet);

            if (moduleConfig.compileEnabled()) {
                logger.trace("STATE", snippet.methodName(), ReasoningStage.COMPILATION.name(), "running compilation");
                lastCompileResult = compilerInvoker.compile(config.getProjectPath(), classInfo.getTargetPath(), snippet.methodName());
                if (!lastCompileResult.success()) {
                    logger.warn("Compilation failed for method " + snippet.methodName());
                    logger.warn("[COMPILATION_REASONING] Primary compile errors for "
                            + snippet.methodName()
                            + ": "
                            + summariseCompileFailure(lastCompileResult));
                    logger.trace("RESULT",
                            snippet.methodName(),
                            AgentState.S2_COMPILATION_FAILED.name(),
                            "compile failed primary=" + summariseCompileFailure(lastCompileResult));
                    report.addCompileErrors(new CompileErrors(classInfo.getTargetPath(),
                            snippet.methodName(),
                            lastCompileResult.messages(),
                            lastCompileResult.stdout(),
                            lastCompileResult.stderr()));
                    recordedCompileErrorMethods.add(snippet.methodName());
                    try {
                        logger.info("[COMPILATION_REASONING] action=START_COMPILE_FIX_LOOP method="
                                + snippet.methodName());
                        logger.trace("TRANSITION", snippet.methodName(), AgentState.S2_COMPILATION_FAILED.name(), "starting compile-fix loop");
                        lastCompileResult = fixingOrchestrator.runFixingLoop();
                    } catch (FixingFailureException exception) {
                        logger.error("Reasoning loop failed to fix compilation errors: " + exception.getMessage(), exception);
                        lastCompileResult = exception.getLastResult();
                    }
                    if (!lastCompileResult.success()) {
                        logger.info("[COMPILATION_REASONING] action=REGENERATE_TEST method="
                                + snippet.methodName()
                                + " reason=COMPILE_REPAIR_FAILED errors="
                                + summariseCompileFailure(lastCompileResult));
                        logger.trace("TRANSITION", snippet.methodName(), AgentState.S1_TESTS_GENERATED.name(), "compile repair failed, regenerating test");
                        support.handleFailure(classInfo, snippet, mergeResult, moduleConfig, "Compilation repair failed");
                        repairContext = support.buildRepairContext(repairContext,
                                lastCompileResult,
                                null,
                                null,
                                List.of(),
                                snippet,
                                attempt + 1);
                        snippet = support.requestSnippet(config,
                                classInfo,
                                methodInfo,
                                plan,
                                repairContext,
                                analysisSummary,
                                moduleConfig,
                                true);
                        if (snippet == null) {
                            logger.warn("LLM did not return a repair snippet for method " + methodInfo.getSignature());
                            logger.trace("RESULT", methodInfo.getSignature(), AgentState.S6_GIVE_UP.name(), "regeneration after compile failure produced no valid snippet");
                            break;
                        }
                        attempt++;
                        continue;
                    }
                    logger.info("[COMPILATION_REASONING] action=KEEP_REPAIRED_TEST method="
                            + snippet.methodName()
                            + " result=COMPILE_SUCCESS");
                    logger.trace("RESULT", snippet.methodName(), AgentState.S5_COMPILATION_SUCCESS.name(), "compile repaired successfully");
                }
                clearRecordedCompileErrors(report, classInfo.getTargetPath(), recordedCompileErrorMethods);
            } else {
                logger.info("Compilation disabled via configuration; skipping compile step.");
                lastCompileResult = new CompileResult(true, List.of(), "", "");
            }

            if (moduleConfig.executeEnabled()) {
                logger.info("[EXECUTION_REASONING] Stage=RUN_GENERATED_TEST method=" + snippet.methodName());
                logger.trace("STATE", snippet.methodName(), ReasoningStage.EXECUTION.name(), "running generated test");
                lastExecuteResult = executionInvoker.execute(config.getProjectPath(), classInfo.getTargetPath(), snippet.methodName());
                if (!lastExecuteResult.success()) {
                    logger.warn("Execution failed for method " + snippet.methodName());
                    logger.trace("RESULT", snippet.methodName(), AgentState.S2_2_EXECUTION_FAILED.name(), "runtime failed failedTests=" + lastExecuteResult.failedTests());
                    report.addExecuteErrors(new ExecuteErrors(classInfo.getTargetPath(),
                            snippet.methodName(),
                            lastExecuteResult.failedTests(),
                            lastExecuteResult.stdout(),
                            lastExecuteResult.stderr()));
                    recordedExecuteErrorMethods.add(snippet.methodName());
                    logger.info("[EXECUTION_REASONING] Stage=PARSE_RUNTIME_FAILURE method=" + snippet.methodName());
                    lastFailureParseResult = support.parseExecutionLog(lastExecuteResult);
                    lastReportFailures = support.parseExecutionReport(config.getProjectPath(), lastFailureParseResult);
                    logger.info("[EXECUTION_REASONING] Parsed runtime failure for "
                            + snippet.methodName()
                            + ": consoleFailures="
                            + lastFailureParseResult.failures().size()
                            + ", reportFailures="
                            + lastReportFailures.size());
                    ExecutionRepairResult executionRepairResult = support.repairExecutionFailure(config,
                            classInfo,
                            methodInfo,
                            analysisSummary,
                            actionExecutor,
                            fixingOrchestrator,
                            lastCompileResult,
                            lastExecuteResult,
                            snippet.methodName(),
                            lastFailureParseResult,
                            lastReportFailures,
                            snippet);
                    lastCompileResult = executionRepairResult.compileResult();
                    lastExecuteResult = executionRepairResult.executeResult();
                    lastFailureParseResult = executionRepairResult.failureParseResult();
                    lastReportFailures = executionRepairResult.reportFailures();
                    if (executionRepairResult.success()) {
                        report.resolveExecuteErrors(classInfo.getTargetPath(), snippet.methodName());
                        clearRecordedExecuteErrors(report, classInfo.getTargetPath(), recordedExecuteErrorMethods);
                        logger.trace("RESULT", snippet.methodName(), AgentState.S5_COMPILATION_SUCCESS.name(), "execution repaired successfully; continuing to coverage");
                    }
                    if (executionRepairResult.success()) {
                        // keep the repaired snippet and continue into coverage / final success handling
                    } else if (!executionRepairResult.shouldRegenerate()) {
                        logger.warn("Execution reasoning did not fully fix method "
                                + snippet.methodName()
                                + ", but compilation stayed valid. Keeping the current test and skipping full regeneration.");
                        logger.trace("RESULT", snippet.methodName(), AgentState.S2_2_EXECUTION_FAILED.name(), "runtime still failing; keeping compiled test without regeneration");
                        return;
                    } else {
                        logger.warn("Execution reasoning introduced or exposed compilation problems for method "
                                + snippet.methodName()
                                + "; switching to full regeneration.");
                        logger.trace("TRANSITION", snippet.methodName(), AgentState.S1_TESTS_GENERATED.name(), "execution repair requested full regeneration");
                        support.handleFailure(classInfo, snippet, mergeResult, moduleConfig, "Execution repair requested regeneration");
                        repairContext = support.buildRepairContext(repairContext,
                                lastCompileResult,
                                lastExecuteResult,
                                lastFailureParseResult,
                                lastReportFailures,
                                snippet,
                                attempt + 1);
                        snippet = support.requestSnippet(config,
                                classInfo,
                                methodInfo,
                                plan,
                                repairContext,
                                analysisSummary,
                                moduleConfig,
                                true);
                        if (snippet == null) {
                            logger.warn("LLM did not return a repair snippet for method " + methodInfo.getSignature());
                            logger.trace("RESULT", methodInfo.getSignature(), AgentState.S6_GIVE_UP.name(), "regeneration after runtime failure produced no valid snippet");
                            break;
                        }
                        attempt++;
                        continue;
                    }
                }
                if (lastExecuteResult.success()) {
                    report.resolveExecuteErrors(classInfo.getTargetPath(), snippet.methodName());
                    clearRecordedExecuteErrors(report, classInfo.getTargetPath(), recordedExecuteErrorMethods);
                }
            } else {
                logger.info("Execution disabled via configuration; skipping execution step.");
                lastExecuteResult = new ExecuteResult(true, List.of(), "", "");
            }

            if (moduleConfig.coverageEnabled()) {
                GeneratedTestSnippet coverageSnippet = snippet;
                CoverageStageResult coverageStageResult = coverageOrchestrator.run(
                        config.getProjectPath(),
                        classInfo.getTargetPath(),
                        classInfo.getTestClassName(),
                        coverageSnippet.methodName(),
                        moduleConfig.coverageGoals(),
                        () -> support.measureCoverage(config, classInfo, methodInfo, coverageSnippet),
                        actionExecutor,
                        projectContextCollector,
                        fixingOrchestrator,
                        (coverageCompileResult, coverageExecuteResult) -> {
                            ExecutionFailureParseResult coverageFailureParseResult = support.parseExecutionLog(coverageExecuteResult);
                            List<TestReportFailure> coverageReportFailures = support.parseExecutionReport(
                                    config.getProjectPath(),
                                    coverageFailureParseResult);
                            ExecutionRepairResult runtimeRepairResult = support.repairExecutionFailure(
                                    config,
                                    classInfo,
                                    methodInfo,
                                    analysisSummary,
                                    actionExecutor,
                                    fixingOrchestrator,
                                    coverageCompileResult,
                                    coverageExecuteResult,
                                    coverageSnippet.methodName(),
                                    coverageFailureParseResult,
                                    coverageReportFailures,
                                    coverageSnippet);
                            return new RuntimeRegressionResult(runtimeRepairResult.success(),
                                    runtimeRepairResult.compileResult(),
                                    runtimeRepairResult.executeResult(),
                                    runtimeRepairResult.actionExecutionResult());
                        });
                CoverageResult coverageResult = coverageStageResult.coverageResult();
                if (!coverageStageResult.success()) {
                    String failure = coverageResult == null ? "Coverage target not reached" : coverageResult.describeFailure();
                    logger.warn("Coverage failed for method " + snippet.methodName() + ": " + failure);
                    report.addCoverageErrors(new CoverageErrors(classInfo.getTargetPath(),
                            snippet.methodName(),
                            methodInfo.getSignature(),
                            failure,
                            coverageResult == null
                                    ? ""
                                    : coverageResult.xmlReportOptional().map(path -> path.toString()).orElse("")));
                    support.handleFailure(classInfo, snippet, mergeResult, moduleConfig, "Coverage target not reached");
                    return;
                }
            }

            success = true;
            logger.trace("RESULT", snippet.methodName(), "SUCCESS", "generation pipeline completed successfully");
            support.recordSuccessfulTest(classInfo, methodInfo);
            break;
        }

        if (!success) {
            if (mergeResult != null) {
                support.handleFailure(classInfo, snippet, mergeResult, moduleConfig, "Compilation/Execution failure");
            }
            return;
        }

        logger.info("Generation pipeline completed successfully for method " + snippet.methodName());
    }

    private void clearRecordedCompileErrors(ErrorsReport report,
                                            Path testClassFile,
                                            List<String> recordedMethodNames) {
        clearRecordedErrors(testClassFile, recordedMethodNames, methodName -> report.resolveCompileErrors(testClassFile, methodName));
    }

    private void clearRecordedExecuteErrors(ErrorsReport report,
                                            Path testClassFile,
                                            List<String> recordedMethodNames) {
        clearRecordedErrors(testClassFile, recordedMethodNames, methodName -> report.resolveExecuteErrors(testClassFile, methodName));
    }

    private void clearRecordedErrors(Path testClassFile,
                                     List<String> recordedMethodNames,
                                     java.util.function.Consumer<String> resolver) {
        if (testClassFile == null || recordedMethodNames == null || recordedMethodNames.isEmpty()) {
            return;
        }
        for (String methodName : List.copyOf(recordedMethodNames)) {
            resolver.accept(methodName);
        }
        recordedMethodNames.clear();
    }

    private DiffEngine.MergeResult applyValidatedScratchSource(TestClassInfo classInfo,
                                                               GeneratedTestSnippet snippet,
                                                               String replacementSource,
                                                               String diagnostic) {
        Path file = classInfo.getTargetPath();
        try {
            Files.createDirectories(file.getParent());
            String originalSource = Files.exists(file)
                    ? Files.readString(file, StandardCharsets.UTF_8)
                    : "";
            Files.writeString(file, replacementSource, StandardCharsets.UTF_8);
            boolean changed = !Objects.equals(originalSource, replacementSource);
            if (changed) {
                logger.info("Applied pre-validated scratch source for " + snippet.methodName()
                        + " into "
                        + file
                        + " ["
                        + diagnostic
                        + "]");
            } else {
                logger.warn("Pre-validated scratch source did not change target class for "
                        + snippet.methodName()
                        + " ["
                        + diagnostic
                        + "]");
            }
            return new DiffEngine.MergeResult(changed,
                    originalSource,
                    replacementSource,
                    snippet.methodBody(),
                    diffEngine.diff(originalSource, replacementSource),
                    snippet,
                    diagnostic == null ? "PRE_VALIDATED_SCRATCH_SOURCE" : diagnostic);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to apply pre-validated scratch source to " + file, exception);
        }
    }

    private JSONObject toJsonObject(String promptJson, TestMethodInfo methodInfo) {
        if (promptJson == null || promptJson.isBlank()) {
            logger.warn("Prompt JSON was empty for method " + methodInfo.getSignature());
            return new JSONObject();
        }
        try {
            return new JSONObject(promptJson);
        } catch (JSONException exception) {
            logger.warn("Failed to parse prompt JSON for method " + methodInfo.getSignature() + ": " + exception.getMessage());
            return new JSONObject();
        }
    }

    private String summariseCompileFailure(CompileResult compileResult) {
        if (compileResult == null) {
            return "unknown";
        }
        if (compileResult.stderr() != null && !compileResult.stderr().isBlank()) {
            return abbreviate(compileResult.stderr().replaceAll("\\s+", " ").trim());
        }
        if (compileResult.messages() != null && !compileResult.messages().isEmpty()) {
            return abbreviate(String.join(" | ", compileResult.messages()).replaceAll("\\s+", " ").trim());
        }
        if (compileResult.stdout() != null && !compileResult.stdout().isBlank()) {
            return abbreviate(compileResult.stdout().replaceAll("\\s+", " ").trim());
        }
        return "unknown";
    }

    private String summariseExecutionFailure(ExecuteResult executeResult) {
        if (executeResult == null) {
            return "unknown";
        }
        if (executeResult.stderr() != null && !executeResult.stderr().isBlank()) {
            return abbreviate(executeResult.stderr().replaceAll("\\s+", " ").trim());
        }
        if (executeResult.failedTests() != null && !executeResult.failedTests().isEmpty()) {
            return abbreviate(String.join(" | ", executeResult.failedTests()).replaceAll("\\s+", " ").trim());
        }
        if (executeResult.stdout() != null && !executeResult.stdout().isBlank()) {
            return abbreviate(executeResult.stdout().replaceAll("\\s+", " ").trim());
        }
        return "unknown";
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= 1800) {
            return value;
        }
        return value.substring(0, 1800) + "...";
    }

    public interface Support {
        GeneratedTestSnippet requestSnippet(AgentConfig config,
                                            TestClassInfo classInfo,
                                            TestMethodInfo methodInfo,
                                            MockPlan plan,
                                            JSONObject contextJson,
                                            Analyze.AnalysisSummary analysisSummary,
                                            PipelineModuleConfig moduleConfig,
                                            boolean retryAttempt);

        ToolActionExecutor createActionExecutor(AgentConfig config,
                                                TestClassInfo classInfo,
                                                GeneratedTestSnippet snippet);

        CompilationPipelineOrchestrator createFixingOrchestrator(AgentConfig config,
                                                                 TestClassInfo classInfo,
                                                                 ProjectContextCollector projectContextCollector,
                                                                 ToolActionExecutor actionExecutor,
                                                                 GeneratedTestSnippet snippet);

        PreMergeScratchValidator.ScratchRepairOutcome repairPreMergeScratchCompilation(AgentConfig config,
                                                                                       TestClassInfo classInfo,
                                                                                       ProjectContextCollector projectContextCollector,
                                                                                       PreMergeScratchValidator.ScratchCompilationFailure scratchFailure);

        PreMergeScratchValidator.ScratchExecutionRepairOutcome repairPreMergeScratchExecution(AgentConfig config,
                                                                                              TestClassInfo classInfo,
                                                                                              TestMethodInfo methodInfo,
                                                                                              Analyze.AnalysisSummary analysisSummary,
                                                                                              ProjectContextCollector projectContextCollector,
                                                                                              PreMergeScratchValidator.ScratchExecutionFailure scratchFailure);

        JSONObject buildRepairContext(JSONObject baseContext,
                                      CompileResult compileResult,
                                      ExecuteResult executeResult,
                                      ExecutionFailureParseResult failureParseResult,
                                      List<TestReportFailure> reportFailures,
                                      GeneratedTestSnippet snippet,
                                      int attempt);

        ExecutionFailureParseResult parseExecutionLog(ExecuteResult executeResult);

        List<TestReportFailure> parseExecutionReport(Path projectRoot, ExecutionFailureParseResult parseResult);

        ExecutionRepairResult repairExecutionFailure(AgentConfig config,
                                                     TestClassInfo classInfo,
                                                     TestMethodInfo methodInfo,
                                                     Analyze.AnalysisSummary analysisSummary,
                                                     ToolActionExecutor actionExecutor,
                                                     CompilationPipelineOrchestrator fixingOrchestrator,
                                                     CompileResult compileResult,
                                                     ExecuteResult executeResult,
                                                     String generatedMethodName,
                                                     ExecutionFailureParseResult failureParseResult,
                                                     List<TestReportFailure> reportFailures,
                                                     GeneratedTestSnippet snippet);

        CoverageResult measureCoverage(AgentConfig config,
                                       TestClassInfo classInfo,
                                       TestMethodInfo methodInfo,
                                       GeneratedTestSnippet snippet);

        void handleFailure(TestClassInfo classInfo,
                           GeneratedTestSnippet snippet,
                           DiffEngine.MergeResult mergeResult,
                           PipelineModuleConfig moduleConfig,
                           String reason);

        void recordSuccessfulTest(TestClassInfo classInfo, TestMethodInfo methodInfo);
    }
}
