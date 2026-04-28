package com.gigachat.unit.tests.generator.resources;

import com.gigachat.unit.tests.generator.reasoning.model.ReasoningMemory;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningStagePolicyCatalogTest {

    @Test
    void shouldLoadCompilationPolicyFromResources() {
        ReasoningStagePolicyCatalog catalog = new ReasoningStagePolicyCatalog();

        ReasoningStagePolicy policy = catalog.policyFor(ReasoningStage.COMPILATION);

        assertEquals("COMPILATION", policy.stage().name());
        assertTrue(policy.objective().contains("compile again"));
        assertTrue(policy.allowedDecisions().contains("MARK_FALSE_DEPENDENCY"));
        assertTrue(policy.allowedToolActions().stream().anyMatch(action -> action.name().equals("RECOMPILE")));
    }

    @Test
    void shouldLoadExecutionRepeatedContextGuardFromResources() {
        ReasoningStagePolicy catalogPolicy = new ReasoningStagePolicyCatalog().policyFor(ReasoningStage.EXECUTION);

        assertTrue(catalogPolicy.protocol().stream()
                .anyMatch(line -> line.contains("CONTEXT_CACHE_KEYS already contains the relevant class or method")));
    }

    @Test
    void stageShouldFilterForbiddenToolActionsUsingResourcePolicy() {
        ReasoningMemory memory = new ReasoningMemory();
        memory.addForbiddenAction("ADD_IMPORT");

        assertTrue(ReasoningStage.COMPILATION.describeAllowedActions(memory).contains("RECOMPILE"));
        assertFalse(ReasoningStage.COMPILATION.describeAllowedActions(memory).contains("ADD_IMPORT"));
    }
}
