package com.gigachat.unit.tests.generator.reasoning.workflow;

import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.orchestrator.CompilationReasoningOrchestrator;

import java.util.Objects;

public class ReasoningWorkflow {

    private final CompilationReasoningOrchestrator orchestrator;

    public ReasoningWorkflow(CompilationReasoningOrchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    public ReasoningResponse process(ReasoningLoopContext loopContext) {
        return orchestrator.handle(loopContext);
    }
}
