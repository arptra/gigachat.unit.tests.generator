package com.gigachat.unit.tests.generator.compile;

import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleCompilerInvokerTest {

    @Test
    void compilesOnlyProvidedFile(@TempDir Path projectRoot) throws IOException {
        Path testsDir = projectRoot.resolve("src/test/java/sample");
        Files.createDirectories(testsDir);

        Path goodTest = testsDir.resolve("GoodTest.java");
        Files.writeString(goodTest,
                "package sample;\n" +
                        "public class GoodTest {\n" +
                        "    public void ok() {}\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        Path brokenTest = testsDir.resolve("BrokenTest.java");
        Files.writeString(brokenTest,
                "package sample;\n" +
                        "public class BrokenTest {\n" +
                        "    public void missingBrace(\n",
                StandardCharsets.UTF_8);

        GradleCompilerInvoker invoker = new GradleCompilerInvoker(new PipelineLogger(projectRoot));

        CompileResult result = invoker.compile(projectRoot, goodTest, "ok");

        Path outputDir = projectRoot.resolve("build/classes/java/test/sample");
        assertTrue(result.success());
        assertTrue(Files.exists(outputDir.resolve("GoodTest.class")));
        assertFalse(Files.exists(outputDir.resolve("BrokenTest.class")));
    }
}
