package com.gigachat.unit.tests.generator.compile;

import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.compile.CompilationCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertTrue(results.get(0).success());
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
        assertTrue(results.get(0).success());
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

        assertTrue(results.get(0).success());
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

    @Test
    void compileAllTestsIncludesPrecompiledTestClasses(@TempDir Path projectRoot) throws Exception {
        Path existingOutput = projectRoot.resolve("another-module/build/classes/java/test");
        Files.createDirectories(existingOutput);

        Path existingSource = projectRoot.resolve("ExistingTest.java");
        Files.writeString(existingSource,
                "package existing;\n" +
                        "public class ExistingTest {\n" +
                        "    public void ok() {}\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(existingOutput));
            Iterable<? extends JavaFileObject> sources =
                    fileManager.getJavaFileObjectsFromPaths(List.of(existingSource));
            compiler.getTask(null, fileManager, null, null, null, sources).call();
        }

        Path testsDir = projectRoot.resolve("src/test/java/sample");
        Files.createDirectories(testsDir);

        Path dependentTest = testsDir.resolve("DependentOnExistingTest.java");
        Files.writeString(dependentTest,
                "package sample;\n" +
                        "import existing.ExistingTest;\n" +
                        "public class DependentOnExistingTest {\n" +
                        "    public void ok() { new ExistingTest().ok(); }\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        GradleCompilerInvoker invoker = new GradleCompilerInvoker(new PipelineLogger(projectRoot));

        CompileResult result = invoker.compileAllTests(projectRoot, "all-tests");

        assertTrue(result.success());
        Path outputDir = projectRoot.resolve("build/classes/java/test/sample");
        assertTrue(Files.exists(outputDir.resolve("DependentOnExistingTest.class")));
    }

    @Test
    void cachesOnlySrcTestJavaEntriesAndLogsResults(@TempDir Path projectRoot) throws Exception {
        Path testsDir = projectRoot.resolve("src/test/java/sample");
        Files.createDirectories(testsDir);

        Path firstTest = testsDir.resolve("DirectoryCacheFirstTest.java");
        Files.writeString(firstTest,
                "package sample;\n" +
                        "public class DirectoryCacheFirstTest {\n" +
                        "    public void ok() {}\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        Path secondTest = testsDir.resolve("DirectoryCacheSecondTest.java");
        Files.writeString(secondTest,
                "package sample;\n" +
                        "public class DirectoryCacheSecondTest {\n" +
                        "    public void ok() {}\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        Path otherSource = projectRoot.resolve("src/integrationTest/java/OtherTest.java");
        Files.createDirectories(otherSource.getParent());
        Files.writeString(otherSource,
                "package sample;\n" +
                        "public class OtherTest {\n" +
                        "    public void ok() {}\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        GradleCompilerInvoker invoker = new GradleCompilerInvoker(new PipelineLogger(projectRoot));

        CompileResult result = invoker.compileAllTests(projectRoot, "all-tests");

        assertTrue(result.success());
        assertNotNull(CompilationCache.getInstance().get(firstTest));
        assertNotNull(CompilationCache.getInstance().get(secondTest));
        assertNull(CompilationCache.getInstance().get(otherSource));
        assertTrue(result.messages().stream().anyMatch(message -> message.contains(firstTest.getFileName().toString())));
        assertTrue(result.messages().stream().anyMatch(message -> message.contains(secondTest.getFileName().toString())));
    }

    @Test
    void compileAllTestsAggregatesClasspathAndCachesEachFileWithErrors(@TempDir Path projectRoot) throws Exception {
        Path helpersDir = projectRoot.resolve("tmp-helpers");
        Files.createDirectories(helpersDir);

        Path helperOneSource = helpersDir.resolve("helpers/HelperOne.java");
        Files.createDirectories(helperOneSource.getParent());
        Files.writeString(helperOneSource,
                "package helpers;\n" +
                        "public class HelperOne {\n" +
                        "    public String name() { return \"one\"; }\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        Path helperTwoSource = helpersDir.resolve("helpers/HelperTwo.java");
        Files.createDirectories(helperTwoSource.getParent());
        Files.writeString(helperTwoSource,
                "package helpers;\n" +
                        "public class HelperTwo {\n" +
                        "    public String name() { return \"two\"; }\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        Path helperOneOutput = projectRoot.resolve("module-one/build/classes/java/test");
        Path helperTwoOutput = projectRoot.resolve("module-two/build/classes/java/test");
        Files.createDirectories(helperOneOutput);
        Files.createDirectories(helperTwoOutput);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(helperOneOutput));
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(List.of(helperOneSource));
            compiler.getTask(null, fileManager, null, null, null, units).call();
        }
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(helperTwoOutput));
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(List.of(helperTwoSource));
            compiler.getTask(null, fileManager, null, null, null, units).call();
        }

        Path testsDir = projectRoot.resolve("src/test/java/sample");
        Files.createDirectories(testsDir);

        Path aggregatedTest = testsDir.resolve("AggregatedClasspathTest.java");
        Files.writeString(aggregatedTest,
                "package sample;\n" +
                        "import helpers.HelperOne;\n" +
                        "import helpers.HelperTwo;\n" +
                        "public class AggregatedClasspathTest {\n" +
                        "    public void ok() {\n" +
                        "        new HelperOne().name();\n" +
                        "        new HelperTwo().name();\n" +
                        "    }\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        Path secondTest = testsDir.resolve("CachedPerFileTest.java");
        Files.writeString(secondTest,
                "package sample;\n" +
                        "public class CachedPerFileTest {\n" +
                        "    public void ok() {}\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        GradleCompilerInvoker invoker = new GradleCompilerInvoker(new PipelineLogger(projectRoot));
        CompileResult initialResult = invoker.compileAllTests(projectRoot, "all-tests");

        assertTrue(initialResult.success());
        Path outputDir = projectRoot.resolve("build/classes/java/test/sample");
        assertTrue(Files.exists(outputDir.resolve("AggregatedClasspathTest.class")));
        assertTrue(Files.exists(outputDir.resolve("CachedPerFileTest.class")));
        assertNotNull(CompilationCache.getInstance().get(aggregatedTest));
        assertNotNull(CompilationCache.getInstance().get(secondTest));
        assertTrue(initialResult.messages().stream().anyMatch(message -> message.contains("AggregatedClasspathTest")));
        assertTrue(initialResult.messages().stream().anyMatch(message -> message.contains("CachedPerFileTest")));

        Path brokenTest = projectRoot.resolve("second-module/src/test/java/broken/BrokenTest.java");
        Files.createDirectories(brokenTest.getParent());
        Files.writeString(brokenTest,
                "package broken;\n" +
                        "public class BrokenTest {\n" +
                        "    public void broken(\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        CompileResult failureResult = invoker.compileAllTests(projectRoot, "all-tests");

        assertFalse(failureResult.success());
        assertTrue(CompilationCache.getInstance().get(aggregatedTest).result().success());
        CompileResult brokenCacheResult = CompilationCache.getInstance().get(brokenTest).result();
        assertFalse(brokenCacheResult.success());
        assertTrue(brokenCacheResult.stderr().contains("BrokenTest.java"));
        assertTrue(failureResult.messages().stream().anyMatch(message -> message.contains("BrokenTest")));
    }

    @Test
    void compileAllTestsAccumulatesAllDiagnosticsAndCachesPerFile(@TempDir Path projectRoot) throws Exception {
        Path testsDir = projectRoot.resolve("src/test/java/sample");
        Files.createDirectories(testsDir);

        Path firstBroken = testsDir.resolve("FirstBroken.java");
        Files.writeString(firstBroken,
                "package sample;\n" +
                        "public class FirstBroken {\n" +
                        "    public void bad(\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        Path secondBroken = testsDir.resolve("SecondBroken.java");
        Files.writeString(secondBroken,
                "package sample;\n" +
                        "public class SecondBroken {\n" +
                        "    public void alsoBad(\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        GradleCompilerInvoker invoker = new GradleCompilerInvoker(new PipelineLogger(projectRoot));

        CompileResult result = invoker.compileAllTests(projectRoot, "all-tests");

        assertFalse(result.success());
        assertTrue(result.stderr().contains("FirstBroken.java"));
        assertTrue(result.stderr().contains("SecondBroken.java"));

        CompileResult firstCache = CompilationCache.getInstance().get(firstBroken).result();
        CompileResult secondCache = CompilationCache.getInstance().get(secondBroken).result();

        assertFalse(firstCache.success());
        assertFalse(secondCache.success());
        assertTrue(firstCache.stderr().contains("FirstBroken.java"));
        assertTrue(secondCache.stderr().contains("SecondBroken.java"));
        assertFalse(firstCache.stderr().contains("SecondBroken.java"));
        assertFalse(secondCache.stderr().contains("FirstBroken.java"));
    }

    @Test
    void compileAllTestsReportsDiagnosticsForManyFailingFiles(@TempDir Path projectRoot) throws Exception {
        Path testsDir = projectRoot.resolve("src/test/java/sample");
        Files.createDirectories(testsDir);

        List<Path> failingSources = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Path source = testsDir.resolve("Broken" + i + ".java");
            Files.writeString(source,
                    "package sample;\n" +
                            "public class Broken" + i + " {\n" +
                            "    public void missingParen(\n" +
                            "}\n",
                    StandardCharsets.UTF_8);
            failingSources.add(source);
        }

        GradleCompilerInvoker invoker = new GradleCompilerInvoker(new PipelineLogger(projectRoot));

        CompileResult result = invoker.compileAllTests(projectRoot, "all-tests");

        assertFalse(result.success());
        for (Path failingSource : failingSources) {
            String fileName = failingSource.getFileName().toString();
            assertTrue(result.stderr().contains(fileName), "Missing diagnostics for " + fileName);

            CompileResult cachedResult = CompilationCache.getInstance().get(failingSource).result();
            assertNotNull(cachedResult, "Cache missing entry for " + fileName);
            assertFalse(cachedResult.success(), "Cached result should fail for " + fileName);
            assertTrue(cachedResult.stderr().contains(fileName), "Cached diagnostics should reference " + fileName);
        }
    }

    @Test
    void refreshesClasspathWhenDependenciesFileChanges(@TempDir Path projectRoot) throws Exception {
        Path testsDir = projectRoot.resolve("src/test/java/sample");
        Files.createDirectories(testsDir);
        Path testFile = testsDir.resolve("DependencyAwareTest.java");
        Files.writeString(testFile,
                "package sample;\n" +
                        "public class DependencyAwareTest {\n" +
                        "    public void ok() {}\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        Path dependencyFile = projectRoot.resolve("gradle.dependencies");
        Files.writeString(dependencyFile, "deps", StandardCharsets.UTF_8);

        GradleCompilerInvoker invoker = new GradleCompilerInvoker(new PipelineLogger(projectRoot));
        assertTrue(invoker.compile(projectRoot, testFile, "ok").success());

        TimeUnit.MILLISECONDS.sleep(5);
        Files.writeString(dependencyFile, "\nnew-dep", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        Field cacheField = GradleCompilerInvoker.class.getDeclaredField("CLASSPATH_CACHE");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentMap<String, Object> classpathCache = (ConcurrentMap<String, Object>) cacheField.get(null);

        Constructor<?> ctor = Class.forName("com.gigachat.unit.tests.generator.compile.GradleCompilerInvoker$ClasspathResolution")
                .getDeclaredConstructor(Set.class, boolean.class);
        ctor.setAccessible(true);
        Object dummyResolution = ctor.newInstance(Set.of(projectRoot.resolve("lib/dummy.jar")), true);
        String cacheKey = projectRoot.toAbsolutePath().normalize() + "|";
        classpathCache.put(cacheKey, dummyResolution);

        invoker.compile(projectRoot, testFile, "ok");

        assertFalse(classpathCache.containsKey(cacheKey));
    }

    @Test
    void refreshesClasspathWhenAnyBuildFileChanges(@TempDir Path projectRoot) throws Exception {
        Path testsDir = projectRoot.resolve("src/test/java/sample");
        Files.createDirectories(testsDir);
        Path testFile = testsDir.resolve("ModuleAwareTest.java");
        Files.writeString(testFile,
                "package sample;\n" +
                        "public class ModuleAwareTest {\n" +
                        "    public void ok() {}\n" +
                        "}\n",
                StandardCharsets.UTF_8);

        Path moduleBuildFile = projectRoot.resolve("modules/module-a/build.gradle");
        Files.createDirectories(moduleBuildFile.getParent());
        Files.writeString(moduleBuildFile, "plugins { id 'java' }\n", StandardCharsets.UTF_8);

        GradleCompilerInvoker invoker = new GradleCompilerInvoker(new PipelineLogger(projectRoot));
        assertTrue(invoker.compile(projectRoot, testFile, "ok").success());

        TimeUnit.MILLISECONDS.sleep(5);
        Files.writeString(moduleBuildFile, "\ndependencies { }\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        Field cacheField = GradleCompilerInvoker.class.getDeclaredField("CLASSPATH_CACHE");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentMap<String, Object> classpathCache = (ConcurrentMap<String, Object>) cacheField.get(null);

        Constructor<?> ctor = Class.forName("com.gigachat.unit.tests.generator.compile.GradleCompilerInvoker$ClasspathResolution")
                .getDeclaredConstructor(Set.class, boolean.class);
        ctor.setAccessible(true);
        Object dummyResolution = ctor.newInstance(new HashSet<>(Set.of(projectRoot.resolve("lib/dummy.jar"))), true);
        String cacheKey = projectRoot.toAbsolutePath().normalize() + "|";
        classpathCache.put(cacheKey, dummyResolution);

        invoker.compile(projectRoot, testFile, "ok");

        assertFalse(classpathCache.containsKey(cacheKey));
    }
}
