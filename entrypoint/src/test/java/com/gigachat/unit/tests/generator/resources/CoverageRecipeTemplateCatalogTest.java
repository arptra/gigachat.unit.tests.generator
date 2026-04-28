package com.gigachat.unit.tests.generator.resources;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoverageRecipeTemplateCatalogTest {

    @Test
    void shouldRenderBooleanBranchCoverageRecipeTemplate() {
        CoverageRecipeTemplateCatalog catalog = new CoverageRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("ADD_BOOLEAN_BRANCH_SIBLING_TEST", Map.of(
                "targetMethodUpper", "SENDWELCOME",
                "targetMethodSimple", "sendWelcome",
                "methodSource", "@Test void shouldSendWelcomeCoverageVariant2() {}",
                "requiredImports", List.of()
        ));

        assertEquals("ADD_BOOLEAN_BRANCH_SIBLING_TEST_SENDWELCOME", recipe.get("id"));
        assertEquals("ADD_BOOLEAN_BRANCH_SIBLING_TEST", recipe.get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipe.get("operations");
        assertEquals("insert_method_before_class_end", operations.get(0).get("type"));
    }

    @Test
    void shouldRenderNullGuardCoverageRecipeTemplate() {
        CoverageRecipeTemplateCatalog catalog = new CoverageRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("ADD_NULL_GUARD_SIBLING_TEST", Map.of(
                "targetMethodUpper", "CLASSIFYSIGNAL",
                "targetMethodSimple", "classifySignal",
                "methodSource", "@Test void shouldClassifySignalCoverageVariant2() {}",
                "requiredImports", List.of()
        ));

        assertEquals("ADD_NULL_GUARD_SIBLING_TEST_CLASSIFYSIGNAL", recipe.get("id"));
        assertEquals("ADD_NULL_GUARD_SIBLING_TEST", recipe.get("kind"));
    }

    @Test
    void shouldRenderNumericBoundaryCoverageRecipeTemplate() {
        CoverageRecipeTemplateCatalog catalog = new CoverageRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("ADD_NUMERIC_BOUNDARY_SIBLING_TEST", Map.of(
                "targetMethodUpper", "SHOULDESCALATE",
                "targetMethodSimple", "shouldEscalate",
                "methodSource", "@Test void shouldEscalateCoverageVariant2() {}",
                "requiredImports", List.of()
        ));

        assertEquals("ADD_NUMERIC_BOUNDARY_SIBLING_TEST_SHOULDESCALATE", recipe.get("id"));
        assertEquals("ADD_NUMERIC_BOUNDARY_SIBLING_TEST", recipe.get("kind"));
    }

    @Test
    void shouldRenderConstructorLocalReboundCoverageRecipeTemplate() {
        CoverageRecipeTemplateCatalog catalog = new CoverageRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("ADD_CONSTRUCTOR_LOCAL_REBOUND_SIBLING_TEST", Map.of(
                "targetMethodUpper", "COORDINATESHADOWROLLBACK",
                "targetMethodSimple", "coordinateShadowRollback",
                "methodSource", "@Test void coordinateShadowRollbackCoverageVariant2() {}",
                "requiredImports", List.of("static org.mockito.ArgumentMatchers.startsWith")
        ));

        assertEquals("ADD_CONSTRUCTOR_LOCAL_REBOUND_SIBLING_TEST_COORDINATESHADOWROLLBACK", recipe.get("id"));
        assertEquals("ADD_CONSTRUCTOR_LOCAL_REBOUND_SIBLING_TEST", recipe.get("kind"));
    }

    @Test
    void shouldRenderTypedCollectionEarlyReturnCoverageRecipeTemplate() {
        CoverageRecipeTemplateCatalog catalog = new CoverageRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("ADD_TYPED_COLLECTION_EARLY_RETURN_SIBLING_TEST", Map.of(
                "targetMethodUpper", "AVERAGELOGINATTEMPTS",
                "targetMethodSimple", "averageLoginAttempts",
                "methodSource", "@Test void averageLoginAttemptsCoverageVariant2() {}",
                "requiredImports", List.of()
        ));

        assertEquals("ADD_TYPED_COLLECTION_EARLY_RETURN_SIBLING_TEST_AVERAGELOGINATTEMPTS", recipe.get("id"));
        assertEquals("ADD_TYPED_COLLECTION_EARLY_RETURN_SIBLING_TEST", recipe.get("kind"));
    }

    @Test
    void shouldRenderCollectionElementTypeSafeCoverageRecipeTemplate() {
        CoverageRecipeTemplateCatalog catalog = new CoverageRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("ADD_COLLECTION_ELEMENT_TYPE_SAFE_SIBLING_TEST", Map.of(
                "targetMethodUpper", "AVERAGELOGINATTEMPTS",
                "targetMethodSimple", "averageLoginAttempts",
                "methodSource", "@Test void averageLoginAttemptsCoverageVariant2() {}",
                "requiredImports", List.of()
        ));

        assertEquals("ADD_COLLECTION_ELEMENT_TYPE_SAFE_SIBLING_TEST_AVERAGELOGINATTEMPTS", recipe.get("id"));
        assertEquals("ADD_COLLECTION_ELEMENT_TYPE_SAFE_SIBLING_TEST", recipe.get("kind"));
    }

    @Test
    void shouldRenderReboundFactorCoverageRecipeTemplate() {
        CoverageRecipeTemplateCatalog catalog = new CoverageRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("ADD_REBOUND_FACTOR_BRANCH_SIBLING_TEST", Map.of(
                "targetMethodUpper", "REBOUNDFACTOR",
                "targetMethodSimple", "reboundFactor",
                "methodSource", "@Test void reboundFactorCoverageVariant2() {}",
                "requiredImports", List.of()
        ));

        assertEquals("ADD_REBOUND_FACTOR_BRANCH_SIBLING_TEST_REBOUNDFACTOR", recipe.get("id"));
        assertEquals("ADD_REBOUND_FACTOR_BRANCH_SIBLING_TEST", recipe.get("kind"));
    }

    @Test
    void shouldRenderReboundThresholdCoverageRecipeTemplate() {
        CoverageRecipeTemplateCatalog catalog = new CoverageRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("ADD_REBOUND_THRESHOLD_SIBLING_TEST", Map.of(
                "targetMethodUpper", "REBOUNDFACTOR",
                "targetMethodSimple", "reboundFactor",
                "methodSource", "@Test void reboundFactorCoverageVariant2() {}",
                "requiredImports", List.of()
        ));

        assertEquals("ADD_REBOUND_THRESHOLD_SIBLING_TEST_REBOUNDFACTOR", recipe.get("id"));
        assertEquals("ADD_REBOUND_THRESHOLD_SIBLING_TEST", recipe.get("kind"));
    }

    @Test
    void shouldRenderSourceDerivedReturnBranchCoverageRecipeTemplate() {
        CoverageRecipeTemplateCatalog catalog = new CoverageRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("ADD_SOURCE_DERIVED_RETURN_BRANCH_SIBLING_TEST", Map.of(
                "targetMethodUpper", "AVERAGE",
                "targetMethodSimple", "average",
                "methodSource", "@Test void averageCoverageVariant2() {}",
                "requiredImports", List.of()
        ));

        assertEquals("ADD_SOURCE_DERIVED_RETURN_BRANCH_SIBLING_TEST_AVERAGE", recipe.get("id"));
        assertEquals("ADD_SOURCE_DERIVED_RETURN_BRANCH_SIBLING_TEST", recipe.get("kind"));
    }

    @Test
    void shouldRenderConstructorLocalPromotionCoverageRecipeTemplate() {
        CoverageRecipeTemplateCatalog catalog = new CoverageRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("ADD_CONSTRUCTOR_LOCAL_PROMOTION_SIBLING_TEST", Map.of(
                "targetMethodUpper", "PROCESS",
                "targetMethodSimple", "process",
                "methodSource", "@Test void processCoverageVariant2() {}",
                "requiredImports", List.of("static org.mockito.ArgumentMatchers.startsWith")
        ));

        assertEquals("ADD_CONSTRUCTOR_LOCAL_PROMOTION_SIBLING_TEST_PROCESS", recipe.get("id"));
        assertEquals("ADD_CONSTRUCTOR_LOCAL_PROMOTION_SIBLING_TEST", recipe.get("kind"));
    }

    @Test
    void shouldRenderLegacyBooleanBranchCoverageRecipeTemplate() {
        CoverageRecipeTemplateCatalog catalog = new CoverageRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("ADD_LEGACY_BOOLEAN_BRANCH_SIBLING_TEST", Map.of(
                "targetMethodUpper", "PROCESS",
                "targetMethodSimple", "process",
                "methodSource", "@Test void processCoverageVariant2() {}",
                "requiredImports", List.of("static org.junit.jupiter.api.Assertions.assertTrue")
        ));

        assertEquals("ADD_LEGACY_BOOLEAN_BRANCH_SIBLING_TEST_PROCESS", recipe.get("id"));
        assertEquals("ADD_LEGACY_BOOLEAN_BRANCH_SIBLING_TEST", recipe.get("kind"));
    }

    @Test
    void shouldRenderExceptionGuardCoverageRecipeTemplate() {
        CoverageRecipeTemplateCatalog catalog = new CoverageRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("ADD_EXCEPTION_GUARD_SIBLING_TEST", Map.of(
                "targetMethodUpper", "VALIDATEFEATURE",
                "targetMethodSimple", "validateFeature",
                "methodSource", "@Test void shouldValidateFeatureCoverageVariant2() {}",
                "requiredImports", List.of()
        ));

        assertEquals("ADD_EXCEPTION_GUARD_SIBLING_TEST_VALIDATEFEATURE", recipe.get("id"));
        assertEquals("ADD_EXCEPTION_GUARD_SIBLING_TEST", recipe.get("kind"));
    }
}
