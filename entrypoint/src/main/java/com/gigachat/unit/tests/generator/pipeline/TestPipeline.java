package com.gigachat.unit.tests.generator.pipeline;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.compile.GradleCompilerInvoker;
import com.gigachat.unit.tests.generator.dto.ErrorsReport;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.execute.JUnitExecutionInvoker;
import com.gigachat.unit.tests.generator.llm.LlmClient;
import com.gigachat.unit.tests.generator.llm.LlmClientStub;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.DiffEngine;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.pipeline.helpers.PromptBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.SkeletonPromptBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.SnapshotStorage;
import com.gigachat.unit.tests.generator.pipeline.helpers.TestClassWriter;
import com.gigachat.unit.tests.generator.scanner.JavaProjectScanner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class TestPipeline {
    private final JavaProjectScanner scanner;

    public TestPipeline() {
        this(new JavaProjectScanner());
    }

    public TestPipeline(JavaProjectScanner scanner) {
        this.scanner = Objects.requireNonNull(scanner, "scanner");
    }

    public List<TestClassInfo> execute(AgentConfig config) throws IOException {
        List<TestClassInfo> classes = scanner.scan(config);
        System.out.printf("Scan completed: %d classes detected.%n", classes.size());
        InitialGenerationStep generationStep = createGenerationStep(config.getProjectPath());
        ErrorsReport report = generationStep.run(config, classes);
        if (report.hasErrors()) {
            System.out.printf("Generation completed with %d compilation errors and %d execution errors.%n",
                    report.getCompileErrors().size(),
                    report.getExecuteErrors().size());
        } else {
            System.out.println("Generation completed without errors.");
        }
        return classes;
    }

    private InitialGenerationStep createGenerationStep(Path projectRoot) {
        PipelineLogger logger = new PipelineLogger(projectRoot);
        TestClassWriter testClassWriter = new TestClassWriter(logger);
        SkeletonPromptBuilder skeletonPromptBuilder = new SkeletonPromptBuilder();
        Analyze analyze = new Analyze(logger);
        PromptBuilder promptBuilder = new PromptBuilder();
        LlmClient llmClient = new LlmClientStub();
        DiffEngine diffEngine = new DiffEngine(testClassWriter, logger);
        CompilerInvoker compilerInvoker = new GradleCompilerInvoker(logger);
        ExecutionInvoker executionInvoker = new JUnitExecutionInvoker(logger);
        SnapshotStorage snapshotStorage = new SnapshotStorage(projectRoot, logger);
        return new InitialGenerationStep(logger,
                testClassWriter,
                skeletonPromptBuilder,
                analyze,
                promptBuilder,
                llmClient,
                diffEngine,
                compilerInvoker,
                executionInvoker,
                snapshotStorage);
    }
}
