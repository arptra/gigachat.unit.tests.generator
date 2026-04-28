package com.gigachat.unit.tests.generator.reasoning.orchestrator;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.llm.LlmClient;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder;
import com.gigachat.unit.tests.generator.reasoning.service.CompilationReasoningService;
import com.gigachat.unit.tests.generator.reasoning.service.ProjectContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.ReasoningResponseParser;
import com.gigachat.unit.tests.generator.reasoning.service.SourceFileEditor;
import com.gigachat.unit.tests.generator.reasoning.service.ToolActionExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilationPipelineOrchestratorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldApplyDeterministicProjectImportRepairBeforeCallingReasoning() throws IOException {
        Path projectRoot = tempDir;
        Path mainDir = projectRoot.resolve("src/main/java/com/example/app/service");
        Files.createDirectories(mainDir);
        Files.writeString(mainDir.resolve("NotificationService.java"), """
                package com.example.app.service;

                class NotificationService {
                }
                """);

        Path testFile = projectRoot.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import com.example.app.util.NotificationService;

                class UserServiceTest {
                    private NotificationService notificationService;
                }
                """);

        AtomicInteger compileCalls = new AtomicInteger();
        CompilerInvoker compilerInvoker = (root, file, methodName) -> {
            compileCalls.incrementAndGet();
            String source;
            try {
                source = Files.readString(file);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to read test file in fake compiler", exception);
            }
            if (source.contains("import com.example.app.util.NotificationService;")) {
                return new CompileResult(
                        false,
                        List.of(),
                        "",
                        file + ":3: error: cannot find symbol\n"
                                + "symbol: class NotificationService\n"
                                + "location: package com.example.app.util\n");
            }
            return new CompileResult(true, List.of(), "", "");
        };

        AtomicInteger reasoningCalls = new AtomicInteger();
        CompilationReasoningService reasoningService = new CompilationReasoningService(
                new NoOpLlmClient(),
                new CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser()) {
            @Override
            public ReasoningResponse reasonAboutError(ReasoningLoopContext loopContext) {
                reasoningCalls.incrementAndGet();
                ReasoningResponse response = new ReasoningResponse();
                response.setDecision("REQUEST_CONTEXT");
                ReasoningResponse.ReasoningAction action = new ReasoningResponse.ReasoningAction();
                action.setType("READ_CLASS");
                action.setArgs(java.util.Map.of("className", "NotificationService"));
                response.setActions(List.of(action));
                return response;
            }
        };

        CompilationPipelineOrchestrator orchestrator = new CompilationPipelineOrchestrator(
                compilerInvoker,
                reasoningService,
                new ProjectContextCollector(projectRoot),
                new ToolActionExecutor(
                        new SourceFileEditor(),
                        compilerInvoker,
                        null,
                        projectRoot,
                        testFile,
                        "com.example.app.service.UserServiceTest",
                        "createUserShouldSaveAndNotify"),
                null,
                new PipelineLogger(projectRoot),
                projectRoot,
                testFile,
                "com.example.app.service.UserServiceTest",
                "createUserShouldSaveAndNotify");

        CompileResult result = orchestrator.runFixingLoop();

        assertTrue(result.success());
        assertEquals(2, compileCalls.get());
        assertEquals(0, reasoningCalls.get());

        String updated = Files.readString(testFile);
        assertFalse(updated.contains("import com.example.app.util.NotificationService;"));
        assertFalse(updated.contains("import com.example.app.service.NotificationService;"));
        assertTrue(updated.contains("private NotificationService notificationService;"));
    }

    @Test
    void shouldApplyDeterministicStaticImportRepairForAssertionMethod() throws IOException {
        Path projectRoot = tempDir;
        Path testFile = projectRoot.resolve("src/test/java/com/example/app/service/CoverageGoalWorkflowServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import org.junit.jupiter.api.Test;

                class CoverageGoalWorkflowServiceTest {
                    @Test
                    void testPriority() {
                        assertEquals("priority", "priority");
                    }
                }
                """);

        AtomicInteger compileCalls = new AtomicInteger();
        CompilerInvoker compilerInvoker = (root, file, methodName) -> {
            compileCalls.incrementAndGet();
            String source;
            try {
                source = Files.readString(file);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to read test file in fake compiler", exception);
            }
            if (!source.contains("import static org.junit.jupiter.api.Assertions.assertEquals;")) {
                return new CompileResult(
                        false,
                        List.of(),
                        "",
                        file + ":7: error: cannot find symbol\n"
                                + "symbol: method assertEquals(java.lang.String,java.lang.String)\n"
                                + "location: class com.example.app.service.CoverageGoalWorkflowServiceTest\n");
            }
            return new CompileResult(true, List.of(), "", "");
        };

        AtomicInteger reasoningCalls = new AtomicInteger();
        CompilationReasoningService reasoningService = new CompilationReasoningService(
                new NoOpLlmClient(),
                new CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser()) {
            @Override
            public ReasoningResponse reasonAboutError(ReasoningLoopContext loopContext) {
                reasoningCalls.incrementAndGet();
                return null;
            }
        };

        CompilationPipelineOrchestrator orchestrator = new CompilationPipelineOrchestrator(
                compilerInvoker,
                reasoningService,
                new ProjectContextCollector(projectRoot),
                new ToolActionExecutor(
                        new SourceFileEditor(),
                        compilerInvoker,
                        null,
                        projectRoot,
                        testFile,
                        "com.example.app.service.CoverageGoalWorkflowServiceTest",
                        "testPriority"),
                null,
                new PipelineLogger(projectRoot),
                projectRoot,
                testFile,
                "com.example.app.service.CoverageGoalWorkflowServiceTest",
                "testPriority");

        CompileResult result = orchestrator.runFixingLoop();

        assertTrue(result.success());
        assertEquals(2, compileCalls.get());
        assertEquals(0, reasoningCalls.get());
        assertTrue(Files.readString(testFile).contains("import static org.junit.jupiter.api.Assertions.assertEquals;"));
    }

    @Test
    void shouldNotTreatMissingVariableAsDeterministicImportRepairCandidate() throws IOException {
        Path projectRoot = tempDir;
        Path mainDir = projectRoot.resolve("src/main/java/com/example/app/legacy");
        Files.createDirectories(mainDir);
        Files.writeString(mainDir.resolve("LegacyTelemetry.java"), """
                package com.example.app.legacy;

                final class LegacyTelemetry {
                }
                """);

        Path testFile = projectRoot.resolve("src/test/java/com/example/app/legacy/ShadowRollbackSessionTest.java");
        Files.createDirectories(testFile.getParent());
        String original = """
                package com.example.app.legacy;

                class ShadowRollbackSessionTest {
                    void testRollbackWhenFeatureEnabledAndReboundHigh() {
                        verify(LegacyTelemetry).emit("shadow-rollback", "notified:disable:" + user.getUsername());
                    }
                }
                """;
        Files.writeString(testFile, original);

        AtomicInteger compileCalls = new AtomicInteger();
        CompilerInvoker compilerInvoker = (root, file, methodName) -> {
            compileCalls.incrementAndGet();
            return new CompileResult(
                    false,
                    List.of(),
                    "",
                    file + ":4: error: cannot find symbol\n"
                            + "  symbol:   variable LegacyTelemetry\n"
                            + "  location: class com.example.app.legacy.ShadowRollbackSessionTest\n");
        };

        CompilationReasoningService reasoningService = new CompilationReasoningService(
                new NoOpLlmClient(),
                new CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser()) {
            @Override
            public ReasoningResponse reasonAboutError(ReasoningLoopContext loopContext) {
                ReasoningResponse response = new ReasoningResponse();
                response.setDecision("REQUEST_CONTEXT");
                ReasoningResponse.ReasoningAction action = new ReasoningResponse.ReasoningAction();
                action.setType("READ_CLASS");
                action.setArgs(java.util.Map.of("className", "LegacyTelemetry"));
                response.setActions(List.of(action));
                return response;
            }
        };

        CompilationPipelineOrchestrator orchestrator = new CompilationPipelineOrchestrator(
                compilerInvoker,
                reasoningService,
                new ProjectContextCollector(projectRoot),
                new ToolActionExecutor(
                        new SourceFileEditor(),
                        compilerInvoker,
                        null,
                        projectRoot,
                        testFile,
                        "com.example.app.legacy.ShadowRollbackSessionTest",
                        "testRollbackWhenFeatureEnabledAndReboundHigh"),
                null,
                new PipelineLogger(projectRoot),
                projectRoot,
                testFile,
                "com.example.app.legacy.ShadowRollbackSessionTest",
                "testRollbackWhenFeatureEnabledAndReboundHigh");

        assertThrows(Exception.class, orchestrator::runFixingLoop);
        assertEquals(2, compileCalls.get());
        assertEquals(original, Files.readString(testFile));
    }

    @Test
    void shouldApplyDeterministicCompileRecipeForDuplicateJUnitAnnotation() throws IOException {
        Path projectRoot = tempDir;
        Path testFile = projectRoot.resolve("src/test/java/com/example/app/service/LibraryComponentTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import org.junit.jupiter.api.Test;

                class LibraryComponentTest {
                    @Test
                    @Test
                    void shouldReloadComponent() {
                    }
                }
                """);

        AtomicInteger compileCalls = new AtomicInteger();
        CompilerInvoker compilerInvoker = (root, file, methodName) -> {
            compileCalls.incrementAndGet();
            String source;
            try {
                source = Files.readString(file);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to read test file in fake compiler", exception);
            }
            if (source.contains("@Test\n    @Test")) {
                return new CompileResult(
                        false,
                        List.of(),
                        "",
                        file + ":6: error: org.junit.jupiter.api.Test is not a repeatable annotation interface\n"
                );
            }
            return new CompileResult(true, List.of(), "", "");
        };

        AtomicInteger reasoningCalls = new AtomicInteger();
        CompilationReasoningService reasoningService = new CompilationReasoningService(
                new NoOpLlmClient(),
                new CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser()) {
            @Override
            public ReasoningResponse reasonAboutError(ReasoningLoopContext loopContext) {
                reasoningCalls.incrementAndGet();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> recipes = (List<Map<String, Object>>) loopContext.getExecutionResult()
                        .getInformation()
                        .get("deterministicRepairRecipes");
                assertFalse(recipes.isEmpty());
                assertEquals("COLLAPSE_CONSECUTIVE_TEST_ANNOTATIONS", recipes.get(0).get("id"));

                ReasoningResponse response = new ReasoningResponse();
                response.setDecision("APPLY_FIX");
                ReasoningResponse.ReasoningAction action = new ReasoningResponse.ReasoningAction();
                action.setType("APPLY_RECIPE");
                action.setArgs(Map.of("recipeId", "COLLAPSE_CONSECUTIVE_TEST_ANNOTATIONS"));
                response.setActions(List.of(action));
                return response;
            }
        };

        CompilationPipelineOrchestrator orchestrator = new CompilationPipelineOrchestrator(
                compilerInvoker,
                reasoningService,
                new ProjectContextCollector(projectRoot),
                new ToolActionExecutor(
                        new SourceFileEditor(),
                        compilerInvoker,
                        null,
                        projectRoot,
                        testFile,
                        "com.example.app.service.LibraryComponentTest",
                        "shouldReloadComponent"),
                null,
                new PipelineLogger(projectRoot),
                projectRoot,
                testFile,
                "com.example.app.service.LibraryComponentTest",
                "shouldReloadComponent");

        CompileResult result = orchestrator.runFixingLoop();

        assertTrue(result.success());
        assertEquals(2, compileCalls.get());
        assertEquals(1, reasoningCalls.get());
        assertFalse(Files.readString(testFile).contains("@Test\n    @Test"));
    }

    @Test
    void shouldApplyDeterministicCompileRecipeForInventedUserStateConstructor() throws IOException {
        Path projectRoot = tempDir;
        Path testFile = projectRoot.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import com.example.app.model.User;
                import java.util.ArrayList;
                import java.util.List;

                class UserServiceTest {
                    void testAverageLoginAttemptsWithUsers() {
                        List<User> users = new ArrayList<>();
                        users.add(new User("Alice", "alice@example.com", 5));
                        users.add(new User("Bob", "bob@example.com", 3));
                    }
                }
                """);

        AtomicInteger compileCalls = new AtomicInteger();
        CompilerInvoker compilerInvoker = (root, file, methodName) -> {
            compileCalls.incrementAndGet();
            String source;
            try {
                source = Files.readString(file);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to read test file in fake compiler", exception);
            }
            if (source.contains("new User(\"Alice\", \"alice@example.com\", 5)")
                    || source.contains("new User(\"Bob\", \"bob@example.com\", 3)")) {
                return new CompileResult(
                        false,
                        List.of(),
                        "",
                        file + ":9: error: constructor User in class com.example.app.model.User cannot be applied to given types;\n"
                                + "  required: java.lang.String,java.lang.String\n"
                                + "  found:    java.lang.String,java.lang.String,int\n"
                                + "  reason: actual and formal argument lists differ in length\n");
            }
            return new CompileResult(true, List.of(), "", "");
        };

        AtomicInteger reasoningCalls = new AtomicInteger();
        CompilationReasoningService reasoningService = new CompilationReasoningService(
                new NoOpLlmClient(),
                new CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser()) {
            @Override
            public ReasoningResponse reasonAboutError(ReasoningLoopContext loopContext) {
                reasoningCalls.incrementAndGet();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> recipes = (List<Map<String, Object>>) loopContext.getExecutionResult()
                        .getInformation()
                        .get("deterministicRepairRecipes");
                assertFalse(recipes.isEmpty());
                assertEquals("NORMALIZE_USER_CONSTRUCTOR_STATE_VARIANTS", recipes.get(0).get("id"));

                ReasoningResponse response = new ReasoningResponse();
                response.setDecision("APPLY_FIX");
                ReasoningResponse.ReasoningAction action = new ReasoningResponse.ReasoningAction();
                action.setType("APPLY_RECIPE");
                action.setArgs(Map.of("recipeId", "NORMALIZE_USER_CONSTRUCTOR_STATE_VARIANTS"));
                response.setActions(List.of(action));
                return response;
            }
        };

        CompilationPipelineOrchestrator orchestrator = new CompilationPipelineOrchestrator(
                compilerInvoker,
                reasoningService,
                new ProjectContextCollector(projectRoot),
                new ToolActionExecutor(
                        new SourceFileEditor(),
                        compilerInvoker,
                        null,
                        projectRoot,
                        testFile,
                        "com.example.app.service.UserServiceTest",
                        "testAverageLoginAttemptsWithUsers"),
                null,
                new PipelineLogger(projectRoot),
                projectRoot,
                testFile,
                "com.example.app.service.UserServiceTest",
                "testAverageLoginAttemptsWithUsers");

        CompileResult result = orchestrator.runFixingLoop();

        assertTrue(result.success());
        assertEquals(2, compileCalls.get());
        assertEquals(1, reasoningCalls.get());
        String rewritten = Files.readString(testFile);
        assertFalse(rewritten.contains("new User(\"Alice\", \"alice@example.com\", 5)"));
        assertTrue(rewritten.contains("generatedUser1.incrementAttempts();"));
        assertTrue(rewritten.contains("users.add(generatedUser2);"));
    }

    private static final class NoOpLlmClient implements LlmClient {
        @Override
        public String requestStructuredResponse(String prompt) {
            return "{}";
        }

        @Override
        public com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet generateTestSnippet(String prompt,
                                                                                              com.gigachat.unit.tests.generator.dto.TestClassInfo classInfo,
                                                                                              com.gigachat.unit.tests.generator.dto.TestMethodInfo methodInfo,
                                                                                              com.gigachat.unit.tests.generator.dto.MockPlan plan) {
            throw new UnsupportedOperationException("Not used in this test");
        }
    }
}
