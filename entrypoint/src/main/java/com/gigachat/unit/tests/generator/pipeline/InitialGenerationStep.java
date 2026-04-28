package com.gigachat.unit.tests.generator.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.PipelineModuleConfig;
import com.gigachat.unit.tests.generator.config.ParallelMode;
import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.coverage.CoverageInvoker;
import com.gigachat.unit.tests.generator.coverage.CoverageResult;
import com.gigachat.unit.tests.generator.coverage.JaCoCoCoverageInvoker;
import com.gigachat.unit.tests.generator.dto.CompileErrors;
import com.gigachat.unit.tests.generator.dto.ErrorsReport;
import com.gigachat.unit.tests.generator.dto.FailedMethodSnapshot;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.llm.LlmClient;
import com.gigachat.unit.tests.generator.pipeline.orchestrator.GenerationMethodOrchestrator;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.DiffEngine;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.pipeline.helpers.PromptBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.SkeletonPromptBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.SnapshotStorage;
import com.gigachat.unit.tests.generator.pipeline.helpers.generation.GenerationSnippetRequester;
import com.gigachat.unit.tests.generator.pipeline.helpers.ExistingTestDetector;
import com.gigachat.unit.tests.generator.pipeline.helpers.TestClassWriter;
import com.gigachat.unit.tests.generator.pipeline.helpers.TestGenerationRegistry;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.AutoCorrectionStage;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.ExecutionFailureSupport;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.GenerationRepairContextBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.GenerationValidationRetryBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.validation.GeneratedSnippetValidator;
import com.gigachat.unit.tests.generator.cleaner.parser.ExecutionFailureLogParser;
import com.gigachat.unit.tests.generator.cleaner.parser.ExecutionFailureParseResult;
import com.gigachat.unit.tests.generator.report.parser.ExecutionReportParser;
import com.gigachat.unit.tests.generator.report.parser.TestReportFailure;
import com.gigachat.unit.tests.generator.reasoning.orchestrator.CompilationPipelineOrchestrator;
import com.gigachat.unit.tests.generator.reasoning.orchestrator.CoveragePipelineOrchestrator;
import com.gigachat.unit.tests.generator.reasoning.orchestrator.ExecutionPipelineOrchestrator.ExecutionRepairResult;
import com.gigachat.unit.tests.generator.reasoning.service.CompilationReasoningService;
import com.gigachat.unit.tests.generator.reasoning.service.ExecutionFailureContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.ProjectContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.SourceFileEditor;
import com.gigachat.unit.tests.generator.reasoning.service.ToolActionExecutor;
import com.gigachat.unit.tests.generator.resources.GenerationPatternCatalog;
import com.gigachat.unit.tests.generator.resources.StateModelCatalog;
import com.gigachat.unit.tests.generator.compile.classification.classify.CompilationErrorClassifier;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.json.JSONObject;

/**
 * Executes the first seven stages of the generation pipeline for each discovered method.
 */
public class InitialGenerationStep {
    private final PipelineLogger logger;
    private final TestClassWriter testClassWriter;
    private final SkeletonPromptBuilder skeletonPromptBuilder;
    private final Analyze analyze;
    private final PromptBuilder promptBuilder;
    private final DiffEngine diffEngine;
    private final CompilerInvoker compilerInvoker;
    private final ExecutionInvoker executionInvoker;
    private final SnapshotStorage snapshotStorage;
    private final ExecutionFailureLogParser executionFailureLogParser;
    private final ExecutionReportParser executionReportParser;
    private final ExecutionFailureContextCollector executionFailureContextCollector;
    private final CompilationReasoningService reasoningService;
    private final CoverageInvoker coverageInvoker;
    private final GenerationRepairContextBuilder repairContextBuilder;
    private final ExecutionFailureSupport executionFailureSupport;
    private final GenerationSnippetRequester generationSnippetRequester;
    private ExistingTestDetector existingTestDetector;
    private TestGenerationRegistry generationRegistry;

