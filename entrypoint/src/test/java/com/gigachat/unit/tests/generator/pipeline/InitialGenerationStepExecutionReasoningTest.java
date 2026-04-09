package com.gigachat.unit.tests.generator.pipeline;

import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.config.AgentMode;
import com.gigachat.unit.tests.generator.dto.ErrorsReport;
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
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.orchestrator.CompilationReasoningOrchestrator;
import com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder;
import com.gigachat.unit.tests.generator.reasoning.service.CompilationReasoningService;
import com.gigachat.unit.tests.generator.reasoning.service.ReasoningResponseParser;
import com.gigachat.unit.tests.generator.reasoning.workflow.ReasoningWorkflow;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitialGenerationStepExecutionReasoningTest {

    @TempDir
    Path tempDir;

    @Test
    void executionFailureDoesNotTriggerFullRegenerationWhileCompilationStaysGreen() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
                }
                """);

        Path testFile = tempDir.resolve("src/test/java/com/example/SampleServiceTest.java");
        TestMethodInfo methodInfo = new TestMethodInfo("perform()", "void", "return;");
        TestClassInfo classInfo = new TestClassInfo(
                "SampleService",
                "SampleServiceTest",
                testFile,
                List.of(),
                List.of(methodInfo)
        );

        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        PipelineLogger logger = new PipelineLogger(tempDir);
        TestClassWriter writer = new TestClassWriter(logger);
        SkeletonPromptBuilder skeletonPromptBuilder = new SkeletonPromptBuilder();
        Analyze analyze = new Analyze(registry) {
            @Override
            public AnalysisSummary analyze(AgentConfig config, TestClassInfo currentClassInfo, TestMethodInfo currentMethodInfo) {
                return new AnalysisSummary(
                        new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("NotificationService"), List.of("User")),
                        new MethodAnalysisResult(new MethodMetadata("perform", "perform()", "void"), List.of(), List.of(), List.of(), List.of()),
                        "{}",
                        Map.of(),
                        new TestTargetContext("SampleService", "sampleService", true, false),
                        true,
                        List.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(),
                        Map.of("SampleService", List.of("perform()")),
                        Set.of(),
                        Set.of()
                );
            }
        };
        PromptBuilder promptBuilder = new PromptBuilder();
        CountingLlmClient llmClient = new CountingLlmClient();
        DiffEngine diffEngine = new DiffEngine(writer, logger);
        CompilerInvoker compilerInvoker = new CompilerInvoker() {
            @Override
            public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                return new CompileResult(true, List.of(), "", "");
            }
        };
        AtomicInteger executionCalls = new AtomicInteger();
        ExecutionInvoker executionInvoker = (projectRoot, testClassFilePath, generatedMethodName) -> {
            executionCalls.incrementAndGet();
            return new ExecuteResult(false,
                    List.of("com.example.SampleServiceTest." + generatedMethodName),
                    "",
                    "org.mockito.exceptions.misusing.NotAMockException: Argument passed to when() is not a mock!");
        };
        SnapshotStorage snapshotStorage = new SnapshotStorage(tempDir, logger);
        ReasoningWorkflow reasoningWorkflow = new ReasoningWorkflow(
                new CompilationReasoningOrchestrator(new CompilationReasoningService(
                        llmClient,
                        new CompilationReasoningPromptBuilder(),
                        new ReasoningResponseParser()))) {
            @Override
            public ReasoningResponse process(ReasoningLoopContext loopContext) {
                ReasoningResponse response = new ReasoningResponse();
                response.setDecision("STOP");
                response.setActions(List.of());
                return response;
            }
        };

        InitialGenerationStep generationStep = new InitialGenerationStep(logger,
                writer,
                skeletonPromptBuilder,
                analyze,
                promptBuilder,
                llmClient,
                diffEngine,
                compilerInvoker,
                executionInvoker,
                snapshotStorage,
                registry,
                reasoningWorkflow);

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(tempDir)
                .moduleOption("pipeline.compile.enabled", true)
                .moduleOption("pipeline.execute.enabled", true)
                .moduleOption("pipeline.snapshots.enabled", false)
                .build();

        ErrorsReport report = generationStep.run(config, List.of(classInfo));

        assertEquals(1, llmClient.generationCalls.get(), "Expected only the initial generation request");
        assertEquals(2, llmClient.executionReasoningCalls.get(), "Expected the forced second execution reasoning request");
        assertEquals(1, executionCalls.get(), "Execution should not restart after STOP");
        assertEquals(1, report.getExecuteErrors().size());
        assertTrue(Files.readString(testFile).contains("shouldExecutePerform"));

        Path logFile = tempDir.resolve(".agent/logs/pipeline.log");
        String logs = Files.readString(logFile);
        assertTrue(logs.contains("[EXECUTION_REASONING] Starting execution reasoning"));
        assertTrue(logs.contains("[EXECUTION_REASONING] LLM prompt attempt 1"));
        assertTrue(logs.contains("[EXECUTION_REASONING] LLM raw response attempt 1"));
        assertTrue(logs.contains("[EXECUTION_REASONING] Decision for shouldExecutePerform: STOP"));
        assertTrue(logs.contains("skipping full regeneration"));
    }

    private static class CountingLlmClient implements LlmClient {
        private final AtomicInteger generationCalls = new AtomicInteger();
        private final AtomicInteger executionReasoningCalls = new AtomicInteger();

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        MockPlan plan) {
            if ("ExecutionReasoningPlaceholder".equals(classInfo.getClassName())) {
                executionReasoningCalls.incrementAndGet();
                return new GeneratedTestSnippet(
                        classInfo.getTestClassName(),
                        methodInfo.getSignature(),
                        """
                                {"decision":"STOP","actions":[],"memory_updates":{}}
                                """,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        ""
                );
            }
            generationCalls.incrementAndGet();
            return new GeneratedTestSnippet(
                    classInfo.getTestClassName(),
                    "shouldExecutePerform",
                    """
                            @Test
                            void shouldExecutePerform() {
                                org.junit.jupiter.api.Assertions.assertTrue(true);
                            }
                            """,
                    List.of("import org.junit.jupiter.api.Test;", "import org.junit.jupiter.api.Assertions;")
            );
        }
    }
}
