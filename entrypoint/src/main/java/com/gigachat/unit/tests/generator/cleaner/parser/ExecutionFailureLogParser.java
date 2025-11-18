package com.gigachat.unit.tests.generator.cleaner.parser;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Gradle/JUnit execution output to extract failing test methods and the report location.
 */
public class ExecutionFailureLogParser {

    private static final Pattern FAILURE_LINE = Pattern.compile("^\\s*([\\w.$]+)\\s*>\\s*(.+?)\\s*FAILED\\s*$");
    private static final Pattern REPORT_LINE = Pattern.compile("See the report at:\\s*(file:[^\\s]+)");

    public ExecutionFailureParseResult parse(String logOutput) {
        if (logOutput == null || logOutput.isBlank()) {
            return new ExecutionFailureParseResult(List.of(), Optional.empty());
        }
        List<TestFailure> failures = new ArrayList<>();
        Optional<Path> reportPath = Optional.empty();
        for (String line : logOutput.split("\\R")) {
            Matcher failureMatcher = FAILURE_LINE.matcher(line);
            if (failureMatcher.find()) {
                String className = failureMatcher.group(1).trim();
                String methodName = normalizeMethodName(failureMatcher.group(2).trim());
                failures.add(new TestFailure(className, methodName));
                continue;
            }
            Matcher reportMatcher = REPORT_LINE.matcher(line);
            if (reportMatcher.find()) {
                String reportUri = reportMatcher.group(1);
                try {
                    reportPath = Optional.of(Path.of(URI.create(reportUri)));
                } catch (IllegalArgumentException exception) {
                    reportPath = Optional.of(Path.of(reportUri.replace("file://", "")));
                }
            }
        }
        return new ExecutionFailureParseResult(List.copyOf(failures), reportPath);
    }

    private String normalizeMethodName(String methodName) {
        if (methodName.endsWith("()")) {
            return methodName.substring(0, methodName.length() - 2);
        }
        return methodName;
    }
}
