package com.gigachat.unit.tests.generator.resources;

import com.gigachat.unit.tests.generator.reasoning.model.ReasoningStage;

import java.util.List;

/**
 * Resource-driven error pattern used to constrain LLM decisions.
 */
public record ReasoningPattern(String id,
                               ReasoningStage stage,
                               String title,
                               String errorFamily,
                               String summary,
                               List<String> containsAny,
                               List<String> allowedTools,
                               List<String> executorActions,
                               String llmHeuristic) {
}
