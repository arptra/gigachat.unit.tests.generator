package com.gigachat.unit.tests.generator.cleaner.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Gradle HTML test reports to discover failing test methods.
 */
public class ExecutionReportParser {

    private static final Pattern FAILURE_LINE = Pattern.compile("([\\w.$]+)\\s*>\\s*([^<]+?)\\s*FAILED");
    private static final Pattern FAILURE_LINK = Pattern.compile("classes/([\\w./]+)\\.html#([\\w$]+)\\(");

    public List<TestFailure> parse(Path reportPath) throws IOException {
        if (reportPath == null || !Files.exists(reportPath)) {
            return List.of();
        }
        String content = Files.readString(reportPath);
        String plainText = content.replaceAll("<[^>]+>", " ");
        List<TestFailure> failures = new ArrayList<>();
        Matcher matcher = FAILURE_LINE.matcher(plainText);
        while (matcher.find()) {
            String className = matcher.group(1).trim();
            String methodName = matcher.group(2).trim();
            if (methodName.endsWith("()")) {
                methodName = methodName.substring(0, methodName.length() - 2);
            }
            failures.add(new TestFailure(className, methodName));
        }
        Matcher linkMatcher = FAILURE_LINK.matcher(content);
        while (linkMatcher.find()) {
            String classPath = linkMatcher.group(1);
            String className = classPath.substring(classPath.lastIndexOf('/') + 1);
            String methodName = linkMatcher.group(2);
            failures.add(new TestFailure(className, methodName));
        }
        return List.copyOf(failures);
    }
}
