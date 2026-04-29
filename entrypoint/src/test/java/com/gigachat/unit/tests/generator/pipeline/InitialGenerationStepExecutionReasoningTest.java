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
import com.gigachat.unit.tests.generator.dto.MockTarget;
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
import com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder;
import com.gigachat.unit.tests.generator.reasoning.service.CompilationReasoningService;
import com.gigachat.unit.tests.generator.reasoning.service.ReasoningResponseParser;
import com.testagent.entrypoint.pipeline.helpers.analyze.DependencyInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodMetadata;
import com.testagent.entrypoint.pipeline.helpers.analyze.MockType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitialGenerationStepExecutionReasoningTest {

    @TempDir
    Path tempDir;

    @Test
    void placeholderSnippetWithoutSutInvocationTriggersValidationRetry() throws Exception {
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
        Analyze analyze = new Analyze(registry) {
            @Override
            public AnalysisSummary analyze(AgentConfig config, TestClassInfo currentClassInfo, TestMethodInfo currentMethodInfo) {
                return new AnalysisSummary(
                        new MockPlan(List.of(), MockStrategy.MOCKITO, List.of(), List.of()),
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

        RetryingGenerationLlmClient llmClient = new RetryingGenerationLlmClient();
        InitialGenerationStep generationStep = new InitialGenerationStep(
                logger,
                writer,
                new SkeletonPromptBuilder(),
                analyze,
                new PromptBuilder(),
                llmClient,
                new DiffEngine(writer, logger),
                (projectRoot, testClassFile, generatedMethodName) -> new CompileResult(true, List.of(), "", ""),
                (projectRoot, testClassFilePath, generatedMethodName) -> new ExecuteResult(true, List.of(), "", ""),
                new SnapshotStorage(tempDir, logger),
                registry,
                new CompilationReasoningService(
                        llmClient,
                        new CompilationReasoningPromptBuilder(),
                        new ReasoningResponseParser())
        );

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(tempDir)
                .moduleOption("pipeline.compile.enabled", false)
                .moduleOption("pipeline.execute.enabled", false)
                .moduleOption("pipeline.snapshots.enabled", false)
                .build();

        generationStep.run(config, List.of(classInfo));

        assertEquals(2, llmClient.generationCalls.get(), "Expected placeholder snippet to be rejected and retried");
        String generatedTest = Files.readString(testFile);
        assertTrue(generatedTest.contains("sampleService.perform();"));

        String logs = Files.readString(tempDir.resolve(".agent/logs/pipeline.log"));
        assertTrue(logs.contains("E108: generated test does not invoke target method"));
    }

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
        CompilationReasoningService reasoningWorkflow = new CompilationReasoningService(
                llmClient,
                new CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser()) {
            @Override
            public ReasoningResponse reasonAboutError(ReasoningLoopContext loopContext) {
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
        assertTrue(logs.contains("Stage=SEND_TO_GIGACHAT method=shouldExecutePerform, attempt=1"));
        assertTrue(logs.contains("Stage=RECEIVE_FROM_GIGACHAT method=shouldExecutePerform, attempt=1"));
        assertTrue(logs.contains("[EXECUTION_REASONING] LLM prompt attempt 1"));
        assertTrue(logs.contains("[EXECUTION_REASONING] LLM raw response attempt 1"));
        assertTrue(logs.contains("[EXECUTION_REASONING] Decision for shouldExecutePerform: STOP"));
        assertTrue(logs.contains("skipping full regeneration"));

        Path artifactDir = tempDir.resolve(".agent/logs/execution-reasoning");
        assertTrue(Files.exists(artifactDir.resolve("shouldExecutePerform-attempt-1.prompt.txt")));
        assertTrue(Files.exists(artifactDir.resolve("shouldExecutePerform-attempt-1.response.txt")));
        assertTrue(Files.readString(artifactDir.resolve("shouldExecutePerform-attempt-1.prompt.txt"))
                .contains("Execution-only hard constraint"));
        assertTrue(Files.readString(artifactDir.resolve("shouldExecutePerform-attempt-1.response.txt"))
                .contains("\"decision\":\"STOP\""));

        Path traceFile = tempDir.resolve(".agent/logs/state-trace.log");
        assertTrue(Files.exists(traceFile));
        String trace = Files.readString(traceFile);
        assertTrue(trace.contains("[FLOW]"));
        assertTrue(trace.contains("state=S0_INIT"));
        assertTrue(trace.contains("[GIGACHAT]"));
        assertTrue(trace.contains("[STATE]"));
        assertTrue(trace.contains("state=EXECUTION"));
        assertTrue(trace.contains("[DECISION]"));
        assertTrue(trace.contains("gigachat chose STOP"));
        assertTrue(trace.contains("[RESULT]"));
    }

    @Test
    void voidMethodInsideMockitoWhenTriggersValidationRetry() throws Exception {
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
        registry.registerConstructor("SampleService", "SampleService(NotificationService)");
        registry.registerMethod("SampleService", "void perform()");
        registry.registerMethod("NotificationService", "void sendWelcome(User)");

        PipelineLogger logger = new PipelineLogger(tempDir);
        TestClassWriter writer = new TestClassWriter(logger);
        Analyze analyze = new Analyze(registry) {
            @Override
            public AnalysisSummary analyze(AgentConfig config, TestClassInfo currentClassInfo, TestMethodInfo currentMethodInfo) {
                return new AnalysisSummary(
                        new MockPlan(List.of(new MockTarget("NotificationService", "notificationService")),
                                MockStrategy.MOCKITO,
                                List.of("notificationService"),
                                List.of()),
                        new MethodAnalysisResult(
                                new MethodMetadata("perform", "perform()", "void"),
                                List.of(new DependencyInfo(
                                        "NotificationService",
                                        "notificationService",
                                        MockType.FIELD,
                                        "field",
                                        false,
                                        false,
                                        0)),
                                List.of(),
                                List.of(),
                                List.of()),
                        "{}",
                        Map.of(),
                        new TestTargetContext("SampleService", "sampleService", true, false),
                        true,
                        List.of(),
                        Set.of(),
                        Set.of(),
                        Map.of("SampleService", List.of(new com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata(
                                "SampleService(NotificationService)",
                                List.of(new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata(
                                        "notificationService",
                                        "NotificationService",
                                        List.of()))
                        ))),
                        Map.of(
                                "SampleService", List.of("void perform()"),
                                "NotificationService", List.of("void sendWelcome(User)")
                        ),
                        Set.of(),
                        Set.of()
                );
            }
        };

        RetryingVoidWhenLlmClient llmClient = new RetryingVoidWhenLlmClient();
        InitialGenerationStep generationStep = new InitialGenerationStep(
                logger,
                writer,
                new SkeletonPromptBuilder(),
                analyze,
                new PromptBuilder(),
                llmClient,
                new DiffEngine(writer, logger),
                (projectRoot, testClassFile, generatedMethodName) -> new CompileResult(true, List.of(), "", ""),
                (projectRoot, testClassFilePath, generatedMethodName) -> new ExecuteResult(true, List.of(), "", ""),
                new SnapshotStorage(tempDir, logger),
                registry,
                new CompilationReasoningService(
                        llmClient,
                        new CompilationReasoningPromptBuilder(),
                        new ReasoningResponseParser())
        );

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(tempDir)
                .moduleOption("pipeline.compile.enabled", false)
                .moduleOption("pipeline.execute.enabled", false)
                .moduleOption("pipeline.snapshots.enabled", false)
                .build();

        generationStep.run(config, List.of(classInfo));

        assertEquals(2, llmClient.generationCalls.get(), "Expected void-when snippet to be rejected and retried");
        String generatedTest = Files.readString(testFile);
        assertTrue(generatedTest.contains("doNothing().when(notificationService).sendWelcome(any());"));
        assertTrue(generatedTest.contains("sampleService.perform();"));

        String logs = Files.readString(tempDir.resolve(".agent/logs/pipeline.log"));
        assertTrue(logs.contains("E109: void method invocation used inside Mockito.when(...)"));
    }

    @Test
    void voidMethodInsideMockitoWhenThenCallRealMethodTriggersValidationRetry() throws Exception {
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
        registry.registerConstructor("SampleService", "SampleService(NotificationService)");
        registry.registerMethod("SampleService", "void perform()");
        registry.registerMethod("NotificationService", "void sendWelcome(User)");

        PipelineLogger logger = new PipelineLogger(tempDir);
        TestClassWriter writer = new TestClassWriter(logger);
        Analyze analyze = new Analyze(registry) {
            @Override
            public AnalysisSummary analyze(AgentConfig config, TestClassInfo currentClassInfo, TestMethodInfo currentMethodInfo) {
                return new AnalysisSummary(
                        new MockPlan(List.of(new MockTarget("NotificationService", "notificationService")),
                                MockStrategy.MOCKITO,
                                List.of("notificationService"),
                                List.of()),
                        new MethodAnalysisResult(
                                new MethodMetadata("perform", "perform()", "void"),
                                List.of(new DependencyInfo(
                                        "NotificationService",
                                        "notificationService",
                                        MockType.FIELD,
                                        "field",
                                        false,
                                        false,
                                        0)),
                                List.of(),
                                List.of(),
                                List.of()),
                        "{}",
                        Map.of(),
                        new TestTargetContext("SampleService", "sampleService", true, false),
                        true,
                        List.of(),
                        Set.of(),
                        Set.of(),
                        Map.of("SampleService", List.of(new com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata(
                                "SampleService(NotificationService)",
                                List.of(new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata(
                                        "notificationService",
                                        "NotificationService",
                                        List.of()))
                        ))),
                        Map.of(
                                "SampleService", List.of("void perform()"),
                                "NotificationService", List.of("void sendWelcome(User)")
                        ),
                        Set.of(),
                        Set.of()
                );
            }
        };

        RetryingVoidWhenThenCallRealMethodLlmClient llmClient = new RetryingVoidWhenThenCallRealMethodLlmClient();
        InitialGenerationStep generationStep = new InitialGenerationStep(
                logger,
                writer,
                new SkeletonPromptBuilder(),
                analyze,
                new PromptBuilder(),
                llmClient,
                new DiffEngine(writer, logger),
                (projectRoot, testClassFile, generatedMethodName) -> new CompileResult(true, List.of(), "", ""),
                (projectRoot, testClassFilePath, generatedMethodName) -> new ExecuteResult(true, List.of(), "", ""),
                new SnapshotStorage(tempDir, logger),
                registry,
                new CompilationReasoningService(
                        llmClient,
                        new CompilationReasoningPromptBuilder(),
                        new ReasoningResponseParser())
        );

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(tempDir)
                .moduleOption("pipeline.compile.enabled", false)
                .moduleOption("pipeline.execute.enabled", false)
                .moduleOption("pipeline.snapshots.enabled", false)
                .build();

        generationStep.run(config, List.of(classInfo));

        assertEquals(2, llmClient.generationCalls.get(), "Expected void-thenCallRealMethod snippet to be rejected and retried");
        String generatedTest = Files.readString(testFile);
        assertTrue(generatedTest.contains("doNothing().when(notificationService).sendWelcome(any());"));
        assertTrue(generatedTest.contains("sampleService.perform();"));

        String logs = Files.readString(tempDir.resolve(".agent/logs/pipeline.log"));
        assertTrue(logs.contains("thenCallRealMethod"));
        assertTrue(logs.contains("E109: void method invocation used inside Mockito.when(...)"));
    }

    @Test
    void voidMutatorUsedAsValueExpressionCanUseFallbackWithoutRetry() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/UserServiceTest.java");
        TestMethodInfo methodInfo = new TestMethodInfo("activeUsernames()", "List<String>", "return java.util.List.of();");
        TestClassInfo classInfo = new TestClassInfo(
                "UserService",
                "UserServiceTest",
                testFile,
                List.of(),
                List.of(methodInfo)
        );

        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerConstructor("UserService", "UserService(UserRepository)");
        registry.registerConstructor("User", "User(String username, String email)");
        registry.registerMethod("UserService", "List<String> activeUsernames()");
        registry.registerMethod("UserRepository", "List<User> findAll()");
        registry.registerMethod("User", "String getUsername()");
        registry.registerMethod("User", "boolean isActive()");
        registry.registerMethod("User", "void deactivate()");

        PipelineLogger logger = new PipelineLogger(tempDir);
        TestClassWriter writer = new TestClassWriter(logger);
        Analyze analyze = new Analyze(registry) {
            @Override
            public AnalysisSummary analyze(AgentConfig config, TestClassInfo currentClassInfo, TestMethodInfo currentMethodInfo) {
                return new AnalysisSummary(
                        new MockPlan(List.of(new MockTarget("UserRepository", "repository")),
                                MockStrategy.MOCKITO,
                                List.of("repository"),
                                List.of()),
                        new MethodAnalysisResult(
                                new MethodMetadata("activeUsernames", "activeUsernames()", "List<String>"),
                                List.of(new DependencyInfo(
                                        "UserRepository",
                                        "repository",
                                        MockType.FIELD,
                                        "field",
                                        false,
                                        false,
                                        0)),
                                List.of(),
                                List.of(),
                                List.of()),
                        "{}",
                        Map.of(),
                        new TestTargetContext("UserService", "service", true, false),
                        true,
                        List.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(
                                "UserService", List.of(new com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata(
                                        "UserService(UserRepository)",
                                        List.of(new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata(
                                                "repository",
                                                "UserRepository",
                                                List.of())))),
                                "User", List.of(new com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata(
                                        "User(String username, String email)",
                                        List.of(
                                                new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata("username", "String", List.of()),
                                                new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata("email", "String", List.of())
                                        )))
                        ),
                        Map.of(
                                "UserService", List.of("List<String> activeUsernames()"),
                                "UserRepository", List.of("List<User> findAll()"),
                                "User", List.of("String getUsername()", "boolean isActive()", "void deactivate()")
                        ),
                        Set.of(),
                        Set.of()
                );
            }
        };

        RetryingVoidMutatorValueLlmClient llmClient = new RetryingVoidMutatorValueLlmClient();
        InitialGenerationStep generationStep = new InitialGenerationStep(
                logger,
                writer,
                new SkeletonPromptBuilder(),
                analyze,
                new PromptBuilder(),
                llmClient,
                new DiffEngine(writer, logger),
                (projectRoot, testClassFile, generatedMethodName) -> new CompileResult(true, List.of(), "", ""),
                (projectRoot, testClassFilePath, generatedMethodName) -> new ExecuteResult(true, List.of(), "", ""),
                new SnapshotStorage(tempDir, logger),
                registry,
                new CompilationReasoningService(
                        llmClient,
                        new CompilationReasoningPromptBuilder(),
                        new ReasoningResponseParser())
        );

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(tempDir)
                .moduleOption("pipeline.compile.enabled", false)
                .moduleOption("pipeline.execute.enabled", false)
                .moduleOption("pipeline.snapshots.enabled", false)
                .build();

        generationStep.run(config, List.of(classInfo));

        assertEquals(1, llmClient.generationCalls.get(), "Expected void-mutator-as-value snippet to be repaired by deterministic fallback");
        String generatedTest = Files.readString(testFile);
        assertTrue(generatedTest.contains("var generatedUser"));
        assertTrue(generatedTest.contains("= new User(\"Bob\", \"bob@example.com\");"));
        assertTrue(generatedTest.contains(".deactivate();"));
        assertTrue(generatedTest.contains("users.add(generatedUser"));

        String logs = Files.readString(tempDir.resolve(".agent/logs/pipeline.log"));
        assertTrue(logs.contains("E113: void mutator used as value expression"));
        assertTrue(logs.contains("Built deterministic constructor-state fallback"));
    }

    @Test
    void voidThenCallRealMethodInsideHelperMethodTriggersValidationRetry() throws Exception {
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
        registry.registerConstructor("SampleService", "SampleService(NotificationService)");
        registry.registerMethod("SampleService", "void perform()");
        registry.registerMethod("NotificationService", "void sendWelcome(User)");

        PipelineLogger logger = new PipelineLogger(tempDir);
        TestClassWriter writer = new TestClassWriter(logger);
        Analyze analyze = new Analyze(registry) {
            @Override
            public AnalysisSummary analyze(AgentConfig config, TestClassInfo currentClassInfo, TestMethodInfo currentMethodInfo) {
                return new AnalysisSummary(
                        new MockPlan(List.of(new MockTarget("NotificationService", "notificationService")),
                                MockStrategy.MOCKITO,
                                List.of("notificationService"),
                                List.of()),
                        new MethodAnalysisResult(
                                new MethodMetadata("perform", "perform()", "void"),
                                List.of(new DependencyInfo(
                                        "NotificationService",
                                        "notificationService",
                                        MockType.FIELD,
                                        "field",
                                        false,
                                        false,
                                        0)),
                                List.of(),
                                List.of(),
                                List.of()),
                        "{}",
                        Map.of(),
                        new TestTargetContext("SampleService", "sampleService", true, false),
                        true,
                        List.of(),
                        Set.of(),
                        Set.of(),
                        Map.of("SampleService", List.of(new com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata(
                                "SampleService(NotificationService)",
                                List.of(new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata(
                                        "notificationService",
                                        "NotificationService",
                                        List.of()))
                        ))),
                        Map.of(
                                "SampleService", List.of("void perform()"),
                                "NotificationService", List.of("void sendWelcome(User)")
                        ),
                        Set.of(),
                        Set.of()
                );
            }
        };

        RetryingHelperVoidWhenThenCallRealMethodLlmClient llmClient = new RetryingHelperVoidWhenThenCallRealMethodLlmClient();
        InitialGenerationStep generationStep = new InitialGenerationStep(
                logger,
                writer,
                new SkeletonPromptBuilder(),
                analyze,
                new PromptBuilder(),
                llmClient,
                new DiffEngine(writer, logger),
                (projectRoot, testClassFile, generatedMethodName) -> new CompileResult(true, List.of(), "", ""),
                (projectRoot, testClassFilePath, generatedMethodName) -> new ExecuteResult(true, List.of(), "", ""),
                new SnapshotStorage(tempDir, logger),
                registry,
                new CompilationReasoningService(
                        llmClient,
                        new CompilationReasoningPromptBuilder(),
                        new ReasoningResponseParser())
        );

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(tempDir)
                .moduleOption("pipeline.compile.enabled", false)
                .moduleOption("pipeline.execute.enabled", false)
                .moduleOption("pipeline.snapshots.enabled", false)
                .build();

        generationStep.run(config, List.of(classInfo));

        assertEquals(2, llmClient.generationCalls.get(), "Expected helper-method void Mockito snippet to be rejected and retried");
        String generatedTest = Files.readString(testFile);
        assertTrue(generatedTest.contains("doNothing().when(notificationService).sendWelcome(any());"));

        String logs = Files.readString(tempDir.resolve(".agent/logs/pipeline.log"));
        assertTrue(logs.contains("thenCallRealMethod"));
        assertTrue(logs.contains("E109: void method invocation used inside Mockito.when(...)"));
    }

    @Test
    void nonRetryableInvalidGenerationResponseDoesNotCrashPipeline() throws Exception {
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
        registry.registerConstructor("SampleService", "SampleService()");
        registry.registerMethod("SampleService", "void perform()");

        PipelineLogger logger = new PipelineLogger(tempDir);
        TestClassWriter writer = new TestClassWriter(logger);
        Analyze analyze = new Analyze(registry) {
            @Override
            public AnalysisSummary analyze(AgentConfig config, TestClassInfo currentClassInfo, TestMethodInfo currentMethodInfo) {
                return new AnalysisSummary(
                        new MockPlan(List.of(), MockStrategy.MOCKITO, List.of(), List.of()),
                        new MethodAnalysisResult(new MethodMetadata("perform", "perform()", "void"), List.of(), List.of(), List.of(), List.of()),
                        "{}",
                        Map.of(),
                        new TestTargetContext("SampleService", "sampleService", true, false),
                        true,
                        List.of(),
                        Set.of(),
                        Set.of(),
                        Map.of("SampleService", List.of(new com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata(
                                "SampleService()",
                                List.of()
                        ))),
                        Map.of("SampleService", List.of("void perform()")),
                        Set.of(),
                        Set.of()
                );
            }
        };

        ReimplementationLlmClient llmClient = new ReimplementationLlmClient();
        InitialGenerationStep generationStep = new InitialGenerationStep(
                logger,
                writer,
                new SkeletonPromptBuilder(),
                analyze,
                new PromptBuilder(),
                llmClient,
                new DiffEngine(writer, logger),
                (projectRoot, testClassFile, generatedMethodName) -> new CompileResult(true, List.of(), "", ""),
                (projectRoot, testClassFilePath, generatedMethodName) -> new ExecuteResult(true, List.of(), "", ""),
                new SnapshotStorage(tempDir, logger),
                registry,
                new CompilationReasoningService(
                        llmClient,
                        new CompilationReasoningPromptBuilder(),
                        new ReasoningResponseParser())
        );

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(tempDir)
                .moduleOption("pipeline.compile.enabled", false)
                .moduleOption("pipeline.execute.enabled", false)
                .moduleOption("pipeline.snapshots.enabled", false)
                .build();

        ErrorsReport report = generationStep.run(config, List.of(classInfo));

        assertEquals(1, llmClient.generationCalls.get(), "Expected non-retryable invalid snippet to stop without crashing");
        assertTrue(report.getCompileErrors().isEmpty());
        assertTrue(report.getExecuteErrors().isEmpty());

        String logs = Files.readString(tempDir.resolve(".agent/logs/pipeline.log"));
        assertTrue(logs.contains("Discarding invalid generation response for method perform()"));
        String trace = Files.readString(tempDir.resolve(".agent/logs/state-trace.log"));
        assertTrue(trace.contains("generation produced no valid snippet"));
    }

    @Test
    void inventedSetActiveFallbackCanRecoverWithoutLlmRetry() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/UserServiceTest.java");
        TestMethodInfo methodInfo = new TestMethodInfo("activeUsernames()", "List<String>", "return java.util.List.of();");
        TestClassInfo classInfo = new TestClassInfo(
                "UserService",
                "UserServiceTest",
                testFile,
                List.of(),
                List.of(methodInfo)
        );

        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerConstructor("UserService", "UserService(UserRepository)");
        registry.registerConstructor("User", "User(String username, String email)");
        registry.registerMethod("UserService", "List<String> activeUsernames()");
        registry.registerMethod("UserRepository", "List<User> findAll()");
        registry.registerMethod("User", "String getUsername()");
        registry.registerMethod("User", "boolean isActive()");
        registry.registerMethod("User", "void deactivate()");
        registry.registerMethod("User", "void activate()");

        PipelineLogger logger = new PipelineLogger(tempDir);
        TestClassWriter writer = new TestClassWriter(logger);
        Analyze analyze = new Analyze(registry) {
            @Override
            public AnalysisSummary analyze(AgentConfig config, TestClassInfo currentClassInfo, TestMethodInfo currentMethodInfo) {
                return new AnalysisSummary(
                        new MockPlan(List.of(new MockTarget("UserRepository", "repository")),
                                MockStrategy.MOCKITO,
                                List.of("repository"),
                                List.of()),
                        new MethodAnalysisResult(
                                new MethodMetadata("activeUsernames", "activeUsernames()", "List<String>"),
                                List.of(new DependencyInfo(
                                        "UserRepository",
                                        "repository",
                                        MockType.FIELD,
                                        "field",
                                        false,
                                        false,
                                        0)),
                                List.of(),
                                List.of(),
                                List.of()),
                        "{}",
                        Map.of(),
                        new TestTargetContext("UserService", "service", true, false),
                        true,
                        List.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(
                                "UserService", List.of(new com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata(
                                        "UserService(UserRepository)",
                                        List.of(new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata(
                                                "repository",
                                                "UserRepository",
                                                List.of())))),
                                "User", List.of(new com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata(
                                        "User(String username, String email)",
                                        List.of(
                                                new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata("username", "String", List.of()),
                                                new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata("email", "String", List.of())
                                        )))
                        ),
                        Map.of(
                                "UserService", List.of("List<String> activeUsernames()"),
                                "UserRepository", List.of("List<User> findAll()"),
                                "User", List.of("String getUsername()", "boolean isActive()", "void deactivate()", "void activate()")
                        ),
                        Set.of(),
                        Set.of()
                );
            }
        };

        SingleInventedSetterLlmClient llmClient = new SingleInventedSetterLlmClient();
        InitialGenerationStep generationStep = new InitialGenerationStep(
                logger,
                writer,
                new SkeletonPromptBuilder(),
                analyze,
                new PromptBuilder(),
                llmClient,
                new DiffEngine(writer, logger),
                (projectRoot, testClassFile, generatedMethodName) -> new CompileResult(true, List.of(), "", ""),
                (projectRoot, testClassFilePath, generatedMethodName) -> new ExecuteResult(true, List.of(), "", ""),
                new SnapshotStorage(tempDir, logger),
                registry,
                new CompilationReasoningService(
                        llmClient,
                        new CompilationReasoningPromptBuilder(),
                        new ReasoningResponseParser())
        );

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(tempDir)
                .moduleOption("pipeline.compile.enabled", false)
                .moduleOption("pipeline.execute.enabled", false)
                .moduleOption("pipeline.snapshots.enabled", false)
                .build();

        generationStep.run(config, List.of(classInfo));

        assertEquals(1, llmClient.generationCalls.get(), "Expected deterministic invented-setter fallback to recover without another LLM retry");
        String generatedTest = Files.readString(testFile);
        assertTrue(generatedTest.contains("activeUser.activate();"));
        assertTrue(generatedTest.contains("inactiveUser.deactivate();"));
        assertFalse(generatedTest.contains("setActive("));

        String logs = Files.readString(tempDir.resolve(".agent/logs/pipeline.log"));
        assertTrue(logs.contains("Using deterministic invented-state-mutator fallback"));
    }

    @Test
    void preMergeScratchCompilationFailureRunsCompileReasoningBeforeMerge() throws Exception {
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
        Analyze analyze = new Analyze(registry) {
            @Override
            public AnalysisSummary analyze(AgentConfig config, TestClassInfo currentClassInfo, TestMethodInfo currentMethodInfo) {
                return new AnalysisSummary(
                        new MockPlan(List.of(), MockStrategy.MOCKITO, List.of(), List.of()),
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

        PreMergeCompileRepairLlmClient llmClient = new PreMergeCompileRepairLlmClient();
        InitialGenerationStep generationStep = new InitialGenerationStep(
                logger,
                writer,
                new SkeletonPromptBuilder(),
                analyze,
                new PromptBuilder(),
                llmClient,
                new DiffEngine(writer, logger),
                (projectRoot, testClassFilePath, generatedMethodName) -> {
                    try {
                        String source = Files.readString(testClassFilePath);
                        if (source.contains("@BeforeEach\n    @BeforeEach") || source.contains("@BeforeEach\r\n    @BeforeEach")) {
                            return new CompileResult(false,
                                    List.of("synthetic duplicate @BeforeEach annotation"),
                                    "",
                                    testClassFilePath
                                            + ":6: error: org.junit.jupiter.api.BeforeEach is not a repeatable annotation interface\n");
                        }
                    } catch (Exception exception) {
                        throw new IllegalStateException("Unable to read synthetic test source", exception);
                    }
                    return new CompileResult(true, List.of(), "", "");
                },
                (projectRoot, testClassFilePath, generatedMethodName) -> new ExecuteResult(true, List.of(), "", ""),
                new SnapshotStorage(tempDir, logger),
                registry,
                new CompilationReasoningService(
                        llmClient,
                        new CompilationReasoningPromptBuilder(),
                        new ReasoningResponseParser())
        );

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(tempDir)
                .moduleOption("pipeline.compile.enabled", true)
                .moduleOption("pipeline.execute.enabled", false)
                .moduleOption("pipeline.snapshots.enabled", false)
                .build();

        generationStep.run(config, List.of(classInfo));

        assertEquals(1, llmClient.generationCalls.get(), "Expected scratch compile reasoning to repair without regeneration");
        assertEquals(1, llmClient.reasoningCalls.get(), "Expected one compile reasoning decision for scratch repair");
        String generatedTest = Files.readString(testFile);
        assertTrue(generatedTest.contains("FIRST-SCRATCH-REPAIRED"));
        assertFalse(generatedTest.contains("@BeforeEach\n    @BeforeEach"));
        assertFalse(generatedTest.contains("@BeforeEach\r\n    @BeforeEach"));

        String logs = Files.readString(tempDir.resolve(".agent/logs/pipeline.log"));
        assertTrue(logs.contains("action=START_PRE_MERGE_SCRATCH_COMPILE_REPAIR"));
        assertTrue(logs.contains("[COMPILATION_REASONING] Pre-merge scratch compile errors"));
        assertTrue(logs.contains("[COMPILATION_REASONING] Stage=ASK_LLM method=shouldExecutePerform"));
        assertTrue(logs.contains("[COMPILATION_REASONING] action=APPLY_FIX method=shouldExecutePerform"));
        assertTrue(logs.contains("action=KEEP_PRE_MERGE_SCRATCH_REPAIR"));
        assertFalse(logs.contains("compileReasoning=SKIPPED_PRE_MERGE_SCRATCH"));
        assertFalse(logs.contains("Pre-merge sibling-isolation validation rejected method"));
    }

    @Test
    void preMergeScratchExecutionFailureRunsExecutionReasoningBeforeMerge() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/SampleServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example;

                import org.junit.jupiter.api.Test;

                public class SampleServiceTest {

                    @Test
                    void existingSiblingShouldStay() {
                        org.junit.jupiter.api.Assertions.assertTrue(false);
                    }
                }
                """);
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
        Analyze analyze = new Analyze(registry) {
            @Override
            public AnalysisSummary analyze(AgentConfig config, TestClassInfo currentClassInfo, TestMethodInfo currentMethodInfo) {
                return new AnalysisSummary(
                        new MockPlan(List.of(), MockStrategy.MOCKITO, List.of(), List.of()),
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

        PreMergeExecutionRepairLlmClient llmClient = new PreMergeExecutionRepairLlmClient();
        AtomicInteger scratchExecutionCalls = new AtomicInteger();
        InitialGenerationStep generationStep = new InitialGenerationStep(
                logger,
                writer,
                new SkeletonPromptBuilder(),
                analyze,
                new PromptBuilder(),
                llmClient,
                new DiffEngine(writer, logger),
                (projectRoot, testClassFilePath, generatedMethodName) -> new CompileResult(true, List.of(), "", ""),
                (projectRoot, testClassFilePath, generatedMethodName) -> {
                    if (testClassFilePath.getFileName().toString().contains("PreMergeScratch")) {
                        assertEquals(null, generatedMethodName, "Expected whole scratch suite execution for sibling-isolation validation");
                        if (scratchExecutionCalls.getAndIncrement() == 0) {
                            return new ExecuteResult(false,
                                    List.of("com.example.SampleServiceTestPreMergeScratch.existingSiblingShouldStay"),
                                    "",
                                    "synthetic scratch execution failure");
                        }
                    }
                    return new ExecuteResult(true, List.of(), "", "");
                },
                new SnapshotStorage(tempDir, logger),
                registry,
                new CompilationReasoningService(
                        llmClient,
                        new CompilationReasoningPromptBuilder(),
                        new ReasoningResponseParser())
        );

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(tempDir)
                .moduleOption("pipeline.compile.enabled", true)
                .moduleOption("pipeline.execute.enabled", true)
                .moduleOption("pipeline.coverage.enabled", false)
                .moduleOption("pipeline.snapshots.enabled", false)
                .build();

        generationStep.run(config, List.of(classInfo));

        assertEquals(1, llmClient.generationCalls.get(), "Expected scratch execution reasoning to repair without regeneration");
        String generatedTest = Files.readString(testFile);
        assertTrue(generatedTest.contains("SCRATCH-EXEC-REPAIRED"));
        assertTrue(generatedTest.contains("shouldExecutePrimarySnippet"));
        assertFalse(generatedTest.contains("assertTrue(false)"));

        String logs = Files.readString(tempDir.resolve(".agent/logs/pipeline.log"));
        assertTrue(logs.contains("action=START_PRE_MERGE_SCRATCH_EXECUTION_REPAIR"));
        assertTrue(logs.contains("[EXECUTION_REASONING] Starting execution reasoning for method shouldExecutePrimarySnippet"));
        assertTrue(logs.contains("[EXECUTION_REASONING] Decision for shouldExecutePrimarySnippet: APPLY_FIX"));
        assertTrue(logs.contains("action=KEEP_PRE_MERGE_SCRATCH_EXECUTION_REPAIR"));
        assertFalse(logs.contains("reason=PRE_MERGE_SCRATCH_EXECUTION_REPAIR_FAILED"));
        assertFalse(logs.contains("Pre-merge sibling-isolation validation rejected method"));
    }

    @Test
    void conflictingLifecycleHelperCanReuseExistingFixtureWithoutRetry() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/ApplicationTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app;

                import static org.mockito.Mockito.*;
                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.Test;
                import org.mockito.Mock;
                import org.mockito.MockitoAnnotations;
                import com.example.app.service.UserService;
                import com.example.app.service.AuditTrailService;
                import com.example.lib.LibraryComponent;

                public class ApplicationTest {

                    @Mock
                    private UserService userService;

                    @Mock
                    private AuditTrailService auditTrailService;

                    @Mock
                    private LibraryComponent libraryComponent;

                    private Application application;

                    @BeforeEach
                    void setUp() {
                        MockitoAnnotations.openMocks(this);
                        application = new Application(userService, auditTrailService, libraryComponent, new com.example.app.service.FeatureToggleService());
                    }

                    @Test
                    void testStart() {
                        application.start();
                    }
                }
                """);

        TestMethodInfo methodInfo = new TestMethodInfo("public void restart()", "void", "shutdown();");
        TestClassInfo classInfo = new TestClassInfo(
                "Application",
                "ApplicationTest",
                testFile,
                List.of(),
                List.of(methodInfo)
        );

        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerConstructor("Application", "Application(UserService, AuditTrailService, LibraryComponent, FeatureToggleService)");
        registry.registerMethod("Application", "void restart()");

        PipelineLogger logger = new PipelineLogger(tempDir);
        TestClassWriter writer = new TestClassWriter(logger);
        Analyze analyze = new Analyze(registry) {
            @Override
            public AnalysisSummary analyze(AgentConfig config, TestClassInfo currentClassInfo, TestMethodInfo currentMethodInfo) {
                return new AnalysisSummary(
                        new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("userService", "auditTrailService", "libraryComponent"), List.of()),
                        new MethodAnalysisResult(new MethodMetadata("restart", "public void restart()", "void"), List.of(), List.of(), List.of(), List.of()),
                        "{}",
                        Map.of(),
                        new TestTargetContext("com.example.app.Application", "application", true, false),
                        true,
                        List.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(),
                        Map.of("Application", List.of("void restart()")),
                        Set.of(),
                        Set.of()
                );
            }
        };

        LifecycleConflictLlmClient llmClient = new LifecycleConflictLlmClient();
        InitialGenerationStep generationStep = new InitialGenerationStep(
                logger,
                writer,
                new SkeletonPromptBuilder(),
                analyze,
                new PromptBuilder(),
                llmClient,
                new DiffEngine(writer, logger),
                (projectRoot, testClassFile, generatedMethodName) -> new CompileResult(true, List.of(), "", ""),
                (projectRoot, testClassFilePath, generatedMethodName) -> new ExecuteResult(true, List.of(), "", ""),
                new SnapshotStorage(tempDir, logger),
                registry,
                new CompilationReasoningService(
                        llmClient,
                        new CompilationReasoningPromptBuilder(),
                        new ReasoningResponseParser())
        );

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(tempDir)
                .moduleOption("pipeline.compile.enabled", false)
                .moduleOption("pipeline.execute.enabled", false)
                .moduleOption("pipeline.snapshots.enabled", false)
                .build();

        generationStep.run(config, List.of(classInfo));

        assertEquals(1, llmClient.generationCalls.get(), "Expected deterministic lifecycle fixture reuse fallback instead of validation retry");
        String generatedTest = Files.readString(testFile);
        assertTrue(generatedTest.contains("void testRestart_shouldCallShutdownAndReloadThenStart()"));
        assertTrue(generatedTest.contains("application.restart();"));
        assertEquals(1, generatedTest.split("@BeforeEach", -1).length - 1);

        String logs = Files.readString(tempDir.resolve(".agent/logs/pipeline.log"));
        assertTrue(logs.contains("Using deterministic lifecycle-fixture reuse fallback"));
    }

    @Test
    void fieldBasedFixtureCanRejectNewLifecycleHelperAndReuseExistingFixtureWithoutRetry() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                import com.example.app.repository.UserRepository;

                @ExtendWith(MockitoExtension.class)
                public class UserServiceTest {

                    @Mock
                    private UserRepository repository;

                    @Mock
                    private AuditTrailService auditTrailService;

                    @Mock
                    private NotificationService notificationService;

                    @InjectMocks
                    private UserService service;

                    @Test
                    void testCreateUser() {
                        service.createUser("john", "john@example.com");
                    }
                }
                """);

        TestMethodInfo methodInfo = new TestMethodInfo("public User findUser(int index)", "User", "return null;");
        TestClassInfo classInfo = new TestClassInfo(
                "UserService",
                "UserServiceTest",
                testFile,
                List.of(),
                List.of(methodInfo)
        );

        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerConstructor("UserService", "UserService(UserRepository repository, AuditTrailService auditTrailService, NotificationService notificationService)");
        registry.registerMethod("UserService", "User findUser(int index)");

        PipelineLogger logger = new PipelineLogger(tempDir);
        TestClassWriter writer = new TestClassWriter(logger);
        Analyze analyze = new Analyze(registry) {
            @Override
            public AnalysisSummary analyze(AgentConfig config, TestClassInfo currentClassInfo, TestMethodInfo currentMethodInfo) {
                return new AnalysisSummary(
                        new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("repository"), List.of()),
                        new MethodAnalysisResult(new MethodMetadata("findUser", "public User findUser(int index)", "User"), List.of(), List.of(), List.of(), List.of()),
                        "{}",
                        Map.of(),
                        new TestTargetContext("com.example.app.service.UserService", "service", true, false),
                        true,
                        List.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(),
                        Map.of("UserService", List.of("User findUser(int index)")),
                        Set.of(),
                        Set.of()
                );
            }
        };

        FieldFixtureLifecycleConflictLlmClient llmClient = new FieldFixtureLifecycleConflictLlmClient();
        InitialGenerationStep generationStep = new InitialGenerationStep(
                logger,
                writer,
                new SkeletonPromptBuilder(),
                analyze,
                new PromptBuilder(),
                llmClient,
                new DiffEngine(writer, logger),
                (projectRoot, testClassFile, generatedMethodName) -> new CompileResult(true, List.of(), "", ""),
                (projectRoot, testClassFilePath, generatedMethodName) -> new ExecuteResult(true, List.of(), "", ""),
                new SnapshotStorage(tempDir, logger),
                registry,
                new CompilationReasoningService(
                        llmClient,
                        new CompilationReasoningPromptBuilder(),
                        new ReasoningResponseParser())
        );

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(tempDir)
                .moduleOption("pipeline.compile.enabled", false)
                .moduleOption("pipeline.execute.enabled", false)
                .moduleOption("pipeline.snapshots.enabled", false)
                .build();

        generationStep.run(config, List.of(classInfo));

        assertEquals(1, llmClient.generationCalls.get(), "Expected field-based fixture reuse fallback instead of validation retry");
        String generatedTest = Files.readString(testFile);
        assertTrue(generatedTest.contains("void testFindUserValidIndex()"));
        assertTrue(generatedTest.contains("service.findUser(1);"));
        assertEquals(0, generatedTest.split("@BeforeEach", -1).length - 1);

        String logs = Files.readString(tempDir.resolve(".agent/logs/pipeline.log"));
        assertTrue(logs.contains("Using deterministic lifecycle-fixture reuse fallback"));
    }

    @Test
    void lifecycleReuseFallbackCanChainIntoInventedStateMutatorFallback() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.Test;
                import org.mockito.Mock;
                import org.mockito.MockitoAnnotations;
                import com.example.app.repository.UserRepository;

                public class UserServiceTest {

                    @Mock
                    private UserRepository repository;

                    private UserService service;

                    @BeforeEach
                    void setUp() {
                        MockitoAnnotations.openMocks(this);
                        service = new UserService(repository, new AuditTrailService(), org.mockito.Mockito.mock(NotificationService.class));
                    }

                    @Test
                    void testCreateUser() {
                        service.createUser("john", "john@example.com");
                    }
                }
                """);

        TestMethodInfo methodInfo = new TestMethodInfo("public List<String> activeUsernames()", "List<String>", "return java.util.List.of();");
        TestClassInfo classInfo = new TestClassInfo(
                "UserService",
                "UserServiceTest",
                testFile,
                List.of(),
                List.of(methodInfo)
        );

        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerConstructor("UserService", "UserService(UserRepository repository, AuditTrailService auditTrailService, NotificationService notificationService)");
        registry.registerConstructor("User", "User(String username, String email)");
        registry.registerMethod("UserService", "List<String> activeUsernames()");
        registry.registerMethod("UserRepository", "List<User> findAll()");
        registry.registerMethod("User", "String getUsername()");
        registry.registerMethod("User", "boolean isActive()");
        registry.registerMethod("User", "void activate()");
        registry.registerMethod("User", "void deactivate()");

        PipelineLogger logger = new PipelineLogger(tempDir);
        TestClassWriter writer = new TestClassWriter(logger);
        Analyze analyze = new Analyze(registry) {
            @Override
            public AnalysisSummary analyze(AgentConfig config, TestClassInfo currentClassInfo, TestMethodInfo currentMethodInfo) {
                return new AnalysisSummary(
                        new MockPlan(List.of(new MockTarget("UserRepository", "repository")),
                                MockStrategy.MOCKITO,
                                List.of("repository"),
                                List.of()),
                        new MethodAnalysisResult(
                                new MethodMetadata("activeUsernames", "public List<String> activeUsernames()", "List<String>"),
                                List.of(new DependencyInfo(
                                        "UserRepository",
                                        "repository",
                                        MockType.FIELD,
                                        "field",
                                        false,
                                        false,
                                        0)),
                                List.of(),
                                List.of(),
                                List.of()),
                        "{}",
                        Map.of(),
                        new TestTargetContext("com.example.app.service.UserService", "service", true, false),
                        true,
                        List.of(),
                        Set.of(),
                        Set.of(),
                        Map.of("User", List.of(new com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata(
                                "User(String username, String email)",
                                List.of(
                                        new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata("username", "String", List.of()),
                                        new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata("email", "String", List.of())
                                ))),
                                "UserService", List.of(new com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata(
                                        "UserService(UserRepository repository, AuditTrailService auditTrailService, NotificationService notificationService)",
                                        List.of(
                                                new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata("repository", "UserRepository", List.of()),
                                                new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata("auditTrailService", "AuditTrailService", List.of()),
                                                new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata("notificationService", "NotificationService", List.of())
                                        )))),
                        Map.of(
                                "UserService", List.of("List<String> activeUsernames()"),
                                "UserRepository", List.of("List<User> findAll()"),
                                "User", List.of("String getUsername()", "boolean isActive()", "void activate()", "void deactivate()")
                        ),
                        Set.of(),
                        Set.of()
                );
            }
        };

        LifecycleThenInventedSetterLlmClient llmClient = new LifecycleThenInventedSetterLlmClient();
        InitialGenerationStep generationStep = new InitialGenerationStep(
                logger,
                writer,
                new SkeletonPromptBuilder(),
                analyze,
                new PromptBuilder(),
                llmClient,
                new DiffEngine(writer, logger),
                (projectRoot, testClassFile, generatedMethodName) -> new CompileResult(true, List.of(), "", ""),
                (projectRoot, testClassFilePath, generatedMethodName) -> new ExecuteResult(true, List.of(), "", ""),
                new SnapshotStorage(tempDir, logger),
                registry,
                new CompilationReasoningService(
                        llmClient,
                        new CompilationReasoningPromptBuilder(),
                        new ReasoningResponseParser())
        );

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(tempDir)
                .moduleOption("pipeline.compile.enabled", false)
                .moduleOption("pipeline.execute.enabled", false)
                .moduleOption("pipeline.snapshots.enabled", false)
                .build();

        generationStep.run(config, List.of(classInfo));

        assertEquals(1, llmClient.generationCalls.get(), "Expected chained deterministic fallbacks instead of validation retry");
        String generatedTest = Files.readString(testFile);
        assertFalse(generatedTest.contains("activeUser.setActive("));
        assertTrue(generatedTest.contains("inactiveUser.deactivate();"));
        assertEquals(1, generatedTest.split("@BeforeEach", -1).length - 1);

        String logs = Files.readString(tempDir.resolve(".agent/logs/pipeline.log"));
        assertTrue(logs.contains("Using chained lifecycle + constructor-state fallback")
                || logs.contains("Using chained lifecycle + invented-state-mutator fallback"));
    }

    @Test
    void missingConstructorStateCanUseDeterministicFallbackWithoutValidationRetry() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        TestMethodInfo methodInfo = new TestMethodInfo("public List<String> activeUsernames()", "List<String>", "return java.util.List.of();");
        TestClassInfo classInfo = new TestClassInfo(
                "UserService",
                "UserServiceTest",
                testFile,
                List.of(),
                List.of(methodInfo)
        );

        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerConstructor("UserService", "UserService(UserRepository repository, AuditTrailService auditTrailService, NotificationService notificationService)");
        registry.registerConstructor("User", "User(String username, String email)");
        registry.registerMethod("UserService", "List<String> activeUsernames()");
        registry.registerMethod("UserRepository", "List<User> findAll()");
        registry.registerMethod("User", "String getUsername()");
        registry.registerMethod("User", "boolean isActive()");
        registry.registerMethod("User", "void deactivate()");

        PipelineLogger logger = new PipelineLogger(tempDir);
        TestClassWriter writer = new TestClassWriter(logger);
        Analyze analyze = new Analyze(registry) {
            @Override
            public AnalysisSummary analyze(AgentConfig config, TestClassInfo currentClassInfo, TestMethodInfo currentMethodInfo) {
                return new AnalysisSummary(
                        new MockPlan(List.of(new MockTarget("UserRepository", "repository")),
                                MockStrategy.MOCKITO,
                                List.of("repository"),
                                List.of()),
                        new MethodAnalysisResult(
                                new MethodMetadata("activeUsernames", "public List<String> activeUsernames()", "List<String>"),
                                List.of(new DependencyInfo(
                                        "UserRepository",
                                        "repository",
                                        MockType.FIELD,
                                        "field",
                                        false,
                                        false,
                                        0)),
                                List.of(),
                                List.of(),
                                List.of()),
                        "{}",
                        Map.of(),
                        new TestTargetContext("com.example.app.service.UserService", "service", true, false),
                        true,
                        List.of(),
                        Set.of(),
                        Set.of(),
                        Map.of("User", List.of(new com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata(
                                "User(String username, String email)",
                                List.of(
                                        new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata("username", "String", List.of()),
                                        new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata("email", "String", List.of())
                                ))),
                                "UserService", List.of(new com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata(
                                        "UserService(UserRepository repository, AuditTrailService auditTrailService, NotificationService notificationService)",
                                        List.of(
                                                new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata("repository", "UserRepository", List.of()),
                                                new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata("auditTrailService", "AuditTrailService", List.of()),
                                                new com.gigachat.unit.tests.generator.analyzer.ParameterMetadata("notificationService", "NotificationService", List.of())
                                        )))),
                        Map.of(
                                "UserService", List.of("List<String> activeUsernames()"),
                                "UserRepository", List.of("List<User> findAll()"),
                                "User", List.of("String getUsername()", "boolean isActive()", "void deactivate()")
                        ),
                        Set.of(),
                        Set.of()
                );
            }
        };

        ConstructorStateFallbackLlmClient llmClient = new ConstructorStateFallbackLlmClient();
        InitialGenerationStep generationStep = new InitialGenerationStep(
                logger,
                writer,
                new SkeletonPromptBuilder(),
                analyze,
                new PromptBuilder(),
                llmClient,
                new DiffEngine(writer, logger),
                (projectRoot, testClassFile, generatedMethodName) -> new CompileResult(true, List.of(), "", ""),
                (projectRoot, testClassFilePath, generatedMethodName) -> new ExecuteResult(true, List.of(), "", ""),
                new SnapshotStorage(tempDir, logger),
                registry,
                new CompilationReasoningService(
                        llmClient,
                        new CompilationReasoningPromptBuilder(),
                        new ReasoningResponseParser())
        );

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(tempDir)
                .moduleOption("pipeline.compile.enabled", false)
                .moduleOption("pipeline.execute.enabled", false)
                .moduleOption("pipeline.snapshots.enabled", false)
                .build();

        generationStep.run(config, List.of(classInfo));

        assertEquals(1, llmClient.generationCalls.get(), "Expected deterministic constructor-state fallback instead of validation retry");
        String generatedTest = Files.readString(testFile);
        assertTrue(generatedTest.contains("var generatedUser1 = new User(\"Alice\", \"alice@example.com\");"));
        assertTrue(generatedTest.contains("generatedUser2.deactivate();"));
        assertFalse(generatedTest.contains("new User(\"Alice\", \"alice@example.com\", true)"));

        String logs = Files.readString(tempDir.resolve(".agent/logs/pipeline.log"));
        assertTrue(logs.contains("Using deterministic constructor-state fallback"));
    }

    private static class LifecycleThenInventedSetterLlmClient implements LlmClient {

        private final AtomicInteger generationCalls = new AtomicInteger();

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt, TestClassInfo classInfo, TestMethodInfo methodInfo, MockPlan plan) {
            generationCalls.incrementAndGet();
            return new GeneratedTestSnippet(
                    "UserServiceTest",
                    "testActiveUsernames_ReturnsFilteredActiveUsers",
                    """
                    @Test
                    public void testActiveUsernames_ReturnsFilteredActiveUsers() {
                        User activeUser = new User("John", "john@example.com");
                        activeUser.setActive(true);
                        User inactiveUser = new User("Jane", "jane@example.com");
                        inactiveUser.setActive(false);
                        List<User> users = Arrays.asList(activeUser, inactiveUser);
                        when(repository.findAll()).thenReturn(users);
                        List<String> result = service.activeUsernames();
                        assertEquals(Arrays.asList("John"), result);
                    }
                    """,
                    List.of(
                            "import static org.junit.jupiter.api.Assertions.*;",
                            "import static org.mockito.Mockito.*;",
                            "import com.example.app.model.User;",
                            "import com.example.app.repository.UserRepository;",
                            "import org.junit.jupiter.api.BeforeEach;",
                            "import org.junit.jupiter.api.Test;",
                            "import org.mockito.Mock;",
                            "import org.mockito.MockitoAnnotations;",
                            "import java.util.Arrays;",
                            "import java.util.List;"
                    ),
                    List.of(),
                    List.of(
                            "@Mock\nprivate UserRepository repository;",
                            "private UserService service;"
                    ),
                    List.of("""
                            @BeforeEach
                            public void setUp() {
                                MockitoAnnotations.openMocks(this);
                                service = new UserService(repository, new AuditTrailService(), mock(NotificationService.class));
                            }
                            """),
                    """
                    package com.example.app.service;

                    import static org.junit.jupiter.api.Assertions.*;
                    import static org.mockito.Mockito.*;
                    import com.example.app.model.User;
                    import com.example.app.repository.UserRepository;
                    import org.junit.jupiter.api.BeforeEach;
                    import org.junit.jupiter.api.Test;
                    import org.mockito.Mock;
                    import org.mockito.MockitoAnnotations;
                    import java.util.Arrays;
                    import java.util.List;

                    public class UserServiceTest {

                        @Mock
                        private UserRepository repository;

                        private UserService service;

                        @BeforeEach
                        public void setUp() {
                            MockitoAnnotations.openMocks(this);
                            service = new UserService(repository, new AuditTrailService(), mock(NotificationService.class));
                        }

                        @Test
                        public void testActiveUsernames_ReturnsFilteredActiveUsers() {
                            User activeUser = new User("John", "john@example.com");
                            activeUser.setActive(true);
                            User inactiveUser = new User("Jane", "jane@example.com");
                            inactiveUser.setActive(false);
                            List<User> users = Arrays.asList(activeUser, inactiveUser);
                            when(repository.findAll()).thenReturn(users);
                            List<String> result = service.activeUsernames();
                            assertEquals(Arrays.asList("John"), result);
                        }
                    }
                    """
            );
        }

        @Override
        public String requestStructuredResponse(String prompt) {
            return """
                    {"decision":"STOP","actions":[],"memory_updates":{}}
                    """;
        }
    }

    private static class CountingLlmClient implements LlmClient {
        private final AtomicInteger generationCalls = new AtomicInteger();
        private final AtomicInteger executionReasoningCalls = new AtomicInteger();

        @Override
        public String requestStructuredResponse(String prompt) {
            executionReasoningCalls.incrementAndGet();
            return """
                    {"decision":"STOP","actions":[],"memory_updates":{}}
                    """;
        }

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        MockPlan plan) {
            generationCalls.incrementAndGet();
            return new GeneratedTestSnippet(
                    classInfo.getTestClassName(),
                    "shouldExecutePerform",
                    """
                            @Test
                            void shouldExecutePerform() {
                                sampleService.perform();
                                org.junit.jupiter.api.Assertions.assertTrue(true);
                            }
                            """,
                    List.of("import org.junit.jupiter.api.Test;", "import org.junit.jupiter.api.Assertions;")
            );
        }
    }

    private static class ConstructorStateFallbackLlmClient implements LlmClient {

        private final AtomicInteger generationCalls = new AtomicInteger();

        @Override
        public String requestStructuredResponse(String prompt) {
            return """
                    {"decision":"STOP","actions":[],"memory_updates":{}}
                    """;
        }

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        MockPlan plan) {
            generationCalls.incrementAndGet();
            return new GeneratedTestSnippet(
                    "UserServiceTest",
                    "testActiveUsernamesReturnsFilteredActiveUsers",
                    """
                            @Test
                            void testActiveUsernamesReturnsFilteredActiveUsers() {
                                List<User> users = new ArrayList<>();
                                users.add(new User("Alice", "alice@example.com", true));
                                users.add(new User("Bob", "bob@example.com", false));
                                when(repository.findAll()).thenReturn(users);
                                List<String> result = service.activeUsernames();
                                assertEquals(List.of("Alice"), result);
                            }
                            """,
                    List.of(
                            "import static org.junit.jupiter.api.Assertions.*;",
                            "import static org.mockito.Mockito.*;",
                            "import com.example.app.model.User;",
                            "import com.example.app.repository.UserRepository;",
                            "import org.junit.jupiter.api.Test;",
                            "import org.mockito.Mock;",
                            "import java.util.ArrayList;",
                            "import java.util.List;"
                    )
            );
        }
    }

    private static class RetryingGenerationLlmClient implements LlmClient {
        private final AtomicInteger generationCalls = new AtomicInteger();

        @Override
        public String requestStructuredResponse(String prompt) {
            return """
                    {"decision":"STOP","actions":[],"memory_updates":{}}
                    """;
        }

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        MockPlan plan) {
            if (generationCalls.getAndIncrement() == 0) {
                return new GeneratedTestSnippet(
                        classInfo.getTestClassName(),
                        "shouldExecutePerform",
                        """
                                @Test
                                void shouldExecutePerform() {
                                    // TODO placeholder
                                    org.junit.jupiter.api.Assertions.assertTrue(true);
                                }
                                """,
                        List.of("import org.junit.jupiter.api.Test;", "import org.junit.jupiter.api.Assertions;")
                );
            }
            return new GeneratedTestSnippet(
                    classInfo.getTestClassName(),
                    "shouldExecutePerform",
                    """
                            @Test
                            void shouldExecutePerform() {
                                sampleService.perform();
                                org.junit.jupiter.api.Assertions.assertTrue(true);
                            }
                            """,
                    List.of("import org.junit.jupiter.api.Test;", "import org.junit.jupiter.api.Assertions;")
            );
        }
    }

    private static class RetryingVoidWhenLlmClient implements LlmClient {
        private final AtomicInteger generationCalls = new AtomicInteger();

        @Override
        public String requestStructuredResponse(String prompt) {
            return """
                    {"decision":"STOP","actions":[],"memory_updates":{}}
                    """;
        }

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        MockPlan plan) {
            if (generationCalls.getAndIncrement() == 0) {
                return new GeneratedTestSnippet(
                        classInfo.getTestClassName(),
                        "shouldExecutePerform",
                        """
                                @Test
                                void shouldExecutePerform() {
                                    NotificationService notificationService = mock(NotificationService.class);
                                    SampleService sampleService = new SampleService(notificationService);
                                    when(notificationService.sendWelcome(any())).thenReturn(true);
                                    sampleService.perform();
                                    org.junit.jupiter.api.Assertions.assertTrue(true);
                                }
                                """,
                        List.of(
                                "import org.junit.jupiter.api.Test;",
                                "import org.junit.jupiter.api.Assertions;",
                                "import static org.mockito.Mockito.*;",
                                "import static org.mockito.ArgumentMatchers.any;"
                        )
                );
            }
            return new GeneratedTestSnippet(
                    classInfo.getTestClassName(),
                    "shouldExecutePerform",
                    """
                            @Test
                            void shouldExecutePerform() {
                                NotificationService notificationService = mock(NotificationService.class);
                                SampleService sampleService = new SampleService(notificationService);
                                doNothing().when(notificationService).sendWelcome(any());
                                sampleService.perform();
                                org.junit.jupiter.api.Assertions.assertTrue(true);
                            }
                            """,
                    List.of(
                            "import org.junit.jupiter.api.Test;",
                            "import org.junit.jupiter.api.Assertions;",
                            "import static org.mockito.Mockito.*;",
                            "import static org.mockito.ArgumentMatchers.any;"
                    )
            );
        }
    }

    private static class RetryingVoidWhenThenCallRealMethodLlmClient implements LlmClient {
        private final AtomicInteger generationCalls = new AtomicInteger();

        @Override
        public String requestStructuredResponse(String prompt) {
            return """
                    {"decision":"STOP","actions":[],"memory_updates":{}}
                    """;
        }

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        MockPlan plan) {
            if (generationCalls.getAndIncrement() == 0) {
                return new GeneratedTestSnippet(
                        classInfo.getTestClassName(),
                        "shouldExecutePerform",
                        """
                                @Test
                                void shouldExecutePerform() {
                                    NotificationService notificationService = mock(NotificationService.class);
                                    SampleService sampleService = new SampleService(notificationService);
                                    when(notificationService.sendWelcome(any())).thenCallRealMethod();
                                    sampleService.perform();
                                    org.junit.jupiter.api.Assertions.assertTrue(true);
                                }
                                """,
                        List.of(
                                "import org.junit.jupiter.api.Test;",
                                "import org.junit.jupiter.api.Assertions;",
                                "import static org.mockito.Mockito.*;",
                                "import static org.mockito.ArgumentMatchers.any;"
                        )
                );
            }
            return new GeneratedTestSnippet(
                    classInfo.getTestClassName(),
                    "shouldExecutePerform",
                    """
                            @Test
                            void shouldExecutePerform() {
                                NotificationService notificationService = mock(NotificationService.class);
                                SampleService sampleService = new SampleService(notificationService);
                                doNothing().when(notificationService).sendWelcome(any());
                                sampleService.perform();
                                org.junit.jupiter.api.Assertions.assertTrue(true);
                            }
                            """,
                    List.of(
                            "import org.junit.jupiter.api.Test;",
                            "import org.junit.jupiter.api.Assertions;",
                            "import static org.mockito.Mockito.*;",
                            "import static org.mockito.ArgumentMatchers.any;"
                    )
            );
        }
    }

    private static class RetryingHelperVoidWhenThenCallRealMethodLlmClient implements LlmClient {
        private final AtomicInteger generationCalls = new AtomicInteger();

        @Override
        public String requestStructuredResponse(String prompt) {
            return """
                    {"decision":"STOP","actions":[],"memory_updates":{}}
                    """;
        }

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        MockPlan plan) {
            if (generationCalls.getAndIncrement() == 0) {
                return new GeneratedTestSnippet(
                        classInfo.getTestClassName(),
                        "shouldExecutePerform",
                        """
                                @Test
                                void shouldExecutePerform() {
                                    sampleService.perform();
                                    org.junit.jupiter.api.Assertions.assertTrue(true);
                                }
                                """,
                        List.of(
                                "import org.junit.jupiter.api.Test;",
                                "import org.junit.jupiter.api.Assertions;",
                                "import org.junit.jupiter.api.BeforeEach;",
                                "import org.mockito.Mock;",
                                "import org.mockito.MockitoAnnotations;",
                                "import static org.mockito.Mockito.*;",
                                "import static org.mockito.ArgumentMatchers.any;"
                        ),
                        List.of(),
                        List.of(
                                "@Mock NotificationService notificationService;",
                                "private SampleService sampleService;"
                        ),
                        List.of(
                                """
                                @BeforeEach
                                void setUp() {
                                    MockitoAnnotations.openMocks(this);
                                    sampleService = new SampleService(notificationService);
                                }
                                """,
                                """
                                @Test
                                void shouldConfigureNotificationWelcomePath() {
                                    when(notificationService.sendWelcome(any())).thenCallRealMethod();
                                    sampleService.perform();
                                }
                                """
                        ),
                        ""
                );
            }
            return new GeneratedTestSnippet(
                    classInfo.getTestClassName(),
                    "shouldExecutePerform",
                    """
                            @Test
                            void shouldExecutePerform() {
                                sampleService.perform();
                                org.junit.jupiter.api.Assertions.assertTrue(true);
                            }
                            """,
                    List.of(
                            "import org.junit.jupiter.api.Test;",
                            "import org.junit.jupiter.api.Assertions;",
                            "import org.junit.jupiter.api.BeforeEach;",
                            "import org.mockito.Mock;",
                            "import org.mockito.MockitoAnnotations;",
                            "import static org.mockito.Mockito.*;",
                            "import static org.mockito.ArgumentMatchers.any;"
                    ),
                    List.of(),
                    List.of(
                            "@Mock NotificationService notificationService;",
                            "private SampleService sampleService;"
                    ),
                    List.of(
                            """
                            @BeforeEach
                            void setUp() {
                                MockitoAnnotations.openMocks(this);
                                sampleService = new SampleService(notificationService);
                            }
                            """,
                            """
                            @Test
                            void shouldConfigureNotificationWelcomePath() {
                                doNothing().when(notificationService).sendWelcome(any());
                                sampleService.perform();
                            }
                            """
                    ),
                    ""
            );
        }
    }

    private static class RetryingVoidMutatorValueLlmClient implements LlmClient {
        private final AtomicInteger generationCalls = new AtomicInteger();

        @Override
        public String requestStructuredResponse(String prompt) {
            return """
                    {"decision":"STOP","actions":[],"memory_updates":{}}
                    """;
        }

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        MockPlan plan) {
            if (generationCalls.getAndIncrement() == 0) {
                return new GeneratedTestSnippet(
                        classInfo.getTestClassName(),
                        "testActiveUsernamesReturnsFilteredUsernames",
                        """
                                @Test
                                void testActiveUsernamesReturnsFilteredUsernames() {
                                    UserRepository repository = mock(UserRepository.class);
                                    UserService service = new UserService(repository);
                                    List<User> users = new ArrayList<>();
                                    users.add(new User("Alice", "alice@example.com"));
                                    users.add(new User("Bob", "bob@example.com").deactivate());
                                    when(repository.findAll()).thenReturn(users);
                                    List<String> result = service.activeUsernames();
                                    assertEquals(List.of("Alice"), result);
                                }
                                """,
                        List.of(
                                "import org.junit.jupiter.api.Test;",
                                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                                "import static org.mockito.Mockito.*;",
                                "import java.util.ArrayList;",
                                "import java.util.List;"
                        )
                );
            }
            return new GeneratedTestSnippet(
                    classInfo.getTestClassName(),
                    "testActiveUsernamesReturnsFilteredUsernames",
                    """
                            @Test
                            void testActiveUsernamesReturnsFilteredUsernames() {
                                UserRepository repository = mock(UserRepository.class);
                                UserService service = new UserService(repository);
                                List<User> users = new ArrayList<>();
                                users.add(new User("Alice", "alice@example.com"));
                                User inactiveUser = new User("Bob", "bob@example.com");
                                inactiveUser.deactivate();
                                users.add(inactiveUser);
                                when(repository.findAll()).thenReturn(users);
                                List<String> result = service.activeUsernames();
                                assertEquals(List.of("Alice"), result);
                            }
                            """,
                    List.of(
                            "import org.junit.jupiter.api.Test;",
                            "import static org.junit.jupiter.api.Assertions.assertEquals;",
                            "import static org.mockito.Mockito.*;",
                            "import java.util.ArrayList;",
                            "import java.util.List;"
                    )
            );
        }
    }

    private static class SingleInventedSetterLlmClient implements LlmClient {
        private final AtomicInteger generationCalls = new AtomicInteger();

        @Override
        public String requestStructuredResponse(String prompt) {
            return """
                    {"decision":"STOP","actions":[],"memory_updates":{}}
                    """;
        }

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        MockPlan plan) {
            generationCalls.incrementAndGet();
            return new GeneratedTestSnippet(
                    classInfo.getTestClassName(),
                    "testActiveUsernamesReturnsActiveUsers",
                    """
                            @Test
                            void testActiveUsernamesReturnsActiveUsers() {
                                UserRepository repository = mock(UserRepository.class);
                                UserService service = new UserService(repository);
                                User activeUser = new User("Alice", "alice@example.com");
                                activeUser.setActive(true);
                                User inactiveUser = new User("Bob", "bob@example.com");
                                inactiveUser.setActive(false);
                                when(repository.findAll()).thenReturn(List.of(activeUser, inactiveUser));
                                List<String> result = service.activeUsernames();
                                assertEquals(List.of("Alice"), result);
                            }
                            """,
                    List.of(
                            "import org.junit.jupiter.api.Test;",
                            "import static org.junit.jupiter.api.Assertions.assertEquals;",
                            "import static org.mockito.Mockito.*;",
                            "import java.util.List;"
                    )
            );
        }
    }

    private static class PreMergeCompileRepairLlmClient implements LlmClient {
        private final AtomicInteger generationCalls = new AtomicInteger();
        private final AtomicInteger reasoningCalls = new AtomicInteger();

        @Override
        public String requestStructuredResponse(String prompt) {
            reasoningCalls.incrementAndGet();
            return """
                    {
                          "decision":"APPLY_FIX",
                          "actions":[
                        {
                          "type":"APPLY_RECIPE",
                          "args":{"recipeId":"COLLAPSE_CONSECUTIVE_BEFOREEACH_ANNOTATIONS"}
                        }
                      ],
                      "memory_updates":{"knownMissingSymbols":[],"appliedFixSignatures":[],"contextCache":{}}
                    }
                    """;
        }

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        MockPlan plan) {
            if (generationCalls.getAndIncrement() == 0) {
                return new GeneratedTestSnippet(
                        classInfo.getTestClassName(),
                        "shouldExecutePerform",
                        """
                                @Test
                                void shouldExecutePerform() {
                                    sampleService.perform();
                                    org.junit.jupiter.api.Assertions.assertTrue(true); // FIRST-SCRATCH-REPAIRED
                                }
                                """,
                        List.of(
                                "import org.junit.jupiter.api.Test;",
                                "import org.junit.jupiter.api.Assertions;",
                                "import org.junit.jupiter.api.BeforeEach;"
                        ),
                        List.of(),
                        List.of("private SampleService sampleService;"),
                        List.of("""
                                @BeforeEach
                                @BeforeEach
                                void setUp() {
                                    sampleService = new SampleService();
                                }
                                """),
                        ""
                );
            }
            throw new AssertionError("Generation retry should not be reached when scratch compile reasoning succeeds");
        }
    }

    private static class PreMergeExecutionRepairLlmClient implements LlmClient {
        private final AtomicInteger generationCalls = new AtomicInteger();
        private final AtomicInteger reasoningCalls = new AtomicInteger();

        @Override
        public String requestStructuredResponse(String prompt) {
            reasoningCalls.incrementAndGet();
            return """
                    {
                      "decision":"APPLY_FIX",
                      "actions":[
                        {
                          "type":"APPLY_PATCH",
                          "args":{
                            "path":"src/test/java/com/example/SampleServiceTestPreMergeScratch.java",
                            "patch":"@@ -1,1 +1,1 @@\\n-        org.junit.jupiter.api.Assertions.assertTrue(false);\\n+        org.junit.jupiter.api.Assertions.assertTrue(true); // SCRATCH-EXEC-REPAIRED\\n"
                          }
                        }
                      ],
                      "memory_updates":{"knownMissingSymbols":[],"appliedFixSignatures":[],"contextCache":{}}
                    }
                    """;
        }

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        MockPlan plan) {
            if (generationCalls.getAndIncrement() == 0) {
                return new GeneratedTestSnippet(
                        classInfo.getTestClassName(),
                        "shouldExecutePrimarySnippet",
                        """
                                @Test
                                void shouldExecutePrimarySnippet() {
                                    sampleService.perform();
                                    org.junit.jupiter.api.Assertions.assertTrue(true);
                                }
                                """,
                        List.of(
                                "import org.junit.jupiter.api.Test;",
                                "import org.junit.jupiter.api.Assertions;"
                        ),
                        List.of(),
                        List.of("private SampleService sampleService;"),
                        List.of(),
                        ""
                );
            }
            throw new AssertionError("Generation retry should not be reached when scratch execution reasoning succeeds");
        }
    }

    private static class LifecycleConflictLlmClient implements LlmClient {

        private final AtomicInteger generationCalls = new AtomicInteger();

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt, TestClassInfo classInfo, TestMethodInfo methodInfo, MockPlan plan) {
            generationCalls.incrementAndGet();
            return new GeneratedTestSnippet(
                    "ApplicationTest",
                    "testRestart_shouldCallShutdownAndReloadThenStart",
                    """
                    @Test
                    void testRestart_shouldCallShutdownAndReloadThenStart() {
                        application.restart();
                        verify(libraryComponent).reload();
                        verify(auditTrailService).recordEvent("Application restarted");
                    }
                    """,
                    List.of(
                            "import static org.mockito.Mockito.*;",
                            "import org.junit.jupiter.api.BeforeEach;",
                            "import org.junit.jupiter.api.Test;",
                            "import org.mockito.Mock;",
                            "import org.mockito.MockitoAnnotations;",
                            "import com.example.app.service.UserService;",
                            "import com.example.app.service.AuditTrailService;",
                            "import com.example.lib.LibraryComponent;",
                            "import com.example.app.service.FeatureToggleService;"
                    ),
                    List.of(),
                    List.of(
                            "@Mock\nprivate UserService userService;",
                            "@Mock\nprivate AuditTrailService auditTrailService;",
                            "@Mock\nprivate LibraryComponent libraryComponent;",
                            "@Mock\nprivate FeatureToggleService featureToggleService;",
                            "private Application application;"
                    ),
                    List.of("""
                            @BeforeEach
                            void setUp() {
                                MockitoAnnotations.openMocks(this);
                                application = new Application(userService, auditTrailService, libraryComponent, featureToggleService);
                            }
                            """),
                    """
                    package com.example.app;

                    import static org.mockito.Mockito.*;
                    import org.junit.jupiter.api.BeforeEach;
                    import org.junit.jupiter.api.Test;
                    import org.mockito.Mock;
                    import org.mockito.MockitoAnnotations;
                    import com.example.app.service.UserService;
                    import com.example.app.service.AuditTrailService;
                    import com.example.lib.LibraryComponent;
                    import com.example.app.service.FeatureToggleService;

                    public class ApplicationTest {

                        @Mock
                        private UserService userService;

                        @Mock
                        private AuditTrailService auditTrailService;

                        @Mock
                        private LibraryComponent libraryComponent;

                        @Mock
                        private FeatureToggleService featureToggleService;

                        private Application application;

                        @BeforeEach
                        void setUp() {
                            MockitoAnnotations.openMocks(this);
                            application = new Application(userService, auditTrailService, libraryComponent, featureToggleService);
                        }

                        @Test
                        void testRestart_shouldCallShutdownAndReloadThenStart() {
                            application.restart();
                            verify(libraryComponent).reload();
                            verify(auditTrailService).recordEvent("Application restarted");
                        }
                    }
                    """
            );
        }

        @Override
        public String requestStructuredResponse(String prompt) {
            return """
                    {"decision":"STOP","actions":[],"memory_updates":{}}
                    """;
        }
    }

    private static class FieldFixtureLifecycleConflictLlmClient implements LlmClient {

        private final AtomicInteger generationCalls = new AtomicInteger();

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt, TestClassInfo classInfo, TestMethodInfo methodInfo, MockPlan plan) {
            generationCalls.incrementAndGet();
            return new GeneratedTestSnippet(
                    "UserServiceTest",
                    "testFindUserValidIndex",
                    """
                    @Test
                    void testFindUserValidIndex() {
                        when(repository.findAll()).thenReturn(java.util.List.of(new com.example.app.model.User("user1", "user1@example.com")));
                        service.findUser(1);
                    }
                    """,
                    List.of(
                            "import static org.mockito.Mockito.*;",
                            "import org.junit.jupiter.api.BeforeEach;",
                            "import org.junit.jupiter.api.Test;",
                            "import org.mockito.Mock;",
                            "import org.mockito.MockitoAnnotations;",
                            "import com.example.app.repository.UserRepository;"
                    ),
                    List.of(),
                    List.of(
                            "@Mock\nprivate UserRepository repository;",
                            "private UserService service;"
                    ),
                    List.of("""
                            @BeforeEach
                            void setUp() {
                                MockitoAnnotations.openMocks(this);
                                service = new UserService(repository, new AuditTrailService(), mock(NotificationService.class));
                            }
                            """),
                    """
                    package com.example.app.service;

                    import static org.mockito.Mockito.*;
                    import org.junit.jupiter.api.BeforeEach;
                    import org.junit.jupiter.api.Test;
                    import org.mockito.Mock;
                    import org.mockito.MockitoAnnotations;
                    import com.example.app.repository.UserRepository;

                    public class UserServiceTest {

                        @Mock
                        private UserRepository repository;

                        private UserService service;

                        @BeforeEach
                        void setUp() {
                            MockitoAnnotations.openMocks(this);
                            service = new UserService(repository, new AuditTrailService(), mock(NotificationService.class));
                        }

                        @Test
                        void testFindUserValidIndex() {
                            when(repository.findAll()).thenReturn(java.util.List.of(new com.example.app.model.User("user1", "user1@example.com")));
                            service.findUser(1);
                        }
                    }
                    """
            );
        }

        @Override
        public String requestStructuredResponse(String prompt) {
            return """
                    {"decision":"STOP","actions":[],"memory_updates":{}}
                    """;
        }
    }

    private static class ReimplementationLlmClient implements LlmClient {

        private final AtomicInteger generationCalls = new AtomicInteger();

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt, TestClassInfo classInfo, TestMethodInfo methodInfo, MockPlan plan) {
            generationCalls.incrementAndGet();
            return new GeneratedTestSnippet(
                    classInfo.getTestClassName(),
                    "shouldAttemptPerform",
                    """
                    @Test
                    void shouldAttemptPerform() {
                        SampleService sampleService = new SampleService();
                        sampleService.perform();
                    }
                    """,
                    List.of("import org.junit.jupiter.api.Test;"),
                    List.of(),
                    List.of(),
                    List.of(),
                    """
                    package com.example;

                    import org.junit.jupiter.api.Test;

                    public class SampleServiceTest {

                        @Test
                        void shouldAttemptPerform() {
                            SampleService sampleService = new SampleService();
                            sampleService.perform();
                        }
                    }

                    class SampleService {
                        void perform() {
                            // Reimplemented SUT method instead of using the production class.
                        }
                    }
                    """
            );
        }

        @Override
        public String requestStructuredResponse(String prompt) {
            return """
                    {"decision":"STOP","actions":[],"memory_updates":{}}
                    """;
        }
    }
}
