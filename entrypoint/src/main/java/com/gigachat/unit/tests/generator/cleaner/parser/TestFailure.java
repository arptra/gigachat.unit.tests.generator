package com.gigachat.unit.tests.generator.cleaner.parser;

/**
 * Represents a failing test method captured either from compilation or execution reports.
 */
public record TestFailure(String className, String methodName) {

    public TestFailure {
        className = className == null ? "" : className.trim();
        methodName = methodName == null ? "" : methodName.trim();
    }
}
