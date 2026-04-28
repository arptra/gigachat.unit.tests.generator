package com.gigachat.unit.tests.generator.execute;

import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void shouldScopeWholeClassExecutionWhenMethodNameIsNullForConcreteTestFile() throws Exception {
        Path gradlew = tempDir.resolve("gradlew");
        Files.writeString(gradlew, """
                #!/bin/sh
                echo "$@"
                exit 0
                """);
        assertTrue(gradlew.toFile().setExecutable(true));
        Path testFile = tempDir.resolve("src/test/java/com/example/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "package com.example.service; class UserServiceTest {}");

        JUnitExecutionInvoker invoker = new JUnitExecutionInvoker(new PipelineLogger(tempDir));

        ExecuteResult result = invoker.execute(tempDir, testFile, null);

        assertTrue(result.success());
        assertTrue(result.stdout().contains("test"));
        assertTrue(result.stdout().contains("--tests"));
        assertTrue(result.stdout().contains("com.example.service.UserServiceTest"));
    }

    @Test
    void shouldKeepWholeSuiteExecutionUnfilteredWhenNoConcreteTestFileIsProvided() throws Exception {
        Path gradlew = tempDir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh");

        JUnitExecutionInvoker invoker = new JUnitExecutionInvoker(new PipelineLogger(tempDir));

        List<String> command = invoker.buildGradleCommand(tempDir, tempDir, null, true);

        assertTrue(command.contains("test"));
        assertFalse(command.contains("--tests"));
    }

    @Test
    void shouldUseEnclosingBuildRootForNestedProject() throws Exception {
        Path gradlew = tempDir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh");
        Files.writeString(tempDir.resolve("settings.gradle"), """
                include 'example-project'
                """);
        Path nestedProject = tempDir.resolve("example-project");
        Files.createDirectories(nestedProject);
        Files.writeString(nestedProject.resolve("build.gradle"), "plugins { id 'java' }");
        Path testFile = nestedProject.resolve("src/test/java/com/example/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "package com.example.service; class UserServiceTest {}");

        JUnitExecutionInvoker invoker = new JUnitExecutionInvoker(new PipelineLogger(tempDir));

        List<String> command = invoker.buildGradleCommand(nestedProject, testFile, "shouldRegisterUser", false);

        assertEquals(tempDir.resolve("gradlew").toAbsolutePath().normalize().toString(), command.get(0));
        assertTrue(command.contains(":example-project:test"));
    }

    @Test
    void shouldUseDetachedProjectExecutionForNestedStandaloneBuild() throws Exception {
        Path gradlew = tempDir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh");
        Files.writeString(tempDir.resolve("settings.gradle"), """
                include 'entrypoint'
                """);
        Path nestedProject = tempDir.resolve("tmp/example-project-e2e");
        Files.createDirectories(nestedProject);
        Files.writeString(nestedProject.resolve("build.gradle"), "plugins { id 'java' }");
        Path testFile = nestedProject.resolve("src/test/java/com/example/feature/HiddenFeatureTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "package com.example.feature; class HiddenFeatureTest {}");

        JUnitExecutionInvoker invoker = new JUnitExecutionInvoker(new PipelineLogger(tempDir));

        List<String> command = invoker.buildGradleCommand(nestedProject, testFile, "shouldActivateFeature", false);

        assertEquals(tempDir.resolve("gradlew").toAbsolutePath().normalize().toString(), command.get(0));
        assertTrue(command.contains("--settings-file"));
        assertTrue(command.contains(nestedProject.resolve(".gigachat-standalone-settings.gradle").toAbsolutePath().normalize().toString()));
        assertTrue(command.contains("test"));
        assertFalse(command.contains(":tmp:example-project-e2e:test"));
        assertTrue(command.contains("com.example.feature.HiddenFeatureTest.shouldActivateFeature"));
    }

    @Test
    void shouldStreamExecutionOutputIntoPipelineLog() throws Exception {
        Path gradlew = tempDir.resolve("gradlew");
        Files.writeString(gradlew, """
                #!/bin/sh
                echo "streamed stdout line"
                echo "streamed stderr line" >&2
                exit 1
                """);
        assertTrue(gradlew.toFile().setExecutable(true));

        Path testFile = tempDir.resolve("src/test/java/com/example/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.service;

                class UserServiceTest {
                }
                """);

        JUnitExecutionInvoker invoker = new JUnitExecutionInvoker(new PipelineLogger(tempDir));

        ExecuteResult result = invoker.execute(tempDir, testFile, "shouldRegisterUser");

        assertFalse(result.success());
        assertTrue(result.stdout().contains("streamed stdout line"));
        assertTrue(result.stderr().contains("streamed stderr line"));

        String logs = Files.readString(tempDir.resolve(".agent/logs/pipeline.log"));
        assertTrue(logs.contains("[EXECUTION] Command: "));
        assertTrue(logs.contains("[EXECUTION][stdout] streamed stdout line"));
        assertTrue(logs.contains("[EXECUTION][stderr] streamed stderr line"));
        assertTrue(logs.contains("[EXECUTION] Process finished with exitCode=1"));
    }
}
