package com.gigachat.unit.tests.generator.reasoning.workflow;

import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.reasoning.model.ProjectContextSummary;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.orchestrator.CompilationReasoningOrchestrator;

import java.util.Objects;

public class ReasoningWorkflow {

    private final CompilationReasoningOrchestrator orchestrator;

    public ReasoningWorkflow(CompilationReasoningOrchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    public ReasoningResponse process(CompilationErrorInfo error, ProjectContextSummary summary) {
        return orchestrator.handle(error, summary);
    }
}
