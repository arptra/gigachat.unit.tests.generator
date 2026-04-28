package com.gigachat.unit.tests.generator.coverage;

import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JaCoCoCoverageInvokerTest {

    @Test
    void shouldCollectMethodCoverageFromExampleProject() {
        Path projectRoot = Path.of("..", "example-project").toAbsolutePath().normalize();
        Path testFile = projectRoot.resolve("src/test/java/com/example/app/service/NotificationServiceTest.java");

        JaCoCoCoverageInvoker invoker = new JaCoCoCoverageInvoker(new PipelineLogger(projectRoot));
        CoverageResult result = invoker.measure(projectRoot,
                testFile,
                "testSendWelcome",
                "NotificationService",
                "sendWelcome(");

        assertTrue(result.success(), result.describeFailure());
        assertTrue(result.reportGenerated(), result.describeFailure());
        assertNotNull(result.summary(), "Expected method-level coverage summary");
        assertTrue(result.summary().coveredLines() > 0, "Expected covered lines for NotificationService.sendWelcome");
        assertScopedToTargetClass(result);
        assertTrue(Files.notExists(projectRoot.resolve(".gigachat-jacoco-scope.init.gradle")),
                "Coverage scope init script should not be left in the project root");
    }

    private void assertScopedToTargetClass(CoverageResult result) {
        Path xmlReport = result.xmlReportOptional().orElseThrow();
        String xml = read(xmlReport);
        assertTrue(xml.contains("com/example/app/service/NotificationService"),
                "Expected scoped JaCoCo report to include NotificationService");
        assertTrue(!xml.contains("com/example/app/Application"),
                "Scoped JaCoCo report should not include unrelated Application coverage");
    }

    private String read(Path file) {
        try {
            return java.nio.file.Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read coverage report: " + file, exception);
        }
    }
}
