package com.gigachat.unit.tests.generator.resources;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeRecipeTemplateCatalogTest {

    @Test
    void shouldRenderParentStaticVoidRecipeTemplate() {
        RuntimeRecipeTemplateCatalog catalog = new RuntimeRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("PARENT_STATIC_VOID_BLOCKER", Map.of(
                "ownerClassUpper", "LEGACYCONNECTIONGATEWAY",
                "ownerClassSimple", "LegacyConnectionGateway",
                "ownerFqcn", "com.example.app.legacy.LegacyConnectionGateway",
                "staticMethod", "openRequiredChannel",
                "stringLiteral", "legacy-shadow-db",
                "sutMethod", "executeInheritedShadowUpgrade",
                "sutClass", "InheritedStaticVoidWorkflowService"
        ));

        assertEquals("MOCK_STATIC_VOID_BLOCKER_LEGACYCONNECTIONGATEWAY", recipe.get("id"));
        assertEquals("PARENT_STATIC_VOID_BLOCKER", recipe.get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipe.get("operations");
        assertTrue(operations.stream().anyMatch(operation -> "wrap_act_with_static_void_mock".equals(operation.get("type"))));
    }

    @Test
    void shouldBindDynamicOperationsForSourceDerivedRuntimeRecipe() {
        RuntimeRecipeTemplateCatalog catalog = new RuntimeRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("SOURCE_DERIVED_RUNTIME_ALIGNMENT", Map.of(
                "sourceClassUpper", "INHERITEDSHADOWUPGRADESESSION",
                "sourceClassSimple", "InheritedShadowUpgradeSession",
                "operations", List.of(Map.of(
                        "type", "align_verify_literals_to_prefixes",
                        "mock", "auditTrailService",
                        "method", "recordEvent",
                        "prefixes", List.of("Inherited shadow promoted ")
                )),
                "requiredImports", List.of("static org.mockito.ArgumentMatchers.startsWith")
        ));

        assertEquals("ALIGN_INHERITEDSHADOWUPGRADESESSION_SOURCE_RUNTIME_CONTRACT", recipe.get("id"));
        assertEquals("SOURCE_DERIVED_RUNTIME_ALIGNMENT", recipe.get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipe.get("operations");
        assertEquals("align_verify_literals_to_prefixes", operations.get(0).get("type"));
    }

    @Test
    void shouldBindDynamicOperationsForConstructorLocalRecipe() {
        RuntimeRecipeTemplateCatalog catalog = new RuntimeRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("CONSTRUCTOR_LOCAL_RUNTIME_ALIGNMENT", Map.of(
                "sourceClassUpper", "HIDDENFEATURE",
                "sourceClassSimple", "HiddenFeature",
                "recipeIndex", 1,
                "sutClass", "Application",
                "invokedMethods", List.of("activate", "recalibrate"),
                "operations", List.of(Map.of(
                        "type", "replace_string_literal_argument",
                        "mock", "featureToggleService",
                        "method", "isEnabled",
                        "literal", "hidden"
                )),
                "requiredImports", List.of("static org.mockito.ArgumentMatchers.startsWith")
        ));

        assertEquals("ALIGN_HIDDENFEATURE_RUNTIME_CONTRACT_1", recipe.get("id"));
        assertEquals("HiddenFeature", recipe.get("sourceClass"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipe.get("operations");
        assertEquals("replace_string_literal_argument", operations.get(0).get("type"));
    }

    @Test
    void shouldRenderThresholdRejectionRuntimeRecipeTemplate() {
        RuntimeRecipeTemplateCatalog catalog = new RuntimeRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("THRESHOLD_REJECTION_RUNTIME_ALIGNMENT", Map.ofEntries(
                Map.entry("sourceClassUpper", "LEGACYUPGRADESESSION"),
                Map.entry("sourceClassSimple", "LegacyUpgradeSession"),
                Map.entry("testMethodName", "testProcessRejectsWhenFeatureIsEnabled"),
                Map.entry("sutMethod", "process"),
                Map.entry("featureMock", "featureToggleService"),
                Map.entry("featureName", "legacy-upgrade"),
                Map.entry("numericArgumentValue", "0"),
                Map.entry("notificationMock", "notificationService"),
                Map.entry("promotionMethod", "sendWelcome"),
                Map.entry("rejectionMethod", "sendDeactivationNotice"),
                Map.entry("auditMock", "auditTrailService"),
                Map.entry("auditMethod", "recordEvent"),
                Map.entry("auditPrefix", "Legacy upgrade rejected ")
        ));

        assertEquals("ALIGN_LEGACYUPGRADESESSION_THRESHOLD_REJECTION_BRANCH", recipe.get("id"));
        assertEquals("THRESHOLD_REJECTION_RUNTIME_ALIGNMENT", recipe.get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipe.get("operations");
        assertEquals("align_threshold_rejection_branch", operations.get(0).get("type"));
    }

    @Test
    void shouldRenderReboundThresholdAttemptsRuntimeRecipeTemplate() {
        RuntimeRecipeTemplateCatalog catalog = new RuntimeRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("REBOUND_THRESHOLD_ATTEMPTS_RUNTIME_ALIGNMENT", Map.of(
                "sourceClassUpper", "SHADOWROLLBACKSESSION",
                "sourceClassSimple", "ShadowRollbackSession",
                "testMethodName", "testRollbackHighRebound",
                "sutMethod", "rollback",
                "userVariable", "user",
                "minimumAttempts", "3"
        ));

        assertEquals("ALIGN_SHADOWROLLBACKSESSION_REBOUND_THRESHOLD_ATTEMPTS", recipe.get("id"));
        assertEquals("REBOUND_THRESHOLD_ATTEMPTS_RUNTIME_ALIGNMENT", recipe.get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipe.get("operations");
        assertEquals("ensure_minimum_rebound_attempts", operations.get(0).get("type"));
    }
}
