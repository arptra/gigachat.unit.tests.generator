package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.reasoning.model.ProjectContextSummary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects lightweight project metadata for the reasoning prompts.
 */
public class ProjectContextCollector {

    private static final Pattern DEPENDENCY_PATTERN = Pattern.compile("(testImplementation|implementation)\\s*\\(\\\"(?<dep>[^\\\"]+)\\\"\\)");

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
        summary.setSourceRoots(detectStandardRoots("src/main/java"));
        summary.setTestSourceRoots(detectStandardRoots("src/test/java"));
        summary.setDependencies(readDeclaredDependencies());
        return summary;
    }

    private List<String> detectStandardRoots(String relativePath) {
        Path path = projectRoot.resolve(relativePath);
        if (Files.exists(path)) {
            return List.of(path.toAbsolutePath().normalize().toString());
        }
        return List.of();
    }

    private List<String> readDeclaredDependencies() {
        Path buildFile = projectRoot.resolve("build.gradle");
        if (!Files.exists(buildFile)) {
            return List.of();
        }
        List<String> dependencies = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(buildFile, StandardCharsets.UTF_8)) {
                Matcher matcher = DEPENDENCY_PATTERN.matcher(line.trim());
                if (matcher.find()) {
                    dependencies.add(matcher.group("dep"));
                }
            }
        } catch (IOException exception) {
            return List.of();
        }
        return dependencies;
    }
}

