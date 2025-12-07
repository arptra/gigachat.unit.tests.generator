package com.example.importanalyzer.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyResolverTest {

    @Test
    void collectsProjectOutputsAndLibsWithoutScanningCaches() throws Exception {
        Path temp = Files.createTempDirectory("resolver-test");
        Path libs = Files.createDirectories(temp.resolve("libs"));
        Path jar = libs.resolve("demo.jar");
        Files.writeString(jar, "dummy");

        DependencyResolver resolver = new DependencyResolver();
        Set<Path> artifacts = resolver.findDependencyArtifacts(temp);

        assertTrue(artifacts.contains(jar));
        assertEquals(1, artifacts.size(), "Only local outputs/libs should be considered without build metadata");
    }

    @Test
    void detectsGradleRootAboveSubmodule() throws Exception {
        Path temp = Files.createTempDirectory("resolver-root");
        Path root = temp.resolve("repo");
        Path sub = root.resolve("example");
        Files.createDirectories(sub);
        Files.writeString(root.resolve("settings.gradle.kts"), "// root settings");

        DependencyResolver resolver = new DependencyResolver();
        Path resolved = resolver.findGradleRoot(sub);

        assertEquals(root, resolved);
    }
}
