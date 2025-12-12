package com.example.importanalyzer.core;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.idea.IdeaDependency;
import org.gradle.tooling.model.idea.IdeaModule;
import org.gradle.tooling.model.idea.IdeaProject;
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class DependencyResolver {
    public Set<Path> findDependencyArtifacts(Path projectRoot) {
        Set<Path> jars = new HashSet<>();
        Path gradleRoot = findGradleRoot(projectRoot);
        jars.addAll(resolveGradleDependencies(gradleRoot));
        jars.addAll(projectBuildOutputs(gradleRoot));
        if (!gradleRoot.equals(projectRoot)) {
            jars.addAll(projectBuildOutputs(projectRoot));
        }
        Path libs = projectRoot.resolve("libs");
        scanDirectory(libs, jars);
        return jars;
    }

    public Set<Path> findSiblingSourceRoots(Path projectRoot) {
        Set<Path> roots = new HashSet<>();
        Path gradleRoot = findGradleRoot(projectRoot);
        if (!Files.exists(gradleRoot)) {
            return roots;
        }
        try {
            Files.list(gradleRoot)
                    .filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(projectRoot))
                    .forEach(dir -> {
                        Path src = dir.resolve("src/main/java");
                        if (Files.exists(src)) {
                            roots.add(src);
                        }
                    });
        } catch (IOException ignored) {
        }
        return roots;
    }

    Path findGradleRoot(Path start) {
        Path current = start;
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle")) || Files.exists(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        return start;
    }

    private Set<Path> projectBuildOutputs(Path projectRoot) {
        Set<Path> outputs = new HashSet<>();
        Path buildLibs = projectRoot.resolve("build/libs");
        scanDirectory(buildLibs, outputs);
        Path mainClasses = projectRoot.resolve("build/classes/java/main");
        if (Files.exists(mainClasses)) {
            outputs.add(mainClasses);
        }
        Path testClasses = projectRoot.resolve("build/classes/java/test");
        if (Files.exists(testClasses)) {
            outputs.add(testClasses);
        }
        return outputs;
    }

    private Set<Path> resolveGradleDependencies(Path projectRoot) {
        Set<Path> artifacts = new HashSet<>();
        boolean gradleProject = Files.exists(projectRoot.resolve("build.gradle"))
                || Files.exists(projectRoot.resolve("build.gradle.kts"));
        if (!gradleProject) {
            return artifacts;
        }

        ProjectConnection connection = null;
        try {
            connection = GradleConnector.newConnector()
                    .forProjectDirectory(projectRoot.toFile())
                    .connect();
            IdeaProject project = connection.getModel(IdeaProject.class);
            for (IdeaModule module : project.getModules()) {
                Optional.ofNullable(module.getCompilerOutput())
                        .ifPresent(output -> {
                            File mainDir = output.getOutputDir();
                            if (mainDir != null && mainDir.exists()) {
                                artifacts.add(mainDir.toPath());
                            }
                            File testDir = output.getTestOutputDir();
                            if (testDir != null && testDir.exists()) {
                                artifacts.add(testDir.toPath());
                            }
                        });

                for (IdeaDependency dependency : module.getDependencies()) {
                    if (dependency instanceof IdeaSingleEntryLibraryDependency lib) {
                        File file = lib.getFile();
                        if (file != null && file.exists()) {
                            artifacts.add(file.toPath());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Fallback: rely on local build outputs and libs folder only.
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
        return artifacts;
    }

    private void scanDirectory(Path dir, Set<Path> jars) {
        if (!Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.toString().endsWith(".jar")) {
                        jars.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }
}
