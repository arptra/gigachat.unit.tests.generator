package com.gigachat.unit.tests.generator.reasoning.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregates inputs that must be included in the next reasoning prompt: the most recent
 * compilation error, project context and any data returned by tool executions.
 */
public class ReasoningLoopContext {

    private final CompilationErrorInfo errorInfo;
    private final ProjectContextSummary projectContextSummary;
    private final Map<String, Object> actionContext;

    public ReasoningLoopContext(CompilationErrorInfo errorInfo,
                                ProjectContextSummary projectContextSummary,
                                Map<String, Object> actionContext) {
        this.errorInfo = Objects.requireNonNull(errorInfo, "errorInfo");
        this.projectContextSummary = Objects.requireNonNull(projectContextSummary, "projectContextSummary");
        this.actionContext = actionContext == null ? new HashMap<>() : new HashMap<>(actionContext);
    }

    public CompilationErrorInfo getErrorInfo() {
        return errorInfo;
    }

    public ProjectContextSummary getProjectContextSummary() {
        return projectContextSummary;
    }

    public Map<String, Object> getActionContext() {
        return Collections.unmodifiableMap(actionContext);
    }
}
