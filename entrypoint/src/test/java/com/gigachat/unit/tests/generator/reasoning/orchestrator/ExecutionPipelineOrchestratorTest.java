package com.gigachat.unit.tests.generator.reasoning.orchestrator;

import com.gigachat.unit.tests.generator.cleaner.parser.ExecutionFailureLogParser;
import com.gigachat.unit.tests.generator.cleaner.parser.ExecutionFailureParseResult;
import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.llm.LlmClient;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder;
import com.gigachat.unit.tests.generator.reasoning.service.CompilationReasoningService;
import com.gigachat.unit.tests.generator.reasoning.service.ExecutionFailureContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.ProjectContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.ReasoningResponseParser;
import com.gigachat.unit.tests.generator.reasoning.service.SourceFileEditor;
import com.gigachat.unit.tests.generator.reasoning.service.ToolActionExecutor;
import com.gigachat.unit.tests.generator.report.parser.ExecutionReportParser;
import com.gigachat.unit.tests.generator.report.parser.TestReportFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPipelineOrchestratorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAllowFinalFollowUpAfterUsefulContextWhenExecutionBudgetReachesZero() throws Exception {
        Path projectRoot = tempDir;
        Path sutFile = projectRoot.resolve("src/main/java/com/example/app/service/RuntimeContextService.java");
        Files.createDirectories(sutFile.getParent());
        Files.writeString(sutFile, """
                package com.example.app.service;

                public class RuntimeContextService {
                    public boolean markReady() {
                        return true;
                    }
                }
                """);

        Path testFile = projectRoot.resolve("src/test/java/com/example/app/service/RuntimeContextServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertFalse;

                class RuntimeContextServiceTest {
                    @Test
                    void shouldUseContextThenPatch() {
                        RuntimeContextService service = new RuntimeContextService();
                        boolean result = service.markReady();
                        assertFalse(result);
                    }
                }
                """);

        AgentConfig config = new AgentConfigBuilder()
                .projectPath(projectRoot)
                .build();
        TestMethodInfo methodInfo = new TestMethodInfo(
                "public boolean markReady()",
                "boolean",
                "return true;");
        TestClassInfo classInfo = new TestClassInfo(
                "com.example.app.service.RuntimeContextService",
                "RuntimeContextServiceTest",
                testFile,
                List.of(),
                List.of(methodInfo));
        Analyze.AnalysisSummary analysisSummary = emptySummary();

        CompilerInvoker compilerInvoker = (root, file, methodName) -> new CompileResult(true, List.of(), "", "");
        AtomicInteger executionCalls = new AtomicInteger();
        ExecutionInvoker executionInvoker = (root, file, methodName) -> {
            executionCalls.incrementAndGet();
            String source = read(file);
            if (source.contains("assertTrue(result);")) {
                return new ExecuteResult(true, List.of(), "", "");
            }
            return new ExecuteResult(false,
                    List.of("com.example.app.service.RuntimeContextServiceTest.shouldUseContextThenPatch"),
                    "",
                    "expected: <true> but was: <false>");
        };

        AtomicInteger reasoningCalls = new AtomicInteger();
        CompilationReasoningService reasoningService = new CompilationReasoningService(
                new LlmClient() {
                    @Override
                    public com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet generateTestSnippet(String prompt,
                                                                                                          TestClassInfo ignoredClassInfo,
                                                                                                          TestMethodInfo ignoredMethodInfo,
                                                                                                          MockPlan ignoredPlan) {
                        throw new UnsupportedOperationException();
                    }
                },
                new CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser()) {
            @Override
            public ReasoningResponse reasonAboutError(ReasoningLoopContext loopContext,
                                                      ReasoningOptions options) {
                int call = reasoningCalls.getAndIncrement();
                if (call < 3) {
                    return requestContextResponse("com.example.app.service.RuntimeContextService");
                }
                return applyPatchResponse(testFile);
            }
        };

        ToolActionExecutor actionExecutor = new ToolActionExecutor(
                new SourceFileEditor(),
                compilerInvoker,
                executionInvoker,
                projectRoot,
                testFile,
                "com.example.app.service.RuntimeContextServiceTest",
                "shouldUseContextThenPatch");

        CompilationPipelineOrchestrator noOpCompilationOrchestrator = new CompilationPipelineOrchestrator(
                compilerInvoker,
                reasoningService,
                new ProjectContextCollector(projectRoot),
                actionExecutor,
                null,
                new PipelineLogger(projectRoot),
                projectRoot,
                testFile,
                "com.example.app.service.RuntimeContextServiceTest",
                "shouldUseContextThenPatch") {
            @Override
            public CompileResult runFixingLoop() {
                return new CompileResult(true, List.of(), "", "");
            }
        };

        ExecutionPipelineOrchestrator orchestrator = new ExecutionPipelineOrchestrator(
                new PipelineLogger(projectRoot),
                compilerInvoker,
                executionInvoker,
                new ExecutionFailureLogParser(),
                new ExecutionReportParser(),
                new ExecutionFailureContextCollector(),
                noOpCompilationOrchestrator,
                reasoningService);

        ExecuteResult initialExecuteResult = executionInvoker.execute(projectRoot, testFile, "shouldUseContextThenPatch");
        ExecutionPipelineOrchestrator.ExecutionRepairResult result = orchestrator.runRepairLoop(
                config,
                classInfo,
                methodInfo,
                analysisSummary,
                actionExecutor,
                new CompileResult(true, List.of(), "", ""),
                initialExecuteResult,
                "shouldUseContextThenPatch",
                "shouldUseContextThenPatch",
                new ExecutionFailureParseResult(List.of(), Optional.empty()),
                List.of(new TestReportFailure(
                        "com.example.app.service.RuntimeContextServiceTest",
                        "shouldUseContextThenPatch",
                        "expected: <true> but was: <false>",
                        List.of("at com.example.app.service.RuntimeContextService.markReady(RuntimeContextService.java:4)"))));

        assertTrue(result.success());
        assertTrue(read(testFile).contains("assertTrue(result);"));
        assertEquals(4, reasoningCalls.get(), "Expected three useful context rounds and one final fix round");
        assertTrue(executionCalls.get() >= 2, "Expected execution rerun after patch application");
        String logs = read(projectRoot.resolve(".agent/logs/pipeline.log"));
        assertTrue(logs.contains("Context budget reached zero after useful context for method shouldUseContextThenPatch"));
    }

    @Test
    void shouldTreatDeterministicRecipeSuccessAsExecutionSuccess() throws Exception {
        Path projectRoot = tempDir;
        Path testFile = projectRoot.resolve("src/test/java/mtd/abonent/NEW_AUTOTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package mtd.abonent;

                import bd.Abonent;
                import bd.Abonent.Ref;
                import rt.Varchar2;

                class NEW_AUTOTest {
                    void test_NEW_AUTO_EXECUTE_withValidInput() {
                        NEW_AUTO o = new NEW_AUTO();
                        Abonent abonent = new Abonent();
                        Ref ref = new Ref(abonent);
                        Varchar2 plpClass = new Varchar2("someClass");
                        o.NEW_AUTO_EXECUTE(ref, plpClass);
                    }
                }
                """);

        AgentConfig config = new AgentConfigBuilder()
                .projectPath(projectRoot)
                .build();
        TestMethodInfo methodInfo = new TestMethodInfo(
                "public final bd.Abonent.Ref NEW_AUTO_EXECUTE(final bd.Abonent.Ref THIS, final Varchar2 PLP$CLASS)",
                "bd.Abonent.Ref",
                "return THIS;");
        TestClassInfo classInfo = new TestClassInfo(
                "mtd.abonent.NEW_AUTO",
                "NEW_AUTOTest",
                testFile,
                List.of(),
                List.of(methodInfo));

        CompilerInvoker compilerInvoker = (root, file, methodName) -> new CompileResult(true, List.of(), "", "");
        AtomicInteger executionCalls = new AtomicInteger();
        ExecutionInvoker executionInvoker = (root, file, methodName) -> {
            executionCalls.incrementAndGet();
            String source = read(file);
            if (source.contains("mock(Ref.class)")
                    && source.contains("when(ref.isCreated()).thenReturn(true);")
                    && source.contains("when(ref.getClassId()).thenReturn(new Varchar2(\"ABONENT\"));")) {
                return new ExecuteResult(true, List.of(), "", "");
            }
            return new ExecuteResult(false,
                    List.of("mtd.abonent.NEW_AUTOTest.test_NEW_AUTO_EXECUTE_withValidInput"),
                    "",
                    """
                            Failures (1):
                              JUnit Jupiter:NEW_AUTOTest:test_NEW_AUTO_EXECUTE_withValidInput()
                                MethodSource [className = 'mtd.abonent.NEW_AUTOTest', methodName = 'test_NEW_AUTO_EXECUTE_withValidInput', methodParameterTypes = '']
                                => java.lang.AssertionError: No global settings provided
                                   ibso.GlobalSettings.get(GlobalSettings.java:12)
                                   cls.Meta.get(Meta.java:13)
                                   bd.Abonent.<clinit>(Abonent.java:18)
                                   mtd.abonent.NEW_AUTOTest.test_NEW_AUTO_EXECUTE_withValidInput(NEW_AUTOTest.java:11)
                            """);
        };

        CompilationReasoningService reasoningService = new CompilationReasoningService(
                new LlmClient() {
                    @Override
                    public com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet generateTestSnippet(String prompt,
                                                                                                          TestClassInfo ignoredClassInfo,
                                                                                                          TestMethodInfo ignoredMethodInfo,
                                                                                                          MockPlan ignoredPlan) {
                        throw new UnsupportedOperationException();
                    }
                },
                new CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser()) {
            @Override
            public ReasoningResponse reasonAboutError(ReasoningLoopContext loopContext,
                                                      ReasoningOptions options) {
                throw new AssertionError("LLM reasoning should not be needed when a deterministic recipe succeeds");
            }
        };

        ToolActionExecutor actionExecutor = new ToolActionExecutor(
                new SourceFileEditor(),
                compilerInvoker,
                executionInvoker,
                projectRoot,
                testFile,
                "mtd.abonent.NEW_AUTOTest",
                "test_NEW_AUTO_EXECUTE_withValidInput");

        CompilationPipelineOrchestrator noOpCompilationOrchestrator = new CompilationPipelineOrchestrator(
                compilerInvoker,
                reasoningService,
                new ProjectContextCollector(projectRoot),
                actionExecutor,
                null,
                new PipelineLogger(projectRoot),
                projectRoot,
                testFile,
                "mtd.abonent.NEW_AUTOTest",
                "test_NEW_AUTO_EXECUTE_withValidInput") {
            @Override
            public CompileResult runFixingLoop() {
                return new CompileResult(true, List.of(), "", "");
            }
        };

        ExecutionFailureContextCollector collector = new ExecutionFailureContextCollector() {
            @Override
            public ActionExecutionResult collect(Path root,
                                                 TestClassInfo ignoredClassInfo,
                                                 TestMethodInfo ignoredMethodInfo,
                                                 Analyze.AnalysisSummary ignoredSummary,
                                                 ExecuteResult ignoredExecuteResult,
                                                 ExecutionFailureParseResult ignoredFailureParseResult,
                                                 List<TestReportFailure> ignoredReportFailures) {
                return new ActionExecutionResult(Map.of(
                        "deterministicRepairRecipes", List.of(Map.of(
                                "id", "ALIGN_NEW_AUTO_STATIC_INIT_REF_FIXTURE",
                                "operations", List.of(Map.of(
                                        "type", "replace_ref_initializer_with_mock_fixture",
                                        "testMethodName", "test_NEW_AUTO_EXECUTE_withValidInput",
                                        "refVariable", "ref",
                                        "refTypeExpression", "Ref",
                                        "classIdLiteral", "ABONENT",
                                        "objectTypeFqcn", "bd.Abonent"
                                ))
                        ))
                ));
            }
        };

        ExecutionPipelineOrchestrator orchestrator = new ExecutionPipelineOrchestrator(
                new PipelineLogger(projectRoot),
                compilerInvoker,
                executionInvoker,
                new ExecutionFailureLogParser(),
                new ExecutionReportParser(),
                collector,
                noOpCompilationOrchestrator,
                reasoningService);

        ExecuteResult initialExecuteResult = executionInvoker.execute(projectRoot, testFile, "test_NEW_AUTO_EXECUTE_withValidInput");
        ExecutionPipelineOrchestrator.ExecutionRepairResult result = orchestrator.runRepairLoop(
                config,
                classInfo,
                methodInfo,
                emptySummary(),
                actionExecutor,
                new CompileResult(true, List.of(), "", ""),
                initialExecuteResult,
                "test_NEW_AUTO_EXECUTE_withValidInput",
                "test_NEW_AUTO_EXECUTE_withValidInput",
                new ExecutionFailureParseResult(List.of(), Optional.empty()),
                List.of());

        assertTrue(result.success());
        assertTrue(read(testFile).contains("mock(Ref.class);"));
        assertFalse(read(testFile).contains("new Ref(abonent);"));
        assertEquals(2, executionCalls.get(), "Expected one failing run and one successful rerun after deterministic recipe");
        String logs = read(projectRoot.resolve(".agent/logs/pipeline.log"));
        assertTrue(logs.contains("[EXECUTION_REASONING] action=APPLY_DETERMINISTIC_RECIPE method=test_NEW_AUTO_EXECUTE_withValidInput"));
        assertTrue(logs.contains("Deterministic execution recipe fixed runtime failure for method test_NEW_AUTO_EXECUTE_withValidInput"));
        assertFalse(logs.contains("action=REGENERATE_TEST method=test_NEW_AUTO_EXECUTE_withValidInput"));
    }

    private static ReasoningResponse requestContextResponse(String className) {
        ReasoningResponse response = new ReasoningResponse();
        response.setDecision("REQUEST_CONTEXT");
        ReasoningResponse.ReasoningAction action = new ReasoningResponse.ReasoningAction();
        action.setType("READ_CLASS");
        action.setArgs(Map.of("className", className));
        response.setActions(List.of(action));
        return response;
    }

    private static ReasoningResponse applyPatchResponse(Path testFile) {
        ReasoningResponse response = new ReasoningResponse();
        response.setDecision("APPLY_FIX");
        ReasoningResponse.ReasoningAction action = new ReasoningResponse.ReasoningAction();
        action.setType("APPLY_PATCH");
        action.setArgs(Map.of(
                "path", testFile.toString(),
                "patch", "@@ -11,1 +11,1 @@\n-        assertFalse(result);\n+        assertTrue(result);\n"));
        response.setActions(List.of(action));
        return response;
    }

    private static Analyze.AnalysisSummary emptySummary() {
        return new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                null,
                "{}",
                Map.of(),
                null,
                true,
                List.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                Map.of(),
                Map.of(),
                java.util.Set.of(),
                java.util.Set.of());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }
}
