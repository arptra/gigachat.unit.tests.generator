package com.gigachat.unit.tests.generator.reasoning.model;

import java.util.Objects;

/**
 * Aggregates inputs that must be included in the next reasoning prompt: the most recent
 * compilation error, project context and the execution log collected from tool actions.
 */
public class ReasoningLoopContext {

    private final CompilationErrorInfo errorInfo;
    private final ProjectContextSummary projectContextSummary;
    private final ActionExecutionResult executionResult;
    private final com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorReport errorReport;
    private final ReasoningMemory memory;

    public ReasoningLoopContext(CompilationErrorInfo errorInfo,
                                ProjectContextSummary projectContextSummary,
                                ActionExecutionResult executionResult,
                                com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorReport errorReport,
                                ReasoningMemory memory) {
        this.errorInfo = Objects.requireNonNull(errorInfo, "errorInfo");
        this.projectContextSummary = Objects.requireNonNull(projectContextSummary, "projectContextSummary");
        this.executionResult = executionResult == null ? ActionExecutionResult.empty() : executionResult;
        this.errorReport = errorReport;
        this.memory = memory == null ? new ReasoningMemory() : memory.copy();
    }

    public CompilationErrorInfo getErrorInfo() {
        return errorInfo;
    }

    public ProjectContextSummary getProjectContextSummary() {
        return projectContextSummary;
    }

    public ActionExecutionResult getExecutionResult() {
        return executionResult;
    }

    public com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorReport getErrorReport() {
        return errorReport;
    }

    public ReasoningMemory getMemory() {
        return memory;
    }
}
