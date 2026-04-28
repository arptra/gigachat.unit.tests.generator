package com.gigachat.unit.tests.generator.reasoning.model;

/**
 * Finite states for the deterministic reasoning agent.
 */
public enum AgentState {
    S0_INIT,
    S1_TESTS_GENERATED,
    S2_COMPILATION_FAILED,
    S2_2_EXECUTION_FAILED,
    S2_1_NEED_MORE_CONTEXT,
    S3_FALSE_DEPENDENCY_DETECTED,
    S4_FIX_APPLIED,
    S5_COMPILATION_SUCCESS,
    S8_COVERAGE_FAILED,
    S6_GIVE_UP
}
