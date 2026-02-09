package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.reasoning.model.ProjectContextSummary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects lightweight project metadata for the reasoning prompts.
 */
public class ProjectContextCollector {

    private static final Pattern DEPENDENCY_PATTERN = Pattern.compile("(testImplementation|implementation)\\s*(?:\\(|\\s+)['\\\"](?<dep>[^'\\\"]+)['\\\"]\\)?");

    private final Path projectRoot;

    public ProjectContextCollector(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /**
     * Captures source roots, test roots and dependencies from the project.
     *
     * @return summary describing the current project layout
     */
    public ProjectContextSummary collect() {
        ProjectContextSummary summary = new ProjectContextSummary();
        summary.setSourceRoots(detectSourceRoots(false));
        summary.setTestSourceRoots(detectSourceRoots(true));
        summary.setDependencies(readDeclaredDependencies());
        return summary;
    }

    private List<String> detectSourceRoots(boolean testRoots) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        Path defaultRoot = projectRoot.resolve(testRoots ? "src/test/java" : "src/main/java");
        if (Files.isDirectory(defaultRoot)) {
            roots.add(defaultRoot.toAbsolutePath().normalize().toString());
        }
        try (var paths = Files.walk(projectRoot, 6)) {
            for (Path path : (Iterable<Path>) paths.filter(Files::isDirectory)::iterator) {
                if (!isJavaSourceRoot(path, testRoots)) {
                    continue;
                }
                roots.add(path.toAbsolutePath().normalize().toString());
            }
        } catch (IOException ignored) {
            // best effort
        }
        return List.copyOf(roots);
    }

    private boolean isJavaSourceRoot(Path path, boolean testRoots) {
        if (path == null || path.getNameCount() < 3) {
            return false;
        }
        int count = path.getNameCount();
        String javaSegment = path.getName(count - 1).toString();
        String sourceSet = path.getName(count - 2).toString().toLowerCase(Locale.ROOT);
        String srcSegment = path.getName(count - 3).toString();
        if (!"java".equals(javaSegment) || !"src".equals(srcSegment)) {
            return false;
        }
        return testRoots ? sourceSet.contains("test") : "main".equals(sourceSet);
    }

    private List<String> readDeclaredDependencies() {
        LinkedHashSet<String> dependencies = new LinkedHashSet<>();
        for (Path buildFile : discoverBuildFiles()) {
            try {
                for (String line : Files.readAllLines(buildFile, StandardCharsets.UTF_8)) {
                    Matcher matcher = DEPENDENCY_PATTERN.matcher(line.trim());
                    if (matcher.find()) {
                        dependencies.add(matcher.group("dep"));
                    }
                }
            } catch (IOException ignored) {
                // best effort
            }
        }
        return List.copyOf(dependencies);
    }

    private List<Path> discoverBuildFiles() {
        LinkedHashSet<Path> buildFiles = new LinkedHashSet<>();
        Path rootGroovy = projectRoot.resolve("build.gradle");
        if (Files.exists(rootGroovy)) {
            buildFiles.add(rootGroovy);
        }
        Path rootKts = projectRoot.resolve("build.gradle.kts");
        if (Files.exists(rootKts)) {
            buildFiles.add(rootKts);
        }
        try (var paths = Files.walk(projectRoot, 4)) {
            for (Path file : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                String name = file.getFileName().toString();
                if ("build.gradle".equals(name) || "build.gradle.kts".equals(name)) {
                    buildFiles.add(file.toAbsolutePath().normalize());
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
        return new ArrayList<>(buildFiles);
    }
}
