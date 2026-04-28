package com.gigachat.unit.tests.generator.resources;

import com.gigachat.unit.tests.generator.reasoning.model.AgentState;

import java.util.List;

/**
 * Resource-driven handling rules for unresolved/invented dependencies.
 */
public record FalseDependencyPolicy(AgentState nextState,
                                    List<String> forbiddenActions) {
}
