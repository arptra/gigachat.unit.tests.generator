package com.gigachat.unit.tests.generator.pipeline;

import com.gigachat.unit.tests.generator.analyzer.ExternalCollaboratorDetector;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.PipelineModuleConfig;
import com.gigachat.unit.tests.generator.config.ParallelMode;
import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.dto.CompileErrors;
import com.gigachat.unit.tests.generator.dto.ErrorsReport;
import com.gigachat.unit.tests.generator.dto.ExecuteErrors;
import com.gigachat.unit.tests.generator.dto.FailedMethodSnapshot;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.llm.LlmClient;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.DiffEngine;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.pipeline.helpers.PromptBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.SkeletonPromptBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.SnapshotStorage;
import com.gigachat.unit.tests.generator.pipeline.helpers.TestClassWriter;
import com.gigachat.unit.tests.generator.pipeline.InvalidLLMResponseException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes the first seven stages of the generation pipeline for each discovered method.
 */
public class InitialGenerationStep {
    private final PipelineLogger logger;
    private final TestClassWriter testClassWriter;
    private final SkeletonPromptBuilder skeletonPromptBuilder;
    private final Analyze analyze;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final DiffEngine diffEngine;
    private final CompilerInvoker compilerInvoker;
    private final ExecutionInvoker executionInvoker;
    private final SnapshotStorage snapshotStorage;
    private final ExternalCollaboratorDetector collaboratorDetector;

