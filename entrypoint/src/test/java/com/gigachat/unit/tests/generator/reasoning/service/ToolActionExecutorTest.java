package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionStep;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolActionExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void searchSymbolStopsAtFirstMatchAndSkipsTests() throws IOException {
        Path mainDir = tempDir.resolve("src/main/java/example");
        Files.createDirectories(mainDir);
        Path testDir = tempDir.resolve("src/test/java/example");
        Files.createDirectories(testDir);

        Path mainFile = mainDir.resolve("Main.java");
        Files.writeString(mainFile, "class Main { void m() { TargetSymbol(); } }");
        Path anotherMain = mainDir.resolve("Secondary.java");
        Files.writeString(anotherMain, "class Secondary { void m() { /* no symbol here */ } }");
        Path testFile = testDir.resolve("MainTest.java");
        Files.writeString(testFile, "class MainTest { void t() { TargetSymbol(); } }");

        ToolActionExecutor executor = new ToolActionExecutor(
                new BuildFileEditor(tempDir),
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(false, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "example.MainTest",
                "test"
        );

        ToolActionStep step = new ToolActionStep(ToolActionType.SEARCH_SYMBOL, Map.of("symbol", "Main"));

        Map<String, Object> info = executor.executeStep(step).getInformation();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) info.get("symbolSearchResults");
        Map<String, Object> result = results.get(0);
        assertEquals("FOUND_ONE", result.get("searchStatus"));
        assertEquals("PROJECT_SOURCE", result.get("source"));
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) result.get("candidates");
        assertEquals(1, candidates.size());
        assertEquals("Main", candidates.get(0));
    }

    @Test
    void addImportWorksForModuleTestPath() throws IOException {
        Path moduleRoot = tempDir.resolve("entrypoint");
        Path testDir = moduleRoot.resolve("src/test/java/example");
        Files.createDirectories(testDir);
        Path testFile = testDir.resolve("MainTest.java");
        Files.writeString(testFile, "package example;\n\nclass MainTest {\n}\n");

        ToolActionExecutor executor = new ToolActionExecutor(
                new BuildFileEditor(moduleRoot),
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(true, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "example.MainTest",
                "test"
        );

        ToolActionStep step = new ToolActionStep(ToolActionType.ADD_IMPORT, Map.of(
                "path", "entrypoint/src/test/java/example/MainTest.java",
                "import", "org.junit.jupiter.api.Test"
        ));

        var result = executor.executeStep(step);
        String updated = Files.readString(testFile);

        assertEquals(1, result.getPerformedActions().size());
        assertTrue(updated.contains("import org.junit.jupiter.api.Test;"));
    }

    @Test
    void readClassResolvesClassesInsideModuleSourceRoot() throws IOException {
        Path moduleRoot = tempDir.resolve("entrypoint");
        Path mainDir = moduleRoot.resolve("src/main/java/example");
        Path testDir = moduleRoot.resolve("src/test/java/example");
        Files.createDirectories(mainDir);
        Files.createDirectories(testDir);
        Path mainFile = mainDir.resolve("Main.java");
        Files.writeString(mainFile, "package example;\nclass Main {}\n");
        Path testFile = testDir.resolve("MainTest.java");
        Files.writeString(testFile, "package example;\nclass MainTest {}\n");

        ToolActionExecutor executor = new ToolActionExecutor(
                new BuildFileEditor(moduleRoot),
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(true, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "example.MainTest",
                "test"
        );

        ToolActionStep step = new ToolActionStep(ToolActionType.READ_CLASS, Map.of("className", "example.Main"));
        var result = executor.executeStep(step);
        @SuppressWarnings("unchecked")
        Map<String, String> cacheUpdates = (Map<String, String>) result.getInformation().get("contextCacheUpdates");

        assertTrue(cacheUpdates.keySet().stream().anyMatch(key -> key.endsWith("Main.java")));
        assertTrue(cacheUpdates.values().stream().anyMatch(content -> content.contains("class Main")));
    }
}
