package com.gigachat.unit.tests.generator.cleaner;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.config.AgentMode;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

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

        AtomicInteger compileCalls = new AtomicInteger();
        CompilerInvoker compilerInvoker = (root, file, method) -> {
            compileCalls.incrementAndGet();
            String stdout = "com.example.SampleTest > shouldBeDropped FAILED";
            return new CompileResult(false, List.of(), stdout, "compile-error");
        };
        ExecutionInvoker executionInvoker = (root, file, method) ->
                new ExecuteResult(!"runtimeFailure".equals(method), List.of(), "", "runtime-error");
        PipelineLogger logger = new PipelineLogger(projectDir);
        TestCleaner cleaner = new TestCleaner(logger, compilerInvoker, executionInvoker, index -> List.of());

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.CLEAN)
                .projectPath(projectDir)
                .build();

        cleaner.clean(config);

        assertEquals(1, compileCalls.get(), "Compilation should be invoked once per file");
        String updated = Files.readString(testFile);
        assertTrue(updated.contains("shouldStay"));
        assertFalse(updated.contains("shouldBeDropped"));
        assertFalse(updated.contains("runtimeFailure"));
    }
}
