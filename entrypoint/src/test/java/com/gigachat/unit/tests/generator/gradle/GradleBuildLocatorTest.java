package com.gigachat.unit.tests.generator.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleBuildLocatorTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsEnclosingBuildRootForNestedModuleWithoutOwnWrapper() throws Exception {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'root'");
        Files.writeString(tempDir.resolve("gradlew"), "#!/bin/sh");

        Path nestedProject = tempDir.resolve("example-project");
        Files.createDirectories(nestedProject);
        Files.writeString(nestedProject.resolve("build.gradle"), "plugins { id 'java' }");

        Path resolved = GradleBuildLocator.findInvocationRoot(nestedProject);

        assertEquals(tempDir, resolved);
    }

    @Test
    void keepsStandaloneProjectWhenNoEnclosingGradleBuildIsDetected() throws Exception {
        Path standaloneProject = tempDir.resolve("standalone");
        Files.createDirectories(standaloneProject);
        Files.writeString(standaloneProject.resolve("build.gradle"), "plugins { id 'java' }");

        Path resolved = GradleBuildLocator.findInvocationRoot(standaloneProject);

        assertEquals(standaloneProject, resolved);
    }

    @Test
    void resolvesInstalledGradleWhenBuildExistsWithoutWrapper() throws Exception {
        Path standaloneProject = tempDir.resolve("standalone");
        Files.createDirectories(standaloneProject);
        Files.writeString(standaloneProject.resolve("build.gradle"), "plugins { id 'java' }");

        assertTrue(GradleBuildLocator.hasGradleBuildDefinition(standaloneProject));
        assertFalse(GradleBuildLocator.hasGradleWrapper(standaloneProject));
        assertEquals("gradle", GradleBuildLocator.resolveGradleCommand(standaloneProject));
    }

    @Test
    void resolvesWrapperWhenPresent() throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'project'");
        Files.writeString(project.resolve("gradlew"), "#!/bin/sh");

        assertTrue(GradleBuildLocator.hasGradleWrapper(project));
        assertEquals(project.resolve("gradlew").toAbsolutePath().normalize().toString(),
                GradleBuildLocator.resolveGradleCommand(project));
    }

    @Test
    void resolvesAncestorWrapperForNestedStandaloneBuild() throws Exception {
        Files.writeString(tempDir.resolve("gradlew"), "#!/bin/sh");

        Path nestedProject = tempDir.resolve("nested/example-project");
        Files.createDirectories(nestedProject);
        Files.writeString(nestedProject.resolve("settings.gradle"), "rootProject.name = 'example-project'");
        Files.writeString(nestedProject.resolve("build.gradle"), "plugins { id 'java' }");

        assertEquals(tempDir.resolve("gradlew").toAbsolutePath().normalize().toString(),
                GradleBuildLocator.resolveGradleCommand(nestedProject));
    }
}
