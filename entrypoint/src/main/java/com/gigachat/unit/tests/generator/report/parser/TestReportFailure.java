package com.gigachat.unit.tests.generator.report.parser;

import java.util.List;

/**
 * Detailed description of a failing test captured from HTML reports.
 */
public record TestReportFailure(String className, String methodName, String message, List<String> stackTrace) {

    public TestReportFailure {
        className = className == null ? "" : className.trim();
        methodName = methodName == null ? "" : methodName.trim();
        message = message == null ? "" : message.trim();
        stackTrace = stackTrace == null ? List.of() : List.copyOf(stackTrace);
    }
}
