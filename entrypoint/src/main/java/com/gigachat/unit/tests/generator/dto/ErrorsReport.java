package com.gigachat.unit.tests.generator.dto;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Aggregates errors detected during the generation pipeline.
 */
public final class ErrorsReport {
    private final List<CompileErrors> compileErrors = new CopyOnWriteArrayList<>();
    private final List<ExecuteErrors> executeErrors = new CopyOnWriteArrayList<>();

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

    public List<ExecuteErrors> getExecuteErrors() {
        return Collections.unmodifiableList(executeErrors);
    }

    public boolean hasErrors() {
        return !compileErrors.isEmpty() || !executeErrors.isEmpty();
    }
}
