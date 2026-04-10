package com.gigachat.unit.tests.generator.reasoning.orchestrator;

import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.service.CompilationReasoningService;

import java.util.Objects;

public class CompilationReasoningOrchestrator {

    private final CompilationReasoningService service;

    public CompilationReasoningOrchestrator(CompilationReasoningService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    public ReasoningResponse handle(ReasoningLoopContext loopContext) {
        return service.reasonAboutError(loopContext);
    }
}
