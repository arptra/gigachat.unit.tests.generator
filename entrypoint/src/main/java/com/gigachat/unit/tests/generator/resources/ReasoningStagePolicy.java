package com.gigachat.unit.tests.generator.resources;

import com.gigachat.unit.tests.generator.reasoning.model.ReasoningStage;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;

import java.util.List;

/**
 * Resource-driven policy for a reasoning stage.
 */
public record ReasoningStagePolicy(ReasoningStage stage,
                                   String objective,
                                   List<String> protocol,
                                   List<String> allowedDecisions,
                                   List<ToolActionType> allowedToolActions) {
}
