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

    public ReasoningLoopContext(CompilationErrorInfo errorInfo,
                                ProjectContextSummary projectContextSummary,
                                ActionExecutionResult executionResult) {
        this.errorInfo = Objects.requireNonNull(errorInfo, "errorInfo");
        this.projectContextSummary = Objects.requireNonNull(projectContextSummary, "projectContextSummary");
        this.executionResult = executionResult == null ? ActionExecutionResult.empty() : executionResult;
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
}
