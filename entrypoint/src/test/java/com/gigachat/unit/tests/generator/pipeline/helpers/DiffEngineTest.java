package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void mergeShouldReturnRenamedSnippetWhenMethodNameCollides() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/ApplicationTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app;

                import org.junit.jupiter.api.Test;

                public class ApplicationTest {

                    @Test
                    void shouldInvokeStart() {
                    }
                }
                """);
        TestClassInfo classInfo = new TestClassInfo("com.example.app.Application",
                "ApplicationTest",
                testFile,
                List.of(),
                List.of(),
                null);
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "ApplicationTest",
                "shouldInvokeStart",
                """
                        @Test
                        void shouldInvokeStart() {
                            application.start();
                        }
                        """,
                List.of()
        );

        DiffEngine diffEngine = new DiffEngine(new TestClassWriter(new PipelineLogger(tempDir)), new PipelineLogger(tempDir));
        DiffEngine.MergeResult mergeResult = diffEngine.merge(classInfo, snippet);

        assertTrue(mergeResult.changed());
        assertNotEquals("shouldInvokeStart", mergeResult.mergedSnippet().methodName());
        assertTrue(mergeResult.mergedSnippet().methodName().startsWith("shouldInvokeStartVariant"));
        assertTrue(Files.readString(testFile).contains("void " + mergeResult.mergedSnippet().methodName() + "()"));
    }
}
