package com.gigachat.unit.tests.generator.dto;

import java.nio.file.Path;
import java.util.Objects;

public record CoverageErrors(Path testClassFile,
                             String generatedTestMethodName,
                             String targetMethodSignature,
                             String message,
                             String reportPath) {
    public CoverageErrors {
        Objects.requireNonNull(testClassFile, "testClassFile");
        generatedTestMethodName = generatedTestMethodName == null ? "" : generatedTestMethodName;
        targetMethodSignature = targetMethodSignature == null ? "" : targetMethodSignature;
        message = message == null ? "" : message;
        reportPath = reportPath == null ? "" : reportPath;
    }
}
