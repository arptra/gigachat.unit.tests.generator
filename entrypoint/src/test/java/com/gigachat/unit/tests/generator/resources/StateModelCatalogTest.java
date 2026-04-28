package com.gigachat.unit.tests.generator.resources;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateModelCatalogTest {

    @Test
    void shouldLoadMissingConstructorMetadataStateFromResources() {
        StateModelCatalog catalog = new StateModelCatalog();

        JSONObject state = catalog.generationValidationState("E104");

        assertEquals("MISSING_CONSTRUCTOR_METADATA", state.getString("failurePattern"));
        assertTrue(state.getJSONArray("allowedActions").toList().contains("USE_PUBLIC_METHODS_TO_REACH_STATE"));
        assertTrue(state.getJSONArray("forbiddenActions").toList().contains("INVENT_CONSTRUCTOR_OVERLOAD"));
    }

    @Test
    void shouldLoadGenerationValidationStateFromResources() {
        StateModelCatalog catalog = new StateModelCatalog();

        JSONObject state = catalog.generationValidationState("E106");

        assertEquals("FORBIDDEN_CONSTRUCTOR_LOCAL_MOCK_USAGE", state.getString("failurePattern"));
        assertTrue(state.getJSONArray("allowedActions").toList().contains("VERIFY_COLLABORATOR_SIDE_EFFECT"));
        assertTrue(state.getJSONArray("forbiddenActions").toList().contains("MOCK_CONSTRUCTOR_LOCAL_OBJECT"));
    }

    @Test
    void shouldLoadVoidMutatorValueStateFromResources() {
        StateModelCatalog catalog = new StateModelCatalog();

        JSONObject state = catalog.generationValidationState("E113");

        assertEquals("VOID_STATE_MUTATOR_USED_AS_VALUE", state.getString("failurePattern"));
        assertTrue(state.getJSONArray("allowedActions").toList().contains("USE_VOID_MUTATOR_AS_STANDALONE_STATEMENT"));
        assertTrue(state.getJSONArray("forbiddenActions").toList().contains("CHAIN_VOID_MUTATOR_INTO_COLLECTION_ADD"));
    }

    @Test
    void shouldLoadStaticBranchDriverStateFromResources() {
        StateModelCatalog catalog = new StateModelCatalog();

        JSONObject state = catalog.generationValidationState("E114");

        assertEquals("NON_MOCK_STATIC_BRANCH_DRIVER_STUBBED", state.getString("failurePattern"));
        assertTrue(state.getJSONArray("allowedActions").toList().contains("DRIVE_BRANCH_WITH_REAL_INPUTS"));
        assertTrue(state.getJSONArray("forbiddenActions").toList().contains("STUB_STATIC_UTILITY_WITH_MOCKITO_WHEN"));
    }

    @Test
    void shouldLoadRepairStateFromResources() {
        StateModelCatalog catalog = new StateModelCatalog();

        JSONObject state = catalog.repairState("executionFailed");

        assertEquals("S2_2_EXECUTION_FAILED", state.getString("currentState"));
        assertTrue(state.getJSONArray("allowedActions").toList().contains("PATCH_RUNTIME_SETUP_OR_ASSERTION"));
    }
}
