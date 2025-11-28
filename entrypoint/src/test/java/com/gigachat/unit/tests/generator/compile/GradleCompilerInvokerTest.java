package com.gigachat.unit.tests.generator.compile;

import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void returnsCachedResultWhenSourceIsUnchanged(@TempDir Path projectRoot) throws IOException {
        Path testsDir = projectRoot.resolve("src/test/java/sample");
        Files.createDirectories(testsDir);

        Path testFile = testsDir.resolve("CachedTest.java");
        Files.writeString(testFile,
                "package sample;\n" +
                        "public class CachedTest {\n" +
                        "    public void ok() {}\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        GradleCompilerInvoker invoker = new GradleCompilerInvoker(new PipelineLogger(projectRoot));

        CompileResult firstResult = invoker.compile(projectRoot, testFile, "ok");
        CompileResult cachedResult = invoker.compile(projectRoot, testFile, "ok");

        assertTrue(firstResult.success());
        assertSame(firstResult, cachedResult);
    }

    @Test
    void invalidatesCacheWhenSourceChanges(@TempDir Path projectRoot) throws Exception {
        Path testsDir = projectRoot.resolve("src/test/java/sample");
        Files.createDirectories(testsDir);

        Path testFile = testsDir.resolve("ChangingTest.java");
        Files.writeString(testFile,
                "package sample;\n" +
                        "public class ChangingTest {\n" +
                        "    public void ok() {}\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        GradleCompilerInvoker invoker = new GradleCompilerInvoker(new PipelineLogger(projectRoot));

        CompileResult firstResult = invoker.compile(projectRoot, testFile, "ok");
        assertTrue(firstResult.success());

        // Ensure filesystem modification time changes
        TimeUnit.MILLISECONDS.sleep(5);

        Files.writeString(testFile,
                "package sample;\n" +
                        "public class ChangingTest {\n" +
                        "    public void broken(\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        CompileResult secondResult = invoker.compile(projectRoot, testFile, "broken");

        assertFalse(secondResult.success());
        assertNotSame(firstResult, secondResult);
    }
}
