package com.gigachat.unit.tests.generator.reasoning.orchestrator;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.llm.LlmClient;
import com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder;
import com.gigachat.unit.tests.generator.reasoning.model.FixSession;
import com.gigachat.unit.tests.generator.reasoning.service.BuildFileEditor;
import com.gigachat.unit.tests.generator.reasoning.service.CompilationReasoningService;
import com.gigachat.unit.tests.generator.reasoning.service.ProjectContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.ReasoningResponseParser;
import com.gigachat.unit.tests.generator.reasoning.service.SourceFileEditor;
import com.gigachat.unit.tests.generator.reasoning.service.ToolActionExecutor;
import com.gigachat.unit.tests.generator.reasoning.workflow.ReasoningWorkflow;
import com.gigachat.unit.tests.generator.reasoning.workflow.exception.FixingFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilationPipelineOrchestratorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldNotStopOnSecondSameErrorWhenContextWasRequested() throws IOException {
        Path moduleRoot = tempDir.resolve("entrypoint");
        Path testDir = moduleRoot.resolve("src/test/java/example");
        Files.createDirectories(testDir);
        Path testFile = testDir.resolve("GeneratedTest.java");
        Files.writeString(testFile, "package example;\nclass GeneratedTest {}\n");

        SequencedCompilerInvoker compilerInvoker = new SequencedCompilerInvoker(testFile);
        ToolActionExecutor actionExecutor = new ToolActionExecutor(
                new BuildFileEditor(moduleRoot),
                new SourceFileEditor(),
                compilerInvoker,
                null,
                tempDir,
                testFile,
                "example.GeneratedTest",
                "generatedTest"
        );
        ReasoningWorkflow workflow = createWorkflow("""
                {
                  "decision":"REQUEST_CONTEXT",
                  "actions":[{"type":"SEARCH_SYMBOL","args":{"symbol":"MissingType"}}],
                  "memory_updates":{}
                }
                """);

        CompilationPipelineOrchestrator orchestrator = new CompilationPipelineOrchestrator(
                compilerInvoker,
                workflow,
                new ProjectContextCollector(tempDir),
                actionExecutor,
                null,
                tempDir,
                testFile,
                "example.GeneratedTest",
                "generatedTest",
                new FixSession("test-session", testFile, "generatedTest")
        );

        CompileResult result = orchestrator.runFixingLoop();

        assertTrue(result.success());
        assertEquals(3, compilerInvoker.calls);
    }

    @Test
    void shouldCaptureIterationSnapshotsWhenBudgetIsExhausted() throws IOException {
        Path moduleRoot = tempDir.resolve("entrypoint");
        Path testDir = moduleRoot.resolve("src/test/java/example");
        Files.createDirectories(testDir);
        Path testFile = testDir.resolve("GeneratedTest.java");
        Files.writeString(testFile, "package example;\nclass GeneratedTest {}\n");

        CompilerInvoker alwaysFailingCompiler = (projectRoot, testClassFile, methodName) -> {
            String stderr = testFile + ":5: error: cannot find symbol\n"
                    + "symbol: class MissingType\n"
                    + "location: class example.GeneratedTest";
            return new CompileResult(false, List.of("cannot find symbol"), "", stderr);
        };
        ToolActionExecutor actionExecutor = new ToolActionExecutor(
                new BuildFileEditor(moduleRoot),
                new SourceFileEditor(),
                alwaysFailingCompiler,
                null,
                tempDir,
                testFile,
                "example.GeneratedTest",
                "generatedTest"
        );
        ReasoningWorkflow workflow = createWorkflow("""
                {
                  "decision":"REQUEST_CONTEXT",
                  "actions":[{"type":"SEARCH_SYMBOL","args":{"symbol":"MissingType"}}],
                  "memory_updates":{}
                }
                """);

        CompilationPipelineOrchestrator orchestrator = new CompilationPipelineOrchestrator(
                alwaysFailingCompiler,
                workflow,
                new ProjectContextCollector(tempDir),
                actionExecutor,
                null,
                tempDir,
                testFile,
                "example.GeneratedTest",
                "generatedTest",
                new FixSession("budget-session", testFile, "generatedTest")
        );

        FixingFailureException exception = assertThrows(FixingFailureException.class, orchestrator::runFixingLoop);
        assertFalse(exception.getIterationSnapshots().isEmpty());
        assertEquals("budget-session", exception.getIterationSnapshots().get(0).getSessionId());
    }

    @Test
    void shouldRollbackTestFileAfterNoProgressAbort() throws IOException {
        Path moduleRoot = tempDir.resolve("entrypoint");
        Path testDir = moduleRoot.resolve("src/test/java/example");
        Files.createDirectories(testDir);
        Path testFile = testDir.resolve("GeneratedTest.java");
        String initialSource = "package example;\nclass GeneratedTest {}\n";
        Files.writeString(testFile, initialSource);

        CompilerInvoker alwaysFailingCompiler = (projectRoot, testClassFile, methodName) -> {
            String stderr = testFile + ":5: error: cannot find symbol\n"
                    + "symbol: class MissingType\n"
                    + "location: class example.GeneratedTest";
            return new CompileResult(false, List.of("cannot find symbol"), "", stderr);
        };
        ToolActionExecutor actionExecutor = new ToolActionExecutor(
                new BuildFileEditor(moduleRoot),
                new SourceFileEditor(),
                alwaysFailingCompiler,
                null,
                tempDir,
                testFile,
                "example.GeneratedTest",
                "generatedTest"
        );
        String patch = "@@ -1,2 +1,3 @@\n package example;\n+import java.util.List;\n class GeneratedTest {}\n";
        ReasoningWorkflow workflow = createWorkflow("""
                {
                  "decision":"APPLY_FIX",
                  "hypothesis":"Apply minimal patch to resolve missing import.",
                  "expected_delta":{"compile_errors":-1,"symbol":"MissingType"},
                  "actions":[{"type":"APPLY_PATCH","preconditions":["patch_applies_cleanly"],"args":{"path":"%s","patch":"%s"}}],
                  "memory_updates":{}
                }
                """.formatted(testFile, patch.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")));

        CompilationPipelineOrchestrator orchestrator = new CompilationPipelineOrchestrator(
                alwaysFailingCompiler,
                workflow,
                new ProjectContextCollector(tempDir),
                actionExecutor,
                null,
                tempDir,
                testFile,
                "example.GeneratedTest",
                "generatedTest",
                new FixSession("rollback-session", testFile, "generatedTest")
        );

        assertThrows(FixingFailureException.class, orchestrator::runFixingLoop);
        String actual = Files.readString(testFile);
        assertEquals(initialSource, actual);
    }

    private ReasoningWorkflow createWorkflow(String payload) {
        CompilationReasoningService service = new CompilationReasoningService(
                new StubLlmClient(payload),
                new CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser()
        );
        return new ReasoningWorkflow(new CompilationReasoningOrchestrator(service));
    }

    private static final class SequencedCompilerInvoker implements CompilerInvoker {

        private final Path testFile;
        private int calls;

        private SequencedCompilerInvoker(Path testFile) {
            this.testFile = testFile;
        }

        @Override
        public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
            calls++;
            if (calls >= 3) {
                return new CompileResult(true, List.of(), "", "");
            }
            String stderr = testFile + ":5: error: cannot find symbol\n"
                    + "symbol: class MissingType\n"
                    + "location: class example.GeneratedTest";
            return new CompileResult(false, List.of("cannot find symbol"), "", stderr);
        }
    }

    private static final class StubLlmClient implements LlmClient {

        private final String payload;

        private StubLlmClient(String payload) {
            this.payload = payload;
        }

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        MockPlan plan) {
            return new GeneratedTestSnippet(
                    classInfo.getTestClassName(),
                    methodInfo.getSignature(),
                    payload,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    ""
            );
        }
    }
}
