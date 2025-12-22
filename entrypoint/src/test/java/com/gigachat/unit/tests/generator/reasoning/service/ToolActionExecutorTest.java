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
}
