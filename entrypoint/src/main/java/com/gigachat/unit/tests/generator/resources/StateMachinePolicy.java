package com.gigachat.unit.tests.generator.resources;

import com.gigachat.unit.tests.generator.reasoning.model.AgentState;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable state-machine policy loaded from editable resources.
 */
public record StateMachinePolicy(AgentState initialState,
                                 Map<AgentState, StateDefinition> states) {

    public StateMachinePolicy {
        Objects.requireNonNull(initialState, "initialState");
        states = states == null ? Map.of() : Map.copyOf(states);
    }

    public boolean canTransition(AgentState from, AgentState to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        StateDefinition definition = states.get(from);
        if (definition == null || definition.nextStates() == null) {
            return false;
        }
        return definition.nextStates().contains(to);
    }

    public boolean isTerminal(AgentState state) {
        if (state == null) {
            return false;
        }
        StateDefinition definition = states.get(state);
        return definition != null && definition.terminal();
    }

    public List<AgentState> allowedNextStates(AgentState state) {
        StateDefinition definition = states.get(state);
        if (definition == null || definition.nextStates() == null) {
            return List.of();
        }
        return definition.nextStates();
    }
}