    public InitialGenerationStep(PipelineLogger logger,
                                 TestClassWriter testClassWriter,
                                 SkeletonPromptBuilder skeletonPromptBuilder,
                                 Analyze analyze,
                                 PromptBuilder promptBuilder,
                                 LlmClient llmClient,
                                 DiffEngine diffEngine,
                                 CompilerInvoker compilerInvoker,
                                 ExecutionInvoker executionInvoker,
                                 SnapshotStorage snapshotStorage) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.testClassWriter = Objects.requireNonNull(testClassWriter, "testClassWriter");
        this.skeletonPromptBuilder = Objects.requireNonNull(skeletonPromptBuilder, "skeletonPromptBuilder");
        this.analyze = Objects.requireNonNull(analyze, "analyze");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.diffEngine = Objects.requireNonNull(diffEngine, "diffEngine");
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.executionInvoker = Objects.requireNonNull(executionInvoker, "executionInvoker");
        this.snapshotStorage = Objects.requireNonNull(snapshotStorage, "snapshotStorage");
        this.collaboratorDetector = new ExternalCollaboratorDetector();
    }

    public ErrorsReport run(AgentConfig config, List<TestClassInfo> classes) {
        ErrorsReport report = new ErrorsReport();
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
        List<TestMethodInfo> methods = classInfo.getMethods();
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
        logger.info("Processing method " + methodInfo.getSignature() + " for class " + classInfo.getClassName());
        String skeletonPrompt = skeletonPromptBuilder.build(classInfo, methodInfo);
        Analyze.AnalysisSummary analysisSummary = analyze.analyze(config, classInfo, methodInfo);
        MockPlan plan = analysisSummary.mockPlan();
        String promptJson = promptBuilder.build(config, classInfo, methodInfo, skeletonPrompt, analysisSummary);
        JSONObject contextJson = toJsonObject(promptJson, methodInfo);
        String llmPrompt = promptBuilder.buildPromptForLLM(contextJson, config.getPromptConfig());
        logger.info("Prepared LLM prompt for method " + methodInfo.getSignature());
        GeneratedTestSnippet snippet;
        try {
            snippet = llmClient.generateTestSnippet(llmPrompt, classInfo, methodInfo, plan);
            validateGeneratedSnippet(classInfo, snippet, methodInfo, analysisSummary, moduleConfig);
        } catch (InvalidLLMResponseException exception) {
            logger.info("Skipping method " + methodInfo.getSignature() + " due to invalid LLM response: " + exception.getMessage());
            return;
        }
        DiffEngine.MergeResult mergeResult = diffEngine.merge(classInfo, snippet);
        if (!mergeResult.changed()) {
            logger.warn("Merge step did not change target class for method " + snippet.methodName());
            return;
        }
        if (moduleConfig.compileEnabled()) {
            CompileResult compileResult = compilerInvoker.compile(config.getProjectPath(), classInfo.getTargetPath(), snippet.methodName());
            if (!compileResult.success()) {
                logger.warn("Compilation failed for method " + snippet.methodName());
                report.addCompileErrors(new CompileErrors(classInfo.getTargetPath(),
                        snippet.methodName(),
                        compileResult.messages(),
                        compileResult.stdout(),
                        compileResult.stderr()));
                handleFailure(classInfo, snippet, mergeResult, moduleConfig, "Compilation failure");
                return;
            }
        } else {
            logger.info("Compilation disabled via configuration; skipping compile step.");
        }
        if (moduleConfig.executeEnabled()) {
            ExecuteResult executeResult = executionInvoker.execute(config.getProjectPath(), classInfo.getTargetPath(), snippet.methodName());
            if (!executeResult.success()) {
                logger.warn("Execution failed for method " + snippet.methodName());
                report.addExecuteErrors(new ExecuteErrors(classInfo.getTargetPath(),
                        snippet.methodName(),
                        executeResult.failedTests(),
                        executeResult.stdout(),
                        executeResult.stderr()));
                handleFailure(classInfo, snippet, mergeResult, moduleConfig, "Execution failure");
                return;
            }
        } else {
            logger.info("Execution disabled via configuration; skipping execution step.");
        }
        logger.info("Generation pipeline completed successfully for method " + snippet.methodName());
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

    private void handleFailure(TestClassInfo classInfo,
                               GeneratedTestSnippet snippet,
                               DiffEngine.MergeResult mergeResult,
                               PipelineModuleConfig moduleConfig,
                               String reason) {
        logger.warn("Preparing repair step for method " + snippet.methodName() + " due to " + reason);
        revertMerge(classInfo, mergeResult);
        if (moduleConfig.snapshotsEnabled()) {
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

    private void validateGeneratedSnippet(TestClassInfo classInfo,
                                          GeneratedTestSnippet snippet,
                                          TestMethodInfo methodInfo,
                                          Analyze.AnalysisSummary analysisSummary,
                                          PipelineModuleConfig moduleConfig) {
        if (snippet == null || methodInfo == null) {
            return;
        }
        String fullSource = snippet.fullClassSource();
        if (fullSource == null || fullSource.isBlank()) {
            return;
        }
        String signature = methodInfo.getSignature();
        if (signature == null || signature.isBlank()) {
            return;
        }
        String normalisedSignature = signature.replaceAll("\\s+", " ").trim();
        String normalisedSource = fullSource.replaceAll("\\s+", " ").trim();
        if (normalisedSource.contains(normalisedSignature + " {")) {
            String methodName = analysisSummary.methodAnalysis().method().name();
            logger.warn("⚠️  LLM reimplemented method " + methodName + " inside test class. Marking generation as invalid.");
            throw new InvalidLLMResponseException("LLM returned reimplementation of tested method instead of test.");
        }
        if (moduleConfig != null && moduleConfig.validateMockUsage()) {
            ensureMockUsageIsValid(fullSource, analysisSummary, classInfo);
        }
    }

    private void ensureMockUsageIsValid(String fullSource,
                                        Analyze.AnalysisSummary analysisSummary,
                                        TestClassInfo classInfo) {
        MockPlan plan = analysisSummary.mockPlan();
        if (plan != null && plan.strategy() == MockStrategy.NONE && containsMockito(fullSource)) {
            String collaboratorField = collaboratorDetector.findFirstExternalCollaborator(
                            classInfo != null ? classInfo.getClassMetadata() : null)
                    .map(field -> field.getTypeName() + " " + field.getName())
                    .orElse(null);
            if (collaboratorField != null) {
                logger.warn("⚠️  LLM introduced Mockito usage despite NONE strategy. External collaborator field detected: "
                        + collaboratorField + ". Marking generation as invalid.");
            } else {
                logger.warn("⚠️  LLM introduced Mockito usage despite NONE strategy. Marking generation as invalid.");
            }
            throw new InvalidLLMResponseException("LLM returned Mockito usage when mocks should be disabled.");
        }
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        if (targetContext == null) {
            return;
        }
        String instanceName = targetContext.instanceName();
        if (instanceName == null || instanceName.isBlank()) {
            return;
        }
        Pattern privateFieldPattern = Pattern.compile("\\b" + Pattern.quote(instanceName) + "\\.\\s*[A-Za-z_][A-Za-z0-9_]*\\b(?!\\s*\\()", Pattern.MULTILINE);
        Matcher matcher = privateFieldPattern.matcher(fullSource);
        if (matcher.find()) {
            logger.warn("⚠️  LLM accessed private/internal field '" + matcher.group() + "'. Marking generation as invalid.");
            throw new InvalidLLMResponseException("LLM accessed private field of tested class.");
        }
    }

    private boolean containsMockito(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        if (source.contains("Mockito")) {
            return true;
        }
        return source.contains("org.mockito")
                || source.contains("import static org.mockito")
                || source.contains("@Mock");
    }
}
