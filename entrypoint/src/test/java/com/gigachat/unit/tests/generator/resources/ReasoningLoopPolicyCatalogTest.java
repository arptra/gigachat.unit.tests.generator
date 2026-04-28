package com.gigachat.unit.tests.generator.resources;

import com.gigachat.unit.tests.generator.reasoning.model.AgentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningLoopPolicyCatalogTest {

    @Test
    void shouldLoadCompilationLoopPolicyFromResources() {
        ReasoningLoopPolicyCatalog catalog = new ReasoningLoopPolicyCatalog();

        ReasoningLoopPolicy policy = catalog.compilationPolicy();

        assertEquals(10, policy.maxIterations());
        assertEquals(2, policy.repeatedSignatureThreshold());
        assertTrue(policy.defaultForbiddenActions().contains("ADD_DEPENDENCY"));
        assertEquals(AgentState.S3_FALSE_DEPENDENCY_DETECTED, policy.falseDependencyPolicy().nextState());
        assertTrue(policy.falseDependencyPolicy().forbiddenActions().contains("ADD_IMPORT"));
    }

    @Test
    void shouldLoadExecutionLoopPolicyFromResources() {
        ReasoningLoopPolicyCatalog catalog = new ReasoningLoopPolicyCatalog();

        ReasoningLoopPolicy policy = catalog.executionPolicy();

        assertEquals(6, policy.maxIterations());
        assertEquals(3, policy.repeatedSignatureThreshold());
        assertEquals(1, policy.stopGraceRounds());
        assertEquals(AgentState.S4_FIX_APPLIED, policy.nextStateForDecision("APPLY_FIX", AgentState.S6_GIVE_UP));
    }

    @Test
    void shouldLoadCoverageLoopPolicyFromResources() {
        ReasoningLoopPolicyCatalog catalog = new ReasoningLoopPolicyCatalog();

        ReasoningLoopPolicy policy = catalog.coveragePolicy();

        assertEquals(4, policy.maxIterations());
        assertEquals(2, policy.repeatedSignatureThreshold());
        assertEquals(AgentState.S4_FIX_APPLIED, policy.nextStateForDecision("APPLY_FIX", AgentState.S6_GIVE_UP));
        assertEquals("DETERMINISTIC_RUNTIME_REPAIR",
                policy.runtimeRegressionPolicyValue("afterCoverageRecipe", ""));
        assertEquals(1, policy.runtimeRegressionPolicyInt("maxRuntimeRepairAttempts", 0));
    }
}
