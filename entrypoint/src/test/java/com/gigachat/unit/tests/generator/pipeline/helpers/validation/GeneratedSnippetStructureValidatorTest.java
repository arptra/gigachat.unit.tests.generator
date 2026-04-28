package com.gigachat.unit.tests.generator.pipeline.helpers.validation;

import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.pipeline.InvalidLLMResponseException;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeneratedSnippetStructureValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void classpathImportWithSameSimpleNameAsSourceTypeIsNotRejected() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/bd"));
        Files.writeString(tempDir.resolve("src/main/java/bd/Date.java"), """
                package bd;

                public class Date {
                }
                """);
        TestClassInfo classInfo = new TestClassInfo(
                "Example",
                "ExampleTest",
                tempDir.resolve("src/test/java/example/ExampleTest.java"),
                List.of(),
                List.of());
        GeneratedSnippetStructureValidator validator = new GeneratedSnippetStructureValidator(
                new PipelineLogger(tempDir),
                (projectRoot, testClassFile, fqcn) -> "rt.Date".equals(fqcn));

        assertDoesNotThrow(() -> validator.ensureProjectImportsAreResolvable(
                tempDir,
                classInfo,
                StaticJavaParser.parse("""
                        package example;

                        import rt.Date;

                        class ExampleTest {
                            private Date value;
                        }
                        """)));
    }

    @Test
    void wrongProjectPackageIsStillRejectedForSamePackageFamily() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example/app/service"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/app/service/NotificationService.java"), """
                package com.example.app.service;

                public class NotificationService {
                }
                """);
        GeneratedSnippetStructureValidator validator = new GeneratedSnippetStructureValidator(
                new PipelineLogger(tempDir),
                (projectRoot, testClassFile, fqcn) -> false);

        assertThrows(InvalidLLMResponseException.class, () -> validator.ensureProjectImportsAreResolvable(
                tempDir,
                StaticJavaParser.parse("""
                        package com.example.app.service;

                        import com.example.app.util.NotificationService;

                        class UserServiceTest {
                            private NotificationService notificationService;
                        }
                        """)));
    }
}
