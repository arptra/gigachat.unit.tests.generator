package com.gigachat.unit.tests.generator.pipeline;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ExternalCollaboratorDetector;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
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
import com.gigachat.unit.tests.generator.dto.ClassMetadata;
import com.gigachat.unit.tests.generator.dto.FieldMetadata;
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
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.AutoCorrectionStage;
import com.gigachat.unit.tests.generator.cleaner.parser.ExecutionFailureLogParser;
import com.gigachat.unit.tests.generator.cleaner.parser.ExecutionFailureParseResult;
import com.gigachat.unit.tests.generator.report.parser.ExecutionReportParser;
import com.gigachat.unit.tests.generator.report.parser.TestReportFailure;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.reasoning.model.ProjectContextSummary;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.orchestrator.CompilationPipelineOrchestrator;
import com.gigachat.unit.tests.generator.reasoning.workflow.ReasoningWorkflow;
import com.gigachat.unit.tests.generator.reasoning.workflow.exception.FixingFailureException;
import com.gigachat.unit.tests.generator.reasoning.service.BuildFileEditor;
import com.gigachat.unit.tests.generator.reasoning.service.ProjectContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.SourceFileEditor;
import com.gigachat.unit.tests.generator.reasoning.service.ToolActionExecutor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;

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
    private final MethodSignatureRegistry signatureRegistry;
    private final AutoCorrectionStage autoCorrectionStage;
    private final ExecutionFailureLogParser executionFailureLogParser;
    private final ExecutionReportParser executionReportParser;
    private final ReasoningWorkflow reasoningWorkflow;

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
                                 ReasoningWorkflow reasoningWorkflow) {
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
        this.signatureRegistry = Objects.requireNonNull(signatureRegistry, "signatureRegistry");
        this.autoCorrectionStage = new AutoCorrectionStage();
        this.executionFailureLogParser = new ExecutionFailureLogParser();
        this.executionReportParser = new ExecutionReportParser();
        this.reasoningWorkflow = Objects.requireNonNull(reasoningWorkflow, "reasoningWorkflow");
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
        GeneratedTestSnippet snippet = requestSnippetSimple(config,
                classInfo,
                methodInfo,
                plan,
                contextJson,
                analysisSummary,
                moduleConfig,
                false);
        if (snippet == null) {
            logger.warn("LLM did not return a snippet for method " + methodInfo.getSignature());
            return;
        }

        ProjectContextCollector projectContextCollector = new ProjectContextCollector(config.getProjectPath(), logger);
        SourceFileEditor sourceFileEditor = new SourceFileEditor(logger);
        BuildFileEditor buildFileEditor = new BuildFileEditor(config.getProjectPath(), logger);
        boolean success = false;
        int attempt = 0;
        int maxAttempts = 5;
        JSONObject repairContext = contextJson;
        DiffEngine.MergeResult mergeResult = null;
        CompileResult lastCompileResult = null;
        ExecuteResult lastExecuteResult = null;
        ExecutionFailureParseResult lastFailureParseResult = null;
        List<TestReportFailure> lastReportFailures = List.of();

        while (attempt < maxAttempts) {
            ToolActionExecutor actionExecutor = createActionExecutor(config,
                    classInfo,
                    buildFileEditor,
                    sourceFileEditor,
                    snippet);
            CompilationPipelineOrchestrator fixingOrchestrator = createFixingOrchestrator(config,
                    classInfo,
                    projectContextCollector,
                    actionExecutor,
                    snippet);
            mergeResult = diffEngine.merge(classInfo, snippet);
            if (!mergeResult.changed()) {
                logger.warn("Merge step did not change target class for method " + snippet.methodName());
                break;
            }
            if (moduleConfig.compileEnabled()) {
                lastCompileResult = compilerInvoker.compile(config.getProjectPath(), classInfo.getTargetPath(), snippet.methodName());
                if (!lastCompileResult.success()) {
                    logger.warn("Compilation failed for method " + snippet.methodName());
                    report.addCompileErrors(new CompileErrors(classInfo.getTargetPath(),
                            snippet.methodName(),
                            lastCompileResult.messages(),
                            lastCompileResult.stdout(),
                            lastCompileResult.stderr()));
                    try {
                        lastCompileResult = fixingOrchestrator.runFixingLoop();
                    } catch (FixingFailureException exception) {
                        logger.error("Reasoning loop failed to fix compilation errors: " + exception.getMessage(), exception);
                        lastCompileResult = exception.getLastResult();
                    }
                    if (!lastCompileResult.success()) {
                        revertMerge(classInfo, mergeResult);
                        repairContext = buildRepairContext(repairContext,
                                lastCompileResult,
                                null,
                                null,
                                List.of(),
                                snippet,
                                attempt + 1);
                        snippet = requestSnippetSimple(config,
                                classInfo,
                                methodInfo,
                                plan,
                                repairContext,
                                analysisSummary,
                                moduleConfig,
                                true);
                        if (snippet == null) {
                            logger.warn("LLM did not return a repair snippet for method " + methodInfo.getSignature());
                            break;
                        }
                        attempt++;
                        continue;
                    }
                }
            } else {
                logger.info("Compilation disabled via configuration; skipping compile step.");
                lastCompileResult = new CompileResult(true, List.of(), "", "");
            }

            if (moduleConfig.executeEnabled()) {
                lastExecuteResult = executionInvoker.execute(config.getProjectPath(), classInfo.getTargetPath(), snippet.methodName());
                if (!lastExecuteResult.success()) {
                    logger.warn("Execution failed for method " + snippet.methodName());
                    report.addExecuteErrors(new ExecuteErrors(classInfo.getTargetPath(),
                            snippet.methodName(),
                            lastExecuteResult.failedTests(),
                            lastExecuteResult.stdout(),
                            lastExecuteResult.stderr()));
                    lastFailureParseResult = parseExecutionLog(lastExecuteResult);
                    lastReportFailures = parseExecutionReport(config.getProjectPath(), lastFailureParseResult);
                    ReasoningResponse reasoningResponse = triggerReasoningWorkflow(config,
                            classInfo,
                            methodInfo,
                            lastCompileResult,
                            lastExecuteResult);
                    logReasoningResponse("execute", reasoningResponse);
                    if (reasoningResponse != null) {
                        actionExecutor.execute(reasoningResponse.getAction());
                        try {
                            lastCompileResult = fixingOrchestrator.runFixingLoop();
                        } catch (FixingFailureException exception) {
                            logger.error("Reasoning loop failed during execution fixes: " + exception.getMessage(), exception);
                            lastCompileResult = exception.getLastResult();
                        }
                        if (lastCompileResult != null && lastCompileResult.success()) {
                            lastExecuteResult = executionInvoker.execute(config.getProjectPath(), classInfo.getTargetPath(), snippet.methodName());
                            if (lastExecuteResult.success()) {
                                success = true;
                                break;
                            }
                        }
                    }
                    revertMerge(classInfo, mergeResult);
                    repairContext = buildRepairContext(repairContext,
                            lastCompileResult,
                            lastExecuteResult,
                            lastFailureParseResult,
                            lastReportFailures,
                            snippet,
                            attempt + 1);
                    snippet = requestSnippetSimple(config,
                            classInfo,
                            methodInfo,
                            plan,
                            repairContext,
                            analysisSummary,
                            moduleConfig,
                            true);
                    if (snippet == null) {
                        logger.warn("LLM did not return a repair snippet for method " + methodInfo.getSignature());
                        break;
                    }
                    attempt++;
                    continue;
                }
            } else {
                logger.info("Execution disabled via configuration; skipping execution step.");
                lastExecuteResult = new ExecuteResult(true, List.of(), "", "");
            }
            success = true;
            break;
        }

        if (!success) {
            if (mergeResult != null) {
                handleFailure(classInfo, snippet, mergeResult, moduleConfig, "Compilation/Execution failure");
            }
            return;
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

    private GeneratedTestSnippet generateSnippetWithRetry(AgentConfig config,
                                                          TestClassInfo classInfo,
                                                          TestMethodInfo methodInfo,
                                                          String skeletonPrompt,
                                                          MockPlan plan,
                                                          JSONObject contextJson,
                                                          Analyze.AnalysisSummary analysisSummary,
                                                          PipelineModuleConfig moduleConfig) {
        try {
            return requestSnippet(config,
                    classInfo,
                    methodInfo,
                    plan,
                    contextJson,
                    analysisSummary,
                    moduleConfig,
                    false);
        } catch (InvalidLLMResponseException first) {
            if (!shouldRetry(first)) {
                throw first;
            }
            logger.warn("Retrying generation for method " + methodInfo.getSignature()
                    + " due to invalid response (" + first.getMessage() + ")");
            Analyze.AnalysisSummary refreshedSummary = analyze.analyze(config, classInfo, methodInfo);
            MockPlan refreshedPlan = refreshedSummary.mockPlan();
            String refreshedPromptJson = promptBuilder.build(config,
                    classInfo,
                    methodInfo,
                    skeletonPrompt,
                    refreshedSummary);
            JSONObject refreshedContextJson = toJsonObject(refreshedPromptJson, methodInfo);
            if (first.getMessage() != null && first.getMessage().contains("E104")) {
                logger.warn("Triggering constructor metadata refresh prior to retry.");
            }
            appendRetryHint(refreshedContextJson);
            return requestSnippet(config,
                    classInfo,
                    methodInfo,
                    refreshedPlan,
                    refreshedContextJson,
                    refreshedSummary,
                    moduleConfig,
                    true);
        }
    }

    private GeneratedTestSnippet requestSnippet(AgentConfig config,
                                                TestClassInfo classInfo,
                                                TestMethodInfo methodInfo,
                                                MockPlan plan,
                                                JSONObject contextJson,
                                                Analyze.AnalysisSummary analysisSummary,
                                                PipelineModuleConfig moduleConfig,
                                                boolean retryAttempt) {
        String llmPrompt = promptBuilder.buildPromptForLLM(contextJson, config.getPromptConfig());
        logger.info("-> DEBUG LOG Request to gigachat \n" + llmPrompt);
        logger.info("Prepared LLM prompt for method " + methodInfo.getSignature()
                + (retryAttempt ? " [retry]" : ""));
        GeneratedTestSnippet snippet = llmClient.generateTestSnippet(llmPrompt, classInfo, methodInfo, plan);
        logger.info("Response from gigachat " + snippet);
                snippet = autoCorrectionStage.apply(snippet);
        validateGeneratedSnippet(config, classInfo, snippet, methodInfo, analysisSummary, moduleConfig);
        return snippet;
    }

    private GeneratedTestSnippet requestSnippetSimple(AgentConfig config,
                                                      TestClassInfo classInfo,
                                                      TestMethodInfo methodInfo,
                                                      MockPlan plan,
                                                      JSONObject contextJson,
                                                      Analyze.AnalysisSummary analysisSummary,
                                                      PipelineModuleConfig moduleConfig,
                                                      boolean retryAttempt) {
        String llmPrompt = promptBuilder.buildPromptForLLM(contextJson, config.getPromptConfig());
        logger.info("-> DEBUG LOG Request to gigachat \n" + llmPrompt);
        logger.info("Prepared LLM prompt for method " + methodInfo.getSignature()
                + (retryAttempt ? " [retry]" : ""));
        GeneratedTestSnippet snippet = llmClient.generateTestSnippet(llmPrompt, classInfo, methodInfo, plan);
        logger.info("Response from gigachat " + snippet);
        return autoCorrectionStage.apply(snippet);
    }

    private boolean shouldRetry(InvalidLLMResponseException exception) {
        if (exception == null) {
            return false;
        }
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("E102") || message.contains("E103") || message.contains("E104");
    }

    private void appendRetryHint(JSONObject contextJson) {
        if (contextJson == null) {
            return;
        }
        final String hint = "Skip unreachable or undefined constructors.";
        JSONArray hints = contextJson.optJSONArray("hints");
        if (hints == null) {
            hints = new JSONArray();
            contextJson.put("hints", hints);
        }
        for (int i = 0; i < hints.length(); i++) {
            if (hint.equalsIgnoreCase(hints.optString(i))) {
                return;
            }
        }
        hints.put(hint);
    }

    private ToolActionExecutor createActionExecutor(AgentConfig config,
                                                    TestClassInfo classInfo,
                                                    BuildFileEditor buildFileEditor,
                                                    SourceFileEditor sourceFileEditor,
                                                    GeneratedTestSnippet snippet) {
        return new ToolActionExecutor(logger,
                buildFileEditor,
                sourceFileEditor,
                compilerInvoker,
                executionInvoker,
                config.getProjectPath(),
                classInfo.getTargetPath(),
                snippet.methodName());
    }

    private CompilationPipelineOrchestrator createFixingOrchestrator(AgentConfig config,
                                                                     TestClassInfo classInfo,
                                                                     ProjectContextCollector projectContextCollector,
                                                                     ToolActionExecutor actionExecutor,
                                                                     GeneratedTestSnippet snippet) {
        return new CompilationPipelineOrchestrator(compilerInvoker,
                reasoningWorkflow,
                projectContextCollector,
                actionExecutor,
                logger,
                config.getProjectPath(),
                classInfo.getTargetPath(),
                classInfo.getTestClassName(),
                snippet.methodName());
    }

    private JSONObject buildRepairContext(JSONObject baseContext,
                                          CompileResult compileResult,
                                          ExecuteResult executeResult,
                                          ExecutionFailureParseResult failureParseResult,
                                          List<TestReportFailure> reportFailures,
                                          GeneratedTestSnippet snippet,
                                          int attempt) {
        JSONObject nextContext = baseContext == null ? new JSONObject() : new JSONObject(baseContext.toString());
        JSONObject repair = nextContext.optJSONObject("repair");
        if (repair == null) {
            repair = new JSONObject();
            nextContext.put("repair", repair);
        }
        repair.put("attempt", attempt);
        repair.put("chainOfThought", "Use chain-of-thought reasoning to iteratively fix compilation and execution issues.");
        if (snippet != null) {
            repair.put("previousSnippet", snippet.methodBody());
        }
        JSONArray diagnostics = new JSONArray();
        if (compileResult != null) {
            JSONObject compileBlock = new JSONObject();
            compileBlock.put("stage", "compile");
            compileBlock.put("messages", new JSONArray(compileResult.messages()));
            compileBlock.put("stdout", compileResult.stdout());
            compileBlock.put("stderr", compileResult.stderr());
            diagnostics.put(compileBlock);
        }
        if (executeResult != null) {
            JSONObject executeBlock = new JSONObject();
            executeBlock.put("stage", "execute");
            JSONArray messages = new JSONArray(executeResult.failedTests());
            if (failureParseResult != null && !failureParseResult.failures().isEmpty()) {
                JSONArray parsedFailures = new JSONArray();
                failureParseResult.failures().forEach(failure -> parsedFailures.put(failure.className() + "." + failure.methodName()));
                executeBlock.put("parsedFailures", parsedFailures);
                for (int i = 0; i < parsedFailures.length(); i++) {
                    messages.put(parsedFailures.get(i));
                }
            }
            if (reportFailures != null && !reportFailures.isEmpty()) {
                JSONArray reports = new JSONArray();
                for (TestReportFailure reportFailure : reportFailures) {
                    JSONObject detail = new JSONObject();
                    detail.put("class", reportFailure.className());
                    detail.put("method", reportFailure.methodName());
                    detail.put("message", reportFailure.message());
                    detail.put("stackTrace", new JSONArray(reportFailure.stackTrace()));
                    reports.put(detail);
                }
                executeBlock.put("reportFailures", reports);
            }
            executeBlock.put("messages", messages);
            executeBlock.put("stdout", executeResult.stdout());
            executeBlock.put("stderr", executeResult.stderr());
            diagnostics.put(executeBlock);
        }
        if (diagnostics.length() > 0) {
            repair.put("diagnostics", diagnostics);
        }
        return nextContext;
    }

    private ReasoningResponse triggerReasoningWorkflow(AgentConfig config,
                                                       TestClassInfo classInfo,
                                                       TestMethodInfo methodInfo,
                                                       CompileResult compileResult,
                                                       ExecuteResult executeResult) {
        try {
            CompilationErrorInfo errorInfo = compileResult != null
                    ? buildCompilationErrorInfo(compileResult, classInfo)
                    : buildExecutionErrorInfo(executeResult, classInfo, methodInfo);
            ProjectContextSummary summary = buildProjectContextSummary(config, classInfo);
            return reasoningWorkflow.process(errorInfo, summary);
        } catch (Exception exception) {
            logger.error("Reasoning workflow failed for method " + methodInfo.getSignature()
                    + ": " + exception.getMessage(), exception);
            return null;
        }
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
                                                         TestMethodInfo methodInfo) {
        String primaryMessage = executeResult.failedTests().isEmpty()
                ? executeResult.stderr()
                : executeResult.failedTests().get(0);
        String output = (executeResult.stdout() + System.lineSeparator() + executeResult.stderr()).trim();
        if (output.isBlank()) {
            output = "Execution failed for " + methodInfo.getSignature();
        }
        return new CompilationErrorInfo(
                output,
                primaryMessage,
                classInfo.getTestClassName(),
                classInfo.getTargetPath().toString(),
                null,
                null
        );
    }

    private ProjectContextSummary buildProjectContextSummary(AgentConfig config, TestClassInfo classInfo) {
        Path projectRoot = config.getProjectPath();
        Path testDirectory = classInfo.getTargetPath().getParent();
        List<String> sourceRoots = List.of(projectRoot.resolve("src/main/java").toString());
        List<String> testSourceRoots = testDirectory == null
                ? List.of()
                : List.of(testDirectory.toString());
        return new ProjectContextSummary(sourceRoots, testSourceRoots, List.of());
    }

    private void logReasoningResponse(String stage, ReasoningResponse response) {
        if (response == null) {
            logger.warn("Reasoning workflow returned no response for " + stage + " failure.");
            return;
        }
        logger.info("Reasoning workflow result for " + stage + " failure: " + response);
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

    private void validateGeneratedSnippet(AgentConfig config,
                                          TestClassInfo classInfo,
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
        CompilationUnit compilationUnit = parseCompilationUnit(fullSource);
        if (compilationUnit == null) {
            return;
        }
        Map<String, String> variableTypes = ensureMethodAndConstructorUsageIsValid(config,
                compilationUnit,
                analysisSummary,
                classInfo,
                methodInfo);
        ensureNoInternalFieldAccess(fullSource, analysisSummary);
        if (moduleConfig != null && moduleConfig.validateMockUsage()) {
            ensureMockUsageIsValid(fullSource, analysisSummary, classInfo);
        }
    }

    private CompilationUnit parseCompilationUnit(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            return StaticJavaParser.parse(source);
        } catch (ParseProblemException exception) {
            logger.warn("Unable to parse generated source for API validation: " + exception.getMessage());
            return null;
        }
    }

    private Map<String, String> ensureMethodAndConstructorUsageIsValid(AgentConfig config,
                                                                       CompilationUnit compilationUnit,
                                                                       Analyze.AnalysisSummary analysisSummary,
                                                                       TestClassInfo classInfo,
                                                                       TestMethodInfo methodInfo) {
        if (compilationUnit == null) {
            return Map.of();
        }
        Map<String, String> variableTypes = collectVariableTypes(compilationUnit, analysisSummary, classInfo);
        LinkedHashSet<String> issues = new LinkedHashSet<>();
        LinkedHashSet<String> missingConstructorMetadata = new LinkedHashSet<>();
        Set<String> signatureTypes = collectMethodSignatureTypeNames(methodInfo);
        compilationUnit.findAll(ObjectCreationExpr.class).forEach(expr -> {
            String type = simpleName(expr.getType().asString());
            if (type.isEmpty() || !signatureRegistry.hasClass(type)) {
                return;
            }
            int argumentCount = expr.getArguments().size();
            signatureRegistry.registerConstructorsIfAbsent(type);
            List<ConstructorMetadata> constructors = signatureRegistry.getConstructorsForClass(type);
            if (constructors.isEmpty() || !signatureRegistry.constructorExists(type, argumentCount)) {
                if (signatureTypes.contains(type)) {
                    attemptConstructorRefresh(config, classInfo, methodInfo, type);
                    constructors = signatureRegistry.getConstructorsForClass(type);
                    if (!constructors.isEmpty() && signatureRegistry.constructorExists(type, argumentCount)) {
                        return;
                    }
                }
                missingConstructorMetadata.add(type);
                issues.add("E104: Missing constructor metadata for " + formatConstructorInvocation(type, expr));
            }
        });
        compilationUnit.findAll(MethodCallExpr.class).forEach(expr -> {
            Optional<Expression> scope = expr.getScope();
            if (scope.isEmpty()) {
                return;
            }
            String resolvedType = resolveExpressionType(scope.get(), variableTypes, classInfo);
            if (isStandardLibraryType(resolvedType)) {
                return;
            }
            String simple = simpleName(resolvedType);
            if (simple.isEmpty() || !signatureRegistry.hasClass(simple)) {
                return;
            }
            if (!signatureRegistry.methodExists(simple, expr.getNameAsString(), expr.getArguments().size())) {
                issues.add("E102: Invented method " + formatMethodInvocation(simple, expr));
            }
        });
        if (!missingConstructorMetadata.isEmpty()) {
            logger.warn("Constructor metadata missing for: " + String.join(", ", missingConstructorMetadata));
        }
        if (!issues.isEmpty()) {
            String message = String.join("; ", issues);
            logger.warn("⚠️  " + message);
            throw new InvalidLLMResponseException(message);
        }
        return variableTypes;
    }

    private Set<String> collectMethodSignatureTypeNames(TestMethodInfo methodInfo) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (methodInfo == null) {
            return names;
        }
        MethodDeclaration declaration = methodInfo.getDeclaration();
        if (declaration != null) {
            declaration.getParameters().forEach(parameter ->
                    extractTypeNames(parameter.getType(), names));
            extractTypeNames(declaration.getType(), names);
        } else {
            String returnType = methodInfo.getReturnType();
            if (returnType != null && !returnType.isBlank()) {
                names.add(simpleName(returnType));
            }
        }
        return names;
    }

    private void extractTypeNames(com.github.javaparser.ast.type.Type type, Set<String> collector) {
        if (type == null || collector == null) {
            return;
        }
        if (type.isPrimitiveType()) {
            return;
        }
        if (type.isArrayType()) {
            extractTypeNames(type.asArrayType().getComponentType(), collector);
            return;
        }
        if (type.isUnionType()) {
            type.asUnionType().getElements().forEach(element -> extractTypeNames(element, collector));
            return;
        }
        if (type.isIntersectionType()) {
            type.asIntersectionType().getElements().forEach(element -> extractTypeNames(element, collector));
            return;
        }
        if (type.isWildcardType()) {
            type.asWildcardType().getExtendedType().ifPresent(t -> extractTypeNames(t, collector));
            type.asWildcardType().getSuperType().ifPresent(t -> extractTypeNames(t, collector));
            return;
        }
        if (type.isClassOrInterfaceType()) {
            collector.add(simpleName(type.asClassOrInterfaceType().getNameWithScope()));
            type.asClassOrInterfaceType().getTypeArguments()
                    .ifPresent(arguments -> arguments.forEach(argument -> extractTypeNames(argument, collector)));
            return;
        }
        collector.add(simpleName(type.asString()));
    }

    private void attemptConstructorRefresh(AgentConfig config,
                                           TestClassInfo classInfo,
                                           TestMethodInfo methodInfo,
                                           String type) {
        if (config == null || analyze == null) {
            return;
        }
        boolean refreshTriggered = signatureRegistry != null && signatureRegistry.refreshConstructors(type);
        if (logger != null) {
            String baseMessage = "Attempting constructor metadata refresh for type " + type
                    + " referenced in method signature before failing validation.";
            if (!refreshTriggered) {
                logger.warn(baseMessage + " Registry has no cached constructors yet.");
            } else {
                logger.warn(baseMessage);
            }
        }
        analyze.analyze(config, classInfo, methodInfo);
    }

    private void ensureNoInternalFieldAccess(String generatedCode,
                                             Analyze.AnalysisSummary analysisSummary) {
        if (analysisSummary == null) {
            return;
        }
        Set<String> internalFields = analysisSummary.internalFields();
        if (internalFields == null || internalFields.isEmpty()) {
            return;
        }
        String code = generatedCode == null ? "" : generatedCode;
        if (code.isBlank()) {
            return;
        }
        Pattern pattern = Pattern.compile("\\b(\\w+)\\.(\\w+)\\b");
        Matcher matcher = pattern.matcher(code);
        LinkedHashSet<String> violations = new LinkedHashSet<>();
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        String targetInstance = targetContext == null ? "" : normalise(targetContext.instanceName());
        Set<String> accessible = analysisSummary.accessibleFields();
        while (matcher.find()) {
            String instance = matcher.group(1);
            String field = matcher.group(2);
            if ("this".equals(instance) && field.equals(targetInstance)) {
                continue;
            }
            if (accessible != null && accessible.contains(field)) {
                continue;
            }
            int lookahead = matcher.end();
            while (lookahead < code.length() && Character.isWhitespace(code.charAt(lookahead))) {
                lookahead++;
            }
            if (lookahead < code.length() && code.charAt(lookahead) == '(') {
                continue;
            }
            if (internalFields.contains(field)) {
                violations.add(instance + '.' + field);
            }
        }
        if (!violations.isEmpty()) {
            String message = "E103: Internal field access " + String.join(", ", violations);
            logger.warn("⚠️  " + message);
            throw new InvalidLLMResponseException(message);
        }
    }

    private Map<String, String> collectVariableTypes(CompilationUnit compilationUnit,
                                                     Analyze.AnalysisSummary analysisSummary,
                                                     TestClassInfo classInfo) {
        Map<String, String> types = new LinkedHashMap<>();
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        if (targetContext != null && targetContext.instanceName() != null && !targetContext.instanceName().isBlank()) {
            types.put(targetContext.instanceName(), targetContext.className());
        }
        analysisSummary.availableMethods().keySet().forEach(className -> types.putIfAbsent(className, className));
        analysisSummary.availableConstructors().keySet().forEach(className -> types.putIfAbsent(className, className));
        compilationUnit.findAll(VariableDeclarator.class).forEach(declarator -> {
            String name = declarator.getNameAsString();
            if (name == null || name.isBlank()) {
                return;
            }
            String type = declarator.getType().asString();
            if ("var".equals(type)) {
                type = inferTypeFromInitializer(declarator.getInitializer());
            }
            types.putIfAbsent(name, type);
        });
        compilationUnit.findAll(MethodDeclaration.class).forEach(method ->
                method.getParameters().forEach(parameter -> types.putIfAbsent(parameter.getNameAsString(), parameter.getType().asString())));
        if (classInfo != null && classInfo.getClassMetadata() != null) {
            classInfo.getClassMetadata().getFields().forEach(field -> types.putIfAbsent(field.getName(), field.getTypeName()));
        }
        return types;
    }

    private String inferTypeFromInitializer(Optional<Expression> initializer) {
        if (initializer.isEmpty()) {
            return "";
        }
        Expression expression = initializer.get();
        if (expression instanceof ObjectCreationExpr creationExpr) {
            return creationExpr.getType().asString();
        }
        return "";
    }

    private String resolveExpressionType(Expression expression,
                                         Map<String, String> variableTypes,
                                         TestClassInfo classInfo) {
        if (expression instanceof ThisExpr) {
            return classInfo == null ? "" : classInfo.getClassName();
        }
        if (expression instanceof NameExpr nameExpr) {
            return variableTypes.getOrDefault(nameExpr.getNameAsString(), "");
        }
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            String fieldName = fieldAccessExpr.getNameAsString();
            String direct = variableTypes.get(fieldName);
            if (direct != null && !direct.isBlank()) {
                return direct;
            }
            return resolveExpressionType(fieldAccessExpr.getScope(), variableTypes, classInfo);
        }
        return "";
    }

    private String formatConstructorInvocation(String type, ObjectCreationExpr expr) {
        String arguments = describeArguments(new java.util.ArrayList<>(expr.getArguments()));
        return type + '(' + arguments + ')';
    }

    private String formatMethodInvocation(String type, MethodCallExpr expr) {
        String arguments = describeArguments(new java.util.ArrayList<>(expr.getArguments()));
        return type + '.' + expr.getNameAsString() + '(' + arguments + ')';
    }

    private String describeArguments(List<Expression> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        List<String> parts = new java.util.ArrayList<>(arguments.size());
        for (Expression argument : arguments) {
            String text = argument == null ? "" : argument.toString();
            if (text.length() > 40) {
                text = text.substring(0, 37) + "...";
            }
            parts.add(text);
        }
        return String.join(", ", parts);
    }

    private String simpleName(String type) {
        if (type == null) {
            return "";
        }
        String trimmed = type.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int genericStart = trimmed.indexOf('<');
        if (genericStart >= 0) {
            trimmed = trimmed.substring(0, genericStart);
        }
        int arrayIndex = trimmed.indexOf('[');
        if (arrayIndex >= 0) {
            trimmed = trimmed.substring(0, arrayIndex);
        }
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < trimmed.length()) {
            return trimmed.substring(lastDot + 1);
        }
        return trimmed;
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
                logger.warn("⚠️  E105: unexpected Mockito usage when mocks are disabled. External collaborator field detected: "
                        + collaboratorField + '.');
            } else {
                logger.warn("⚠️  E105: unexpected Mockito usage when mocks are disabled.");
            }
            throw new InvalidLLMResponseException("E105: unexpected Mockito usage when mocks are disabled.");
        }
    }

    private String normalise(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
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

    private boolean isStandardLibraryType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        for (String standard : Analyze.STANDARD_TYPES) {
            if (matchesStandardLibraryType(type, standard)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesStandardLibraryType(String candidate, String standard) {
        if (candidate == null || standard == null) {
            return false;
        }
        String trimmedCandidate = candidate.trim();
        if (trimmedCandidate.isEmpty()) {
            return false;
        }
        if (trimmedCandidate.contains(standard)) {
            return true;
        }
        String standardSimple = simpleName(standard);
        String candidateSimple = simpleName(trimmedCandidate);
        if (!standardSimple.isEmpty()) {
            if (candidateSimple.equals(standardSimple)) {
                return true;
            }
            if (trimmedCandidate.startsWith(standardSimple + "<")) {
                return true;
            }
            if (trimmedCandidate.endsWith('.' + standardSimple)) {
                return true;
            }
            if (trimmedCandidate.equals(standardSimple)) {
                return true;
            }
        }
        return false;
    }
}
