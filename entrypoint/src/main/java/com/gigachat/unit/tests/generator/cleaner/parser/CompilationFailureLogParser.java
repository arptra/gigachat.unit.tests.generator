package com.gigachat.unit.tests.generator.cleaner.parser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Gradle/Javac compilation logs to locate failing test sources.
 */
public class CompilationFailureLogParser {

    private static final Pattern ERROR_LINE = Pattern.compile("^(?<path>.+\\.java):(?<line>\\d+):\\s+error:.*$");

    public List<CompilationFailureLocation> parse(String logOutput) {
        if (logOutput == null || logOutput.isBlank()) {
            return List.of();
        }
        List<CompilationFailureLocation> failures = new ArrayList<>();
        for (String line : logOutput.lines().toList()) {
            Matcher matcher = ERROR_LINE.matcher(line.trim());
            if (!matcher.matches()) {
                continue;
            }
            String path = matcher.group("path");
            String lineNumber = matcher.group("line");
            try {
                failures.add(new CompilationFailureLocation(Path.of(path), Integer.parseInt(lineNumber)));
            } catch (NumberFormatException ignored) {
                // Skip malformed line numbers but continue parsing the rest of the log
            }
        }
        return failures.stream().distinct().toList();
    }
}
