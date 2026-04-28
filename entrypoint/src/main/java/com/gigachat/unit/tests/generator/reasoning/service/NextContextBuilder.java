package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.reasoning.model.ProjectContextSummary;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningStage;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;

/**
 * Utility that constructs the context passed into the next reasoning iteration by combining the
 * current error details, project summary and any execution artefacts from tool actions.
 */
public class NextContextBuilder {

    public ReasoningLoopContext build(CompilationErrorInfo errorInfo,
                                      ProjectContextSummary projectContextSummary,
                                      ActionExecutionResult actionResult,
                                      com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorReport errorReport,
                                      com.gigachat.unit.tests.generator.reasoning.model.ReasoningMemory memory) {
        return build(errorInfo, projectContextSummary, actionResult, errorReport, memory, ReasoningStage.COMPILATION);
    }

    public ReasoningLoopContext build(CompilationErrorInfo errorInfo,
                                      ProjectContextSummary projectContextSummary,
                                      ActionExecutionResult actionResult,
                                      com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorReport errorReport,
                                      com.gigachat.unit.tests.generator.reasoning.model.ReasoningMemory memory,
                                      ReasoningStage stage) {
        return new ReasoningLoopContext(errorInfo, projectContextSummary,
                actionResult == null ? ActionExecutionResult.empty() : actionResult,
                errorReport,
                memory,
                stage);
    }
}
