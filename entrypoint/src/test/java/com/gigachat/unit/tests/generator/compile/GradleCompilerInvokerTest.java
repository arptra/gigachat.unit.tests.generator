package com.gigachat.unit.tests.generator.compile;

import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void sharesCacheAcrossInvokerInstances(@TempDir Path projectRoot) throws IOException {
        Path testsDir = projectRoot.resolve("src/test/java/sample");
        Files.createDirectories(testsDir);

        Path testFile = testsDir.resolve("SharedCacheTest.java");
        Files.writeString(testFile,
                "package sample;\n" +
                        "public class SharedCacheTest {\n" +
                        "    public void ok() {}\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        GradleCompilerInvoker firstInvoker = new GradleCompilerInvoker(new PipelineLogger(projectRoot));
        CompileResult firstResult = firstInvoker.compile(projectRoot, testFile, "ok");

        GradleCompilerInvoker secondInvoker = new GradleCompilerInvoker(new PipelineLogger(projectRoot));
        CompileResult cachedResult = secondInvoker.compile(projectRoot, testFile, "ok");

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

    @Test
    void compilesMultipleFilesInParallel(@TempDir Path projectRoot) throws IOException {
        Path testsDir = projectRoot.resolve("src/test/java/sample");
        Files.createDirectories(testsDir);

        Path firstTest = testsDir.resolve("FirstParallelTest.java");
        Files.writeString(firstTest,
                "package sample;\n" +
                        "public class FirstParallelTest {\n" +
                        "    public void ok() {}\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        Path secondTest = testsDir.resolve("SecondParallelTest.java");
        Files.writeString(secondTest,
                "package sample;\n" +
                        "public class SecondParallelTest {\n" +
                        "    public void ok() {}\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        GradleCompilerInvoker invoker = new GradleCompilerInvoker(new PipelineLogger(projectRoot));

        List<CompileResult> results = invoker.compileParallel(projectRoot, List.of(firstTest, secondTest), "parallel");

        assertEquals(2, results.size());
        assertTrue(results.getFirst().success());
        assertTrue(results.getLast().success());

        Path outputDir = projectRoot.resolve("build/classes/java/test/sample");
        assertTrue(Files.exists(outputDir.resolve("FirstParallelTest.class")));
        assertTrue(Files.exists(outputDir.resolve("SecondParallelTest.class")));
    }

    @Test
    void reportsIsolatedFailuresWhenCompilingInParallel(@TempDir Path projectRoot) throws IOException {
        Path testsDir = projectRoot.resolve("src/test/java/sample");
        Files.createDirectories(testsDir);

        Path validTest = testsDir.resolve("ValidParallelTest.java");
        Files.writeString(validTest,
                "package sample;\n" +
                        "public class ValidParallelTest {\n" +
                        "    public void ok() {}\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        Path invalidTest = testsDir.resolve("InvalidParallelTest.java");
        Files.writeString(invalidTest,
                "package sample;\n" +
                        "public class InvalidParallelTest {\n" +
                        "    public void oops(\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        GradleCompilerInvoker invoker = new GradleCompilerInvoker(new PipelineLogger(projectRoot));

        List<CompileResult> results = invoker.compileParallel(projectRoot, List.of(validTest, invalidTest), "parallel");

        assertEquals(2, results.size());
        assertTrue(results.getFirst().success());
        assertFalse(results.getLast().success());

        Path outputDir = projectRoot.resolve("build/classes/java/test/sample");
        assertTrue(Files.exists(outputDir.resolve("ValidParallelTest.class")));
        assertFalse(Files.exists(outputDir.resolve("InvalidParallelTest.class")));
    }

    @Test
    void forksSeparateJvmForEachParallelCompilation(@TempDir Path projectRoot) throws IOException {
        Path testsDir = projectRoot.resolve("src/test/java/sample");
        Files.createDirectories(testsDir);

        Path firstTest = testsDir.resolve("ForkedFirstTest.java");
        Files.writeString(firstTest,
                "package sample;\n" +
                        "public class ForkedFirstTest {\n" +
                        "    public void ok() {}\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        Path secondTest = testsDir.resolve("ForkedSecondTest.java");
        Files.writeString(secondTest,
                "package sample;\n" +
                        "public class ForkedSecondTest {\n" +
                        "    public void ok() {}\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        GradleCompilerInvoker invoker = new GradleCompilerInvoker(new PipelineLogger(projectRoot));
        List<CompileResult> results = invoker.compileParallel(projectRoot, List.of(firstTest, secondTest), "forked");

        assertTrue(results.getFirst().success());
        assertTrue(results.getLast().success());

        Path logFile = projectRoot.resolve(".agent/logs/pipeline.log");
        Set<String> pids = Files.readAllLines(logFile).stream()
                .filter(line -> line.contains("Forking javac process (pid="))
                .map(line -> {
                    Matcher matcher = Pattern.compile("pid=(\\d+)").matcher(line);
                    return matcher.find() ? matcher.group(1) : "";
                })
                .filter(pid -> !pid.isBlank())
                .collect(Collectors.toSet());

        assertEquals(2, pids.size());
    }
}
