package com.gigachat.unit.tests.generator.cleaner;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.GradleCompilerInvoker;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.config.AgentMode;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCleanerStageTest {

    @TempDir
    Path projectDir;

    @Test
    void removesMethodsReportedInLogs() throws IOException {
        Path testFile = projectDir.resolve("src/test/java/com/example/SampleTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, String.join(System.lineSeparator(),
                "package com.example;",
                "import org.junit.jupiter.api.Test;",
                "class SampleTest {",
                "    @Test",
                "    void shouldStay() {",
                "        org.junit.jupiter.api.Assertions.assertTrue(true);",
                "    }",
                "    @Test",
                "    void shouldBeDropped() {",
                "        org.junit.jupiter.api.Assertions.assertTrue(true);",
                "    }",
                "    @Test",
                "    void runtimeFailure() {",
                "        org.junit.jupiter.api.Assertions.assertTrue(true);",
                "    }",
                "}"));

        List<String> lines = Files.readAllLines(testFile);
        int failingLine = IntStream.range(0, lines.size())
                .filter(i -> lines.get(i).contains("shouldBeDropped"))
                .map(i -> i + 1)
                .findFirst()
                .orElse(0);

        PipelineLogger logger = new PipelineLogger(projectDir);
        StubGradleCompilerInvoker compilerInvoker = new StubGradleCompilerInvoker(logger, call -> {
            if (call == 1) {
                String stdout = testFile + ":" + failingLine + ": error: cannot find symbol";
                return new CompileResult(false, List.of(), stdout, "compile-error");
            }
            return new CompileResult(true, List.of(), "", "");
        });
        Path reportPath = projectDir.resolve("build/reports/tests/test/index.html");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, String.join(System.lineSeparator(),
                "SampleTest > runtimeFailure FAILED"));

        String executionLog = String.join(System.lineSeparator(),
                "SampleTest > shouldBeDropped() FAILED",
                "SampleTest > runtimeFailure FAILED",
                "> There were failing tests. See the report at: file://" + reportPath);

        ExecutionInvoker executionInvoker = (root, file, method) ->
                new ExecuteResult(false, List.of(), executionLog, "runtime-error");
        TestCleaner cleaner = new TestCleaner(logger, compilerInvoker, executionInvoker,
                index -> new CleanerRules(List.of(), List.of()));

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.CLEAN)
                .projectPath(projectDir)
                .build();

        cleaner.clean(config);

        int calls = compilerInvoker.getCalls();
        assertEquals(2, calls, "Compilation should repeat until clean but was " + calls);
        String updated = Files.readString(testFile);
        assertTrue(updated.contains("shouldStay"));
        assertFalse(updated.contains("shouldBeDropped"));
        assertFalse(updated.contains("runtimeFailure"));
    }

    @Test
    void resolvesFullyQualifiedClassNamesDuringExecutionCleanup() throws IOException {
        Path testFile = projectDir.resolve("src/test/java/com/example/app/model/UserTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, String.join(System.lineSeparator(),
                "package com.example.app.model;",
                "import org.junit.jupiter.api.Test;",
                "class UserTest {",
                "    @Test",
                "    void shouldStay() {",
                "        org.junit.jupiter.api.Assertions.assertTrue(true);",
                "    }",
                "    @Test",
                "    void shouldBeRemoved() {",
                "        org.junit.jupiter.api.Assertions.assertTrue(true);",
                "    }",
                "}"));

        PipelineLogger logger = new PipelineLogger(projectDir);
        StubGradleCompilerInvoker compilerInvoker = new StubGradleCompilerInvoker(logger, call -> new CompileResult(true, List.of(), "", ""));
        String executionLog = String.join(System.lineSeparator(),
                "com.example.app.model.UserTest > shouldBeRemoved FAILED",
                "48 tests completed, 1 failed");
        ExecutionInvoker executionInvoker = (root, file, method) -> new ExecuteResult(false, List.of(), executionLog, "runtime-error");
        TestCleaner cleaner = new TestCleaner(logger, compilerInvoker, executionInvoker,
                index -> new CleanerRules(List.of(), List.of()));

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.CLEAN)
                .projectPath(projectDir)
                .build();

        cleaner.clean(config);

        String updated = Files.readString(testFile);
        assertTrue(updated.contains("shouldStay"));
        assertFalse(updated.contains("shouldBeRemoved"));
    }
    private static final class StubGradleCompilerInvoker extends GradleCompilerInvoker {
        private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.function.Function<Integer, CompileResult> behavior;

        StubGradleCompilerInvoker(PipelineLogger logger, java.util.function.Function<Integer, CompileResult> behavior) {
            super(logger);
            this.behavior = behavior;
        }

        @Override
        public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
            int invocation = calls.incrementAndGet();
            return behavior.apply(invocation);
        }

        int getCalls() {
            return calls.get();
        }
    }
}
