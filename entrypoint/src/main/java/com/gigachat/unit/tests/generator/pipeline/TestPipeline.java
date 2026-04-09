package com.gigachat.unit.tests.generator.pipeline;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.compile.GradleCompilerInvoker;
import com.gigachat.unit.tests.generator.dto.ErrorsReport;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.execute.JUnitExecutionInvoker;
import com.gigachat.unit.tests.generator.llm.GigaChatMtlsClient;
import com.gigachat.unit.tests.generator.llm.GigaChatTokenClient;
import com.gigachat.unit.tests.generator.llm.LlmClient;
import com.gigachat.unit.tests.generator.llm.LlmClientStub;
import com.gigachat.unit.tests.generator.llm.ProxyHttpLlmClient;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.DiffEngine;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.pipeline.helpers.PromptBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.SkeletonPromptBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.SnapshotStorage;
import com.gigachat.unit.tests.generator.pipeline.helpers.TestClassWriter;
import com.gigachat.unit.tests.generator.scanner.JavaProjectScanner;
import com.gigachat.unit.tests.generator.reasoning.orchestrator.CompilationReasoningOrchestrator;
import com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder;
import com.gigachat.unit.tests.generator.reasoning.service.CompilationReasoningService;
import com.gigachat.unit.tests.generator.reasoning.service.ReasoningResponseParser;
import com.gigachat.unit.tests.generator.reasoning.workflow.ReasoningWorkflow;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TestPipeline {
    private final JavaProjectScanner scanner;
    private final MethodSignatureRegistry methodRegistry;

    public TestPipeline() {
        this(new MethodSignatureRegistry());
    }

    public TestPipeline(MethodSignatureRegistry methodRegistry) {
        this(new JavaProjectScanner(methodRegistry), methodRegistry);
    }

    public TestPipeline(JavaProjectScanner scanner) {
        this(scanner, scanner.getMethodRegistry());
    }

    public TestPipeline(JavaProjectScanner scanner,
                        MethodSignatureRegistry registry) {
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.methodRegistry = Objects.requireNonNull(registry, "methodRegistry");
    }

    public List<TestClassInfo> execute(AgentConfig config) throws IOException {
        if (config.isSingleFileMode()) {
            return executeSequentialMode(config);
        }
        List<TestClassInfo> classes = scanner.scan(config);
        System.out.printf("Scan completed: %d classes detected.%n", classes.size());
        InitialGenerationStep generationStep = createGenerationStep(config);
        ErrorsReport report = generationStep.run(config, classes);
        logReport(report);
        return classes;
    }

    private List<TestClassInfo> executeSequentialMode(AgentConfig config) throws IOException {
        List<TestClassInfo> processed = new ArrayList<>();
        InitialGenerationStep generationStep = createGenerationStep(config);
        scanner.scanSequentially(config, classes -> {
            if (classes == null || classes.isEmpty()) {
                return;
            }
            processed.addAll(classes);
            System.out.printf("Scan completed: %d classes detected.%n", classes.size());
            ErrorsReport report = generationStep.run(config, classes);
            logReport(report);
        });
        return List.copyOf(processed);
    }

    private void logReport(ErrorsReport report) {
        if (report.hasErrors()) {
            System.out.printf("Generation completed with %d compilation errors and %d execution errors.%n",
                    report.getCompileErrors().size(),
                    report.getExecuteErrors().size());
        } else {
            System.out.println("Generation completed without errors.");
        }
    }

    protected InitialGenerationStep createGenerationStep(AgentConfig config) {
        Path projectRoot = config.getProjectPath();
        PipelineLogger logger = new PipelineLogger(projectRoot);
        TestClassWriter testClassWriter = new TestClassWriter(logger);
        SkeletonPromptBuilder skeletonPromptBuilder = new SkeletonPromptBuilder();
        Analyze analyze = new Analyze(logger, methodRegistry);
        PromptBuilder promptBuilder = new PromptBuilder();
        LlmClient llmClient = createLlmClient(config, logger);
        DiffEngine diffEngine = new DiffEngine(testClassWriter, logger);
        CompilerInvoker compilerInvoker = new GradleCompilerInvoker(logger);
        ExecutionInvoker executionInvoker = new JUnitExecutionInvoker(logger);
        SnapshotStorage snapshotStorage = new SnapshotStorage(projectRoot, logger);
        CompilationReasoningPromptBuilder reasoningPromptBuilder = new CompilationReasoningPromptBuilder();
        ReasoningResponseParser reasoningResponseParser = new ReasoningResponseParser();
        CompilationReasoningService reasoningService = new CompilationReasoningService(
                llmClient,
                reasoningPromptBuilder,
                reasoningResponseParser,
                logger);
        ReasoningWorkflow reasoningWorkflow = new ReasoningWorkflow(
                new CompilationReasoningOrchestrator(reasoningService));
        return new InitialGenerationStep(logger,
                testClassWriter,
                skeletonPromptBuilder,
                analyze,
                promptBuilder,
                llmClient,
                diffEngine,
                compilerInvoker,
                executionInvoker,
                snapshotStorage,
                methodRegistry,
                reasoningWorkflow);
    }

    private LlmClient createLlmClient(AgentConfig config, PipelineLogger logger) {
        boolean proxyEnabled = Boolean.TRUE.equals(config.getModuleOptions().get("llm.proxy.enabled"));
        if (proxyEnabled) {
            logger.info("Initialising proxy HTTP LLM client (no auth).");
            return new ProxyHttpLlmClient(config.getGigaChat(), logger);
        }
        try {
            if (config.getGigaChat().isTokenAuthConfigured()) {
                logger.info("Initialising GigaChat client using bearer token authentication.");
                return new GigaChatTokenClient(config.getGigaChat(), logger);
            }
            if (config.getGigaChat().isMtlsConfigured()) {
                logger.info("Initialising GigaChat client using mTLS authentication.");
                return new GigaChatMtlsClient(config.getGigaChat(), logger);
            }
        } catch (IllegalStateException exception) {
            logger.error("Failed to initialise GigaChat client; falling back to stub.", exception);
        }
        logger.warn("GigaChat credentials not provided; using stub LLM client.");
        return new LlmClientStub();
    }
}
