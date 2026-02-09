package com.gigachat.unit.tests.generator.reasoning.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides simple editing capabilities for the build.gradle file to append dependencies
 * requested by the reasoning loop.
 */
public class BuildFileEditor {

    private static final Pattern DEPENDENCY_PATTERN = Pattern.compile("(testImplementation|implementation)\\s*(?:\\(|\\s+)['\\\"](?<dep>[^'\\\"]+)['\\\"]\\)?");

    private final Path projectRoot;

    public BuildFileEditor(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /**
     * Adds a {@code testImplementation} dependency if it is not already present and returns the updated dependency list.
     *
     * @param dependencyNotation dependency notation, e.g. {@code "org.junit.jupiter:junit-jupiter-api:5.10.0"}
     * @return list of declared dependencies after the update
     */
    public List<String> addTestDependency(String dependencyNotation) {
        if (dependencyNotation == null || dependencyNotation.isBlank()) {
            return List.of();
        }
        Path buildFile = resolveBuildFile();
        if (buildFile == null || !Files.exists(buildFile)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(buildFile, StandardCharsets.UTF_8);
            String dependencyLine = buildFile.getFileName().toString().endsWith(".kts")
                    ? String.format("    testImplementation(\"%s\")", dependencyNotation.trim())
                    : String.format("    testImplementation '%s'", dependencyNotation.trim());
            if (lines.stream().noneMatch(line -> line.contains(dependencyNotation))) {
                int dependenciesIndex = findDependenciesBlock(lines);
                if (dependenciesIndex == -1) {
                    lines.add("dependencies {");
                    lines.add(dependencyLine);
                    lines.add("}");
                } else {
                    lines.add(dependenciesIndex + 1, dependencyLine);
                }
                Files.write(buildFile, lines, StandardCharsets.UTF_8);
            }
            return readDeclaredDependencies(lines);
        } catch (IOException exception) {
            return List.of();
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

    private List<String> readDeclaredDependencies(List<String> lines) {
        List<String> dependencies = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = DEPENDENCY_PATTERN.matcher(line.trim());
            if (matcher.find()) {
                dependencies.add(matcher.group("dep"));
            }
        }
        return dependencies;
    }

    private Path resolveBuildFile() {
        Path groovy = projectRoot.resolve("build.gradle");
        if (Files.exists(groovy)) {
            return groovy;
        }
        Path kotlin = projectRoot.resolve("build.gradle.kts");
        if (Files.exists(kotlin)) {
            return kotlin;
        }
        return null;
    }
}
