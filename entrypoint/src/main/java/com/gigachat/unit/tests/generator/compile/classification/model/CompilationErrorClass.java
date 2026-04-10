package com.gigachat.unit.tests.generator.compile.classification.model;

/**
 * Categories for common compilation failures.
 */
public enum CompilationErrorClass {
    MISSING_DEPENDENCY_OR_PACKAGE,
    MISSING_IMPORT_OR_SYMBOL,
    METHOD_SIGNATURE_MISMATCH,
    TYPE_MISMATCH,
    ACCESS_VIOLATION,
    SYNTAX_ERROR,
    OTHER
}
