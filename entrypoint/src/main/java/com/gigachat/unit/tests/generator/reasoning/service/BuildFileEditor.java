package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Provides simple editing capabilities for the build.gradle file to append dependencies
 * requested by the reasoning loop.
 */
public class BuildFileEditor {

    private final Path projectRoot;
    private final PipelineLogger logger;

    public BuildFileEditor(Path projectRoot, PipelineLogger logger) {
        this.projectRoot = projectRoot;
        this.logger = logger;
    }

    /**
     * Adds a {@code testImplementation} dependency if it is not already present.
     *
     * @param dependencyNotation dependency notation, e.g. {@code "org.junit.jupiter:junit-jupiter-api:5.10.0"}
     */
    public void addTestDependency(String dependencyNotation) {
        if (dependencyNotation == null || dependencyNotation.isBlank()) {
            return;
        }
        Path buildFile = projectRoot.resolve("build.gradle");
        if (!Files.exists(buildFile)) {
            logger.warn("build.gradle not found at " + buildFile);
            return;
        }
        try {
            List<String> lines = Files.readAllLines(buildFile, StandardCharsets.UTF_8);
            String dependencyLine = String.format("    testImplementation(\"%s\")", dependencyNotation.trim());
            if (lines.stream().anyMatch(line -> line.contains(dependencyNotation))) {
                logger.info("Dependency already declared: " + dependencyNotation);
                return;
            }
            int dependenciesIndex = findDependenciesBlock(lines);
            if (dependenciesIndex == -1) {
                lines.add("dependencies {");
                lines.add(dependencyLine);
                lines.add("}");
            } else {
                lines.add(dependenciesIndex + 1, dependencyLine);
            }
            Files.write(buildFile, lines, StandardCharsets.UTF_8);
            logger.info("Added test dependency: " + dependencyNotation);
        } catch (IOException exception) {
            logger.error("Failed to update build.gradle: " + exception.getMessage(), exception);
        }
    }

    private int findDependenciesBlock(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith("dependencies")) {
                return i;
            }
        }
        return -1;
    }
}

