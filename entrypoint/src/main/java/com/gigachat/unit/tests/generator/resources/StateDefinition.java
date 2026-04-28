package com.gigachat.unit.tests.generator.resources;

import com.gigachat.unit.tests.generator.reasoning.model.AgentState;

import java.util.List;

/**
 * Resource-backed description of one reasoning state and the next states that are allowed from it.
 */
public record StateDefinition(AgentState state,
                              String title,
                              boolean terminal,
                              List<AgentState> nextStates) {
}
