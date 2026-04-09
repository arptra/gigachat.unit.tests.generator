package com.gigachat.unit.tests.generator.execute;

import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JUnitExecutionInvokerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldUseFullyQualifiedTestPatternFromPackageDeclaration() throws Exception {
        Path testFile = tempDir.resolve("module-a/src/test/java/com/example/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.service;

                class UserServiceTest {
                }
                """);

        JUnitExecutionInvoker invoker = new JUnitExecutionInvoker(new PipelineLogger(tempDir));

        String pattern = invoker.determineTestPattern(tempDir, testFile, "shouldRegisterUser");

        assertEquals("com.example.service.UserServiceTest.shouldRegisterUser", pattern);
    }

    @Test
    void shouldTargetModuleSpecificGradleTaskWhenTestLivesInSubmodule() throws Exception {
        Path gradlew = tempDir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh");
        Path testFile = tempDir.resolve("module-a/src/test/java/com/example/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "package com.example.service; class UserServiceTest {}");

        JUnitExecutionInvoker invoker = new JUnitExecutionInvoker(new PipelineLogger(tempDir));

        List<String> command = invoker.buildGradleCommand(tempDir, testFile, "shouldRegisterUser", false);

        assertTrue(command.contains(":module-a:test"));
        assertTrue(command.contains("com.example.service.UserServiceTest.shouldRegisterUser"));
    }
}
