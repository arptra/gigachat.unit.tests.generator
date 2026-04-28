package com.gigachat.unit.tests.generator.reasoning.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningMemoryStateTransitionTest {

    @Test
    void shouldStartFromResourceConfiguredInitialState() {
        ReasoningMemory memory = new ReasoningMemory();

        assertEquals(AgentState.S0_INIT, memory.getState());
    }

    @Test
    void shouldRejectInvalidStateTransitions() {
        ReasoningMemory memory = new ReasoningMemory();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> memory.setState(AgentState.S4_FIX_APPLIED));

        assertTrue(exception.getMessage().contains("S0_INIT -> S4_FIX_APPLIED"));
    }
}
