package com.gigachat.unit.tests.generator.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationPatternCatalogTest {

    @Test
    void shouldMatchInventedMethodPatternFromValidationError() {
        GenerationPatternCatalog catalog = new GenerationPatternCatalog();

        GenerationValidationPattern pattern = catalog.matchValidationPattern(
                        "E102: Invented method User.setActive(true)")
                .orElseThrow();

        assertEquals("G001_INVENTED_METHOD_USAGE", pattern.id());
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("availableMethods")));
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("setActive") || line.contains("incrementAttempts")));
        assertTrue(pattern.dynamicConstraintBuilders().contains("SOURCE_DERIVED_AVAILABLE_METHOD_RETRY_CONSTRAINTS"));
    }

    @Test
    void shouldMatchInternalFieldAccessPatternFromValidationError() {
        GenerationPatternCatalog catalog = new GenerationPatternCatalog();

        GenerationValidationPattern pattern = catalog.matchValidationPattern(
                        "E103: Internal field access app.repository")
                .orElseThrow();

        assertEquals("E103", pattern.errorCode());
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("public methods")));
    }

    @Test
    void shouldMatchMissingConstructorMetadataPatternFromValidationError() {
        GenerationPatternCatalog catalog = new GenerationPatternCatalog();

        GenerationValidationPattern pattern = catalog.matchValidationPattern(
                        "E104: Missing constructor metadata for User(\"Alice\", \"alice@example.com\", 4)")
                .orElseThrow();

        assertEquals("G003_MISSING_CONSTRUCTOR_METADATA", pattern.id());
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("availableConstructors")));
        assertTrue(pattern.dynamicConstraintBuilders().contains("SOURCE_DERIVED_AVAILABLE_METHOD_RETRY_CONSTRAINTS"));
    }

    @Test
    void shouldMatchConstructorLocalGenerationPatternFromValidationError() {
        GenerationPatternCatalog catalog = new GenerationPatternCatalog();

        GenerationValidationPattern pattern = catalog.matchValidationPattern(
                        "E106: forbidden Mockito usage on constructor-created local objects spy(session)")
                .orElseThrow();

        assertEquals("E106", pattern.errorCode());
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("constructor-created local objects")));
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("constructorLocalContexts")));
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("branchDrivers")));
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("publicStateMutators")));
        assertTrue(pattern.dynamicConstraintBuilders().contains("CONSTRUCTOR_LOCAL_RETRY_CONSTRAINTS"));
    }

    @Test
    void shouldMatchPlaceholderGenerationPatternFromValidationError() {
        GenerationPatternCatalog catalog = new GenerationPatternCatalog();

        GenerationValidationPattern pattern = catalog.matchValidationPattern(
                        "E108: generated test does not invoke target method \"executeInheritedShadowUpgrade\"")
                .orElseThrow();

        assertEquals("G007_TARGET_METHOD_NOT_EXERCISED", pattern.id());
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("must invoke the target method")));
    }

    @Test
    void shouldMatchVoidWhenGenerationPatternFromValidationError() {
        GenerationPatternCatalog catalog = new GenerationPatternCatalog();

        GenerationValidationPattern pattern = catalog.matchValidationPattern(
                        "E109: void method invocation used inside Mockito.when(...) when(notificationService.sendWelcome(any()))")
                .orElseThrow();

        assertEquals("G008_VOID_METHOD_STUBBED_WITH_WHEN", pattern.id());
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("void method")));
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("thenCallRealMethod")));
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("constructorLocalContexts")));
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("voidSideEffects")));
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("publicStateMutators")));
        assertTrue(pattern.llmHeuristic().contains("thenCallRealMethod"));
    }

    @Test
    void shouldMatchVoidMutatorUsedAsValuePatternFromValidationError() {
        GenerationPatternCatalog catalog = new GenerationPatternCatalog();

        GenerationValidationPattern pattern = catalog.matchValidationPattern(
                        "E113: void mutator used as value expression new User(\"Bob\", \"bob@example.com\").deactivate()")
                .orElseThrow();

        assertEquals("G012_VOID_MUTATOR_USED_AS_VALUE", pattern.id());
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("separate statements")));
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("add(...)")));
        assertTrue(pattern.dynamicConstraintBuilders().contains("SOURCE_DERIVED_SNIPPET_TYPE_RETRY_CONSTRAINTS"));
    }

    @Test
    void shouldMatchStaticBranchDriverStubbingPatternFromValidationError() {
        GenerationPatternCatalog catalog = new GenerationPatternCatalog();

        GenerationValidationPattern pattern = catalog.matchValidationPattern(
                        "E114: Mockito stubbing applied to non-mock static branch driver when(LegacyScoreRules.shouldEscalate(anyInt(), anyBoolean()))")
                .orElseThrow();

        assertEquals("G013_NON_MOCK_STATIC_BRANCH_DRIVER_STUBBED", pattern.id());
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("LegacyUpgradeSession-style flows")));
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("rawSignal")));
        assertTrue(pattern.dynamicConstraintBuilders().contains("CONSTRUCTOR_LOCAL_RETRY_CONSTRAINTS"));
    }

    @Test
    void shouldMatchStaticMutableStatePatternFromScratchFailure() {
        GenerationPatternCatalog catalog = new GenerationPatternCatalog();

        GenerationValidationPattern pattern = catalog.matchValidationPattern(
                        "SCRATCH_EXECUTION_FAILED: static snapshot clear lifecycle left polluted state")
                .orElseThrow();

        assertEquals("G015_STATIC_MUTABLE_STATE_PUBLIC_API", pattern.id());
        assertTrue(pattern.retryConstraints().stream()
                .anyMatch(line -> line.contains("clear before the scenario")));
        assertTrue(pattern.llmHeuristic().contains("clear -> emit -> snapshot"));
        assertTrue(pattern.dynamicConstraintBuilders().contains("SOURCE_DERIVED_AVAILABLE_METHOD_RETRY_CONSTRAINTS"));
    }
}
