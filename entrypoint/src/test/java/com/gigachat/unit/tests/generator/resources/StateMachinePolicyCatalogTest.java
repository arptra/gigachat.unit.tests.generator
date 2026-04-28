package com.gigachat.unit.tests.generator.resources;

import com.gigachat.unit.tests.generator.reasoning.model.AgentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateMachinePolicyCatalogTest {

    @Test
    void shouldLoadInitialStateAndTransitionsFromResources() {
        StateMachinePolicy policy = new StateMachinePolicyCatalog().policy();

        assertEquals(AgentState.S0_INIT, policy.initialState());
        assertTrue(policy.canTransition(AgentState.S2_COMPILATION_FAILED, AgentState.S4_FIX_APPLIED));
        assertTrue(policy.canTransition(AgentState.S4_FIX_APPLIED, AgentState.S2_2_EXECUTION_FAILED));
        assertTrue(policy.canTransition(AgentState.S8_COVERAGE_FAILED, AgentState.S2_2_EXECUTION_FAILED));
        assertFalse(policy.canTransition(AgentState.S6_GIVE_UP, AgentState.S4_FIX_APPLIED));
    }

    @Test
    void shouldExposeTerminalStatesFromResources() {
        StateMachinePolicy policy = new StateMachinePolicyCatalog().policy();

        assertTrue(policy.isTerminal(AgentState.S5_COMPILATION_SUCCESS));
        assertTrue(policy.isTerminal(AgentState.S6_GIVE_UP));
        assertFalse(policy.isTerminal(AgentState.S2_COMPILATION_FAILED));
    }
}