    public InitialGenerationStep(PipelineLogger logger,
                                 TestClassWriter testClassWriter,
                                 SkeletonPromptBuilder skeletonPromptBuilder,
                                 Analyze analyze,
                                 PromptBuilder promptBuilder,
                                 LlmClient llmClient,
                                 DiffEngine diffEngine,
                                 CompilerInvoker compilerInvoker,
                                 ExecutionInvoker executionInvoker,
                                 SnapshotStorage snapshotStorage,
                                 MethodSignatureRegistry signatureRegistry,
                                 CompilationReasoningService reasoningService) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.testClassWriter = Objects.requireNonNull(testClassWriter, "testClassWriter");
        this.skeletonPromptBuilder = Objects.requireNonNull(skeletonPromptBuilder, "skeletonPromptBuilder");
        this.analyze = Objects.requireNonNull(analyze, "analyze");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        Objects.requireNonNull(llmClient, "llmClient");
        this.diffEngine = Objects.requireNonNull(diffEngine, "diffEngine");
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.executionInvoker = Objects.requireNonNull(executionInvoker, "executionInvoker");
        this.snapshotStorage = Objects.requireNonNull(snapshotStorage, "snapshotStorage");
        Objects.requireNonNull(signatureRegistry, "signatureRegistry");
        this.executionFailureLogParser = new ExecutionFailureLogParser();
        this.executionReportParser = new ExecutionReportParser();
        this.executionFailureContextCollector = new ExecutionFailureContextCollector();
        this.reasoningService = Objects.requireNonNull(reasoningService, "reasoningService");
        this.coverageInvoker = new JaCoCoCoverageInvoker(logger);
        AutoCorrectionStage autoCorrectionStage = new AutoCorrectionStage();
        GenerationPatternCatalog generationPatternCatalog = new GenerationPatternCatalog();
        StateModelCatalog stateModelCatalog = new StateModelCatalog();
        this.repairContextBuilder = new GenerationRepairContextBuilder(stateModelCatalog);
        GeneratedSnippetValidator generatedSnippetValidator = new GeneratedSnippetValidator(logger, analyze, signatureRegistry);
        GenerationValidationRetryBuilder generationValidationRetryBuilder = new GenerationValidationRetryBuilder(
                logger,
                generationPatternCatalog,
                stateModelCatalog,
                generatedSnippetValidator::buildTargetConstructionRetryConstraints);
        this.executionFailureSupport = new ExecutionFailureSupport(
                logger,
                compilerInvoker,
                executionInvoker,
                executionFailureLogParser,
                executionReportParser,
                executionFailureContextCollector,
                reasoningService);
        this.generationSnippetRequester = new GenerationSnippetRequester(
                logger,
                promptBuilder,
                llmClient,
                autoCorrectionStage,
                generationValidationRetryBuilder,
                generatedSnippetValidator);
    }

    public ErrorsReport run(AgentConfig config, List<TestClassInfo> classes) {
        ErrorsReport report = new ErrorsReport();
        this.generationRegistry = new TestGenerationRegistry(config.getProjectPath());
        this.existingTestDetector = new ExistingTestDetector(generationRegistry);
        if (classes == null || classes.isEmpty()) {
            logger.warn("No classes to process in initial generation step");
            return report;
        }
        PipelineModuleConfig moduleConfig = config.getPipelineModuleConfig();
        ParallelMode parallelMode = moduleConfig.parallelMode();
        logger.info("Starting initial pipeline generation with parallel mode " + parallelMode);
        if (parallelMode.paralleliseClasses()) {
            classes.parallelStream().forEach(classInfo -> processClass(config, classInfo, moduleConfig, report));
        } else {
            for (TestClassInfo classInfo : classes) {
                processClass(config, classInfo, moduleConfig, report);
            }
        }
        return report;
    }

    private void processClass(AgentConfig config,
                              TestClassInfo classInfo,
                              PipelineModuleConfig moduleConfig,
                              ErrorsReport report) {
        testClassWriter.ensureTestClassExists(classInfo);
        if (!classInfo.hasMethods()) {
            logger.warn("Class " + classInfo.getClassName() + " has no eligible methods for generation");
            return;
        }
        List<TestMethodInfo> methods = classInfo.getMethods().stream()
                .filter(method -> !existingTestDetector.isTestMethodPresent(classInfo, method))
                .toList();
        if (methods.isEmpty()) {
            logger.info("All requested test methods already exist for class " + classInfo.getTestClassName());
            return;
        }
        if (moduleConfig.parallelMode().paralleliseMethods()) {
            methods.parallelStream().forEach(method -> processMethod(config, classInfo, method, moduleConfig, report));
        } else {
            for (TestMethodInfo methodInfo : methods) {
                processMethod(config, classInfo, methodInfo, moduleConfig, report);
            }
        }
    }

    private void processMethod(AgentConfig config,
                               TestClassInfo classInfo,
                               TestMethodInfo methodInfo,
                               PipelineModuleConfig moduleConfig,
                               ErrorsReport report) {
        try {
            createMethodOrchestrator().processMethod(config, classInfo, methodInfo, moduleConfig, report);
        } catch (RuntimeException exception) {
            logger.error("Generation pipeline failed unexpectedly for method "
                    + methodInfo.getSignature()
                    + "; continuing with remaining methods.", exception);
            report.addCompileErrors(new CompileErrors(classInfo.getTargetPath(),
                    methodInfo.getSignature(),
                    List.of("Unexpected generation pipeline failure: " + exception.getMessage()),
                    "",
                    exception.toString()));
        }
    }

    private GenerationMethodOrchestrator createMethodOrchestrator() {
        return new GenerationMethodOrchestrator(
                logger,
                skeletonPromptBuilder,
                analyze,
                promptBuilder,
                diffEngine,
                compilerInvoker,
                executionInvoker,
                new CoveragePipelineOrchestrator(logger, compilerInvoker, executionInvoker, reasoningService),
                new GenerationMethodOrchestrator.Support() {
                    @Override
                    public GeneratedTestSnippet requestSnippet(AgentConfig config,
                                                               TestClassInfo classInfo,
                                                               TestMethodInfo methodInfo,
                                                               MockPlan plan,
                                                               JSONObject contextJson,
                                                               Analyze.AnalysisSummary analysisSummary,
                                                               PipelineModuleConfig moduleConfig,
                                                               boolean retryAttempt) {
                        return generationSnippetRequester.requestSnippet(config,
                                classInfo,
                                methodInfo,
                                plan,
                                contextJson,
                                analysisSummary,
                                moduleConfig,
                                retryAttempt);
                    }

                    @Override
                    public ToolActionExecutor createActionExecutor(AgentConfig config,
                                                                   TestClassInfo classInfo,
                                                                   GeneratedTestSnippet snippet) {
                        SourceFileEditor sourceFileEditor = new SourceFileEditor();
                        return InitialGenerationStep.this.createActionExecutor(config,
                                classInfo,
                                sourceFileEditor,
                                snippet);
                    }

                    @Override
                    public CompilationPipelineOrchestrator createFixingOrchestrator(AgentConfig config,
                                                                                    TestClassInfo classInfo,
                                                                                    ProjectContextCollector projectContextCollector,
                                                                                    ToolActionExecutor actionExecutor,
                                                                                    GeneratedTestSnippet snippet) {
                        return InitialGenerationStep.this.createFixingOrchestrator(config,
                                classInfo,
                                projectContextCollector,
                                actionExecutor,
                                snippet);
                    }

                    @Override
                    public JSONObject buildRepairContext(JSONObject baseContext,
                                                         CompileResult compileResult,
                                                         ExecuteResult executeResult,
                                                         ExecutionFailureParseResult failureParseResult,
                                                         List<TestReportFailure> reportFailures,
                                                         GeneratedTestSnippet snippet,
                                                         int attempt) {
                        return repairContextBuilder.build(baseContext,
                                compileResult,
                                executeResult,
                                failureParseResult,
                                reportFailures,
                                snippet,
                                attempt);
                    }

                    @Override
                    public ExecutionFailureParseResult parseExecutionLog(ExecuteResult executeResult) {
                        return executionFailureSupport.parseExecutionLog(executeResult);
                    }

                    @Override
                    public List<TestReportFailure> parseExecutionReport(Path projectRoot, ExecutionFailureParseResult parseResult) {
                        return executionFailureSupport.parseExecutionReport(projectRoot, parseResult);
                    }

                    @Override
                    public ExecutionRepairResult repairExecutionFailure(AgentConfig config,
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
                                                                        GeneratedTestSnippet snippet) {
                        return executionFailureSupport.repairExecutionFailure(config,
                                classInfo,
                                methodInfo,
                                analysisSummary,
                                actionExecutor,
                                fixingOrchestrator,
                                compileResult,
                                executeResult,
                                generatedMethodName,
                                failureParseResult,
                                reportFailures,
                                snippet);
                    }

                    @Override
                    public CoverageResult measureCoverage(AgentConfig config,
                                                          TestClassInfo classInfo,
                                                          TestMethodInfo methodInfo,
                                                          GeneratedTestSnippet snippet) {
                        return coverageInvoker.measure(config.getProjectPath(),
                                classInfo.getTargetPath(),
                                "",
                                classInfo.getClassName(),
                                methodInfo.getSignature());
                    }

                    @Override
                    public void handleFailure(TestClassInfo classInfo,
                                              GeneratedTestSnippet snippet,
                                              DiffEngine.MergeResult mergeResult,
                                              PipelineModuleConfig moduleConfig,
                                              String reason) {
                        InitialGenerationStep.this.handleFailure(classInfo, snippet, mergeResult, moduleConfig, reason);
                    }

                    @Override
                    public void recordSuccessfulTest(TestClassInfo classInfo, TestMethodInfo methodInfo) {
                        existingTestDetector.recordSuccessfulTest(classInfo, methodInfo);
                    }
                });
    }

    private ToolActionExecutor createActionExecutor(AgentConfig config,
                                                    TestClassInfo classInfo,
                                                    SourceFileEditor sourceFileEditor,
                                                    GeneratedTestSnippet snippet) {
        return new ToolActionExecutor(sourceFileEditor,
                compilerInvoker,
                executionInvoker,
                config.getProjectPath(),
                classInfo.getTargetPath(),
                classInfo.getTestClassName(),
                snippet.methodName());
    }

    private CompilationPipelineOrchestrator createFixingOrchestrator(AgentConfig config,
                                                                     TestClassInfo classInfo,
                                                                     ProjectContextCollector projectContextCollector,
                                                                     ToolActionExecutor actionExecutor,
                                                                     GeneratedTestSnippet snippet) {
        return new CompilationPipelineOrchestrator(compilerInvoker,
                reasoningService,
                projectContextCollector,
                actionExecutor,
                new CompilationErrorClassifier(),
                logger,
                config.getProjectPath(),
                classInfo.getTargetPath(),
                classInfo.getTestClassName(),
                snippet.methodName());
    }

    private void handleFailure(TestClassInfo classInfo,
                               GeneratedTestSnippet snippet,
                               DiffEngine.MergeResult mergeResult,
                               PipelineModuleConfig moduleConfig,
                               String reason) {
        String methodName = snippet == null ? "<unknown>" : snippet.methodName();
        logger.warn("Preparing repair step for method " + methodName + " due to " + reason);
        if (mergeResult != null) {
            revertMerge(classInfo, mergeResult);
        }
        if (moduleConfig.snapshotsEnabled() && snippet != null) {
            FailedMethodSnapshot snapshot = new FailedMethodSnapshot(classInfo.getTargetPath(),
                    snippet.methodName(),
                    snippet.methodBody(),
                    snippet.imports(),
                    reason,
                    Instant.now());
            snapshotStorage.save(snapshot);
        }
    }

    private void revertMerge(TestClassInfo classInfo, DiffEngine.MergeResult mergeResult) {
        Path file = classInfo.getTargetPath();
        testClassWriter.writeSource(file, mergeResult.originalSource());
        logger.info("Reverted generated method from " + file);
    }
}
