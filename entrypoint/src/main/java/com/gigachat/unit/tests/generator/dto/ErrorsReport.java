package com.gigachat.unit.tests.generator.dto;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Aggregates errors detected during the generation pipeline.
 */
public final class ErrorsReport {
    private final List<CompileErrors> compileErrors = new CopyOnWriteArrayList<>();
    private final List<ExecuteErrors> executeErrors = new CopyOnWriteArrayList<>();
    private final List<CoverageErrors> coverageErrors = new CopyOnWriteArrayList<>();

    public void addCompileErrors(CompileErrors errors) {
        if (errors != null) {
            compileErrors.add(errors);
        }
    }

    public void addExecuteErrors(ExecuteErrors errors) {
        if (errors != null) {
            executeErrors.add(errors);
        }
    }

    public List<CompileErrors> getCompileErrors() {
        return Collections.unmodifiableList(compileErrors);
    }

    public void resolveCompileErrors(Path testClassFile, String methodName) {
        if (testClassFile == null || methodName == null || methodName.isBlank()) {
            return;
        }
        Path normalizedPath = testClassFile.toAbsolutePath().normalize();
        compileErrors.removeIf(error -> Objects.equals(error.testClassFile().toAbsolutePath().normalize(), normalizedPath)
                && Objects.equals(error.methodName(), methodName));
    }

    public List<ExecuteErrors> getExecuteErrors() {
        return Collections.unmodifiableList(executeErrors);
    }

    public void resolveExecuteErrors(Path testClassFile, String methodName) {
        if (testClassFile == null || methodName == null || methodName.isBlank()) {
            return;
        }
        Path normalizedPath = testClassFile.toAbsolutePath().normalize();
        executeErrors.removeIf(error -> Objects.equals(error.testClassFile().toAbsolutePath().normalize(), normalizedPath)
                && Objects.equals(error.methodName(), methodName));
    }

    public void addCoverageErrors(CoverageErrors errors) {
        if (errors != null) {
            coverageErrors.add(errors);
        }
    }

    public List<CoverageErrors> getCoverageErrors() {
        return Collections.unmodifiableList(coverageErrors);
    }

    public boolean hasErrors() {
        return !compileErrors.isEmpty() || !executeErrors.isEmpty() || !coverageErrors.isEmpty();
    }
}
