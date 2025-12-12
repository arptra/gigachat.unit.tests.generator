package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.reasoning.model.ProjectContextSummary;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility that constructs the context passed into the next reasoning iteration by combining the
 * current error details, project summary and any execution artefacts from tool actions.
 */
public class NextContextBuilder {

    public ReasoningLoopContext build(CompilationErrorInfo errorInfo,
                                      ProjectContextSummary projectContextSummary,
                                      ActionExecutionResult actionResult) {
        Map<String, Object> context = new HashMap<>();
        if (actionResult != null) {
            context.putAll(actionResult.getContext());
        }
        return new ReasoningLoopContext(errorInfo, projectContextSummary, context);
    }
}
