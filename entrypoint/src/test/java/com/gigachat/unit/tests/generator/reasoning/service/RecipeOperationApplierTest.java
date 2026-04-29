package com.gigachat.unit.tests.generator.reasoning.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeOperationApplierTest {

    @Test
    void shouldWrapVerifyArgumentWithRefEq() {
        String source = """
                class UserServiceTest {
                    void testCreateUser() {
                        verify(repository).save(expectedUser);
                        verify(notificationService).sendWelcome(expectedUser);
                    }
                }
                """;

        RecipeOperationApplier applier = new RecipeOperationApplier("testCreateUser");
        String afterRepository = applier.apply(source, Map.of(
                "type", "wrap_verify_argument_with_ref_eq",
                "mock", "repository",
                "method", "save"
        ));
        String updated = applier.apply(afterRepository, Map.of(
                "type", "wrap_verify_argument_with_ref_eq",
                "mock", "notificationService",
                "method", "sendWelcome"
        ));

        assertTrue(updated.contains("verify(repository).save(refEq(expectedUser));"));
        assertTrue(updated.contains("verify(notificationService).sendWelcome(refEq(expectedUser));"));
    }

    @Test
    void shouldReplaceEqMatcherWithRefEqForConstructorLocalObjects() {
        String source = """
                class UserServiceTest {
                    void testCreateUser() {
                        verify(repository).save(eq(expectedUser));
                        verify(notificationService).sendWelcome(eq(expectedUser));
                    }
                }
                """;

        RecipeOperationApplier applier = new RecipeOperationApplier("testCreateUser");
        String afterRepository = applier.apply(source, Map.of(
                "type", "wrap_verify_argument_with_ref_eq",
                "mock", "repository",
                "method", "save"
        ));
        String updated = applier.apply(afterRepository, Map.of(
                "type", "wrap_verify_argument_with_ref_eq",
                "mock", "notificationService",
                "method", "sendWelcome"
        ));

        assertTrue(updated.contains("verify(repository).save(refEq(expectedUser));"));
        assertTrue(updated.contains("verify(notificationService).sendWelcome(refEq(expectedUser));"));
    }

    @Test
    void shouldInsertMissingBooleanStubBeforeActWhenLiteralCallIsAbsent() {
        String source = """
                class ApplicationTest {
                    void testDeployHiddenFeature() {
                        // Arrange
                        when(auditTrailService.countEvents()).thenReturn(0);
                        // Act
                        application.deployHiddenFeature();
                    }
                }
                """;

        RecipeOperationApplier applier = new RecipeOperationApplier("testDeployHiddenFeature");
        String updated = applier.apply(source, Map.of(
                "type", "replace_string_literal_argument",
                "mock", "featureToggleService",
                "method", "isEnabled",
                "literal", "hidden",
                "returnLiteral", "true"
        ));

        assertTrue(updated.contains("when(featureToggleService.isEnabled(\"hidden\")).thenReturn(true);"));
        assertTrue(updated.indexOf("when(featureToggleService.isEnabled(\"hidden\")).thenReturn(true);")
                < updated.indexOf("application.deployHiddenFeature();"));
    }

    @Test
    void shouldAlignExactVerifyLiteralsToSourcePrefixesAcrossSiblingMethods() {
        String source = """
                class InheritedShadowUpgradeSessionTest {
                    void testUpgradeWithHighNormalizedScore() {
                        verify(auditTrailService).recordEvent("Inherited shadow promoted test-user with score 15");
                    }

                    void testUpgradeWithLowNormalizedScore() {
                        verify(auditTrailService).recordEvent("Inherited shadow rejected test-user with score 5");
                    }
                }
                """;

        RecipeOperationApplier applier = new RecipeOperationApplier("testUpgradeWhenFeatureDisabled");
        String updated = applier.apply(source, Map.of(
                "type", "align_verify_literals_to_prefixes",
                "mock", "auditTrailService",
                "method", "recordEvent",
                "prefixes", java.util.List.of(
                        "Inherited shadow promoted ",
                        "Inherited shadow rejected "
                )
        ));

        assertTrue(updated.contains("recordEvent(startsWith(\"Inherited shadow promoted \"))"));
        assertTrue(updated.contains("recordEvent(startsWith(\"Inherited shadow rejected \"))"));
    }

    @Test
    void shouldAlignConcatenatedVerifyArgumentsToSourcePrefixesAcrossSiblingMethods() {
        String source = """
                class InheritedShadowUpgradeSessionTest {
                    void testUpgradeWithHighNormalizedScore() {
                        int rawSignal = 12;
                        verify(auditTrailService).recordEvent("Inherited shadow promoted AliceSmith with score " + rawSignal);
                    }

                    void testUpgradeWithLowNormalizedScore() {
                        User user = new User("JaneDoe", "jane@example.com");
                        verify(auditTrailService).recordEvent("Inherited shadow rejected " + user.getUsername());
                    }
                }
                """;

        RecipeOperationApplier applier = new RecipeOperationApplier("testUpgradeWhenFeatureDisabled");
        String updated = applier.apply(source, Map.of(
                "type", "align_verify_literals_to_prefixes",
                "mock", "auditTrailService",
                "method", "recordEvent",
                "prefixes", java.util.List.of(
                        "Inherited shadow promoted ",
                        "Inherited shadow rejected "
                )
        ));

        assertTrue(updated.contains("recordEvent(startsWith(\"Inherited shadow promoted \"))"));
        assertTrue(updated.contains("recordEvent(startsWith(\"Inherited shadow rejected \"))"));
    }

    @Test
    void shouldAlignThresholdRejectionBranchByLoweringActInput() {
        String source = """
                class LegacyUpgradeSessionTest {
                    void testProcessRejectsWhenFeatureIsEnabledButShouldEscalateReturnsFalse() {
                        User user = new User("testUser", "test@example.com");
                        when(featureToggleService.isEnabled("legacy-upgrade")).thenReturn(true);
                        boolean result = session.process(user, 40);
                        assertTrue(result);
                        verify(notificationService).sendWelcome(user);
                        verify(auditTrailService).recordEvent("Legacy upgrade promoted testUser with score " + LegacyScoreRules.normalizeSignal(40, user.getLoginAttempts()));
                    }

                    void testProcessPromotesWhenFeatureIsEnabledAndShouldEscalateReturnsTrue() {
                        boolean result = session.process(user, 80);
                        assertTrue(result);
                    }
                }
                """;

        RecipeOperationApplier applier = new RecipeOperationApplier("testProcessPromotesWhenFeatureIsEnabledAndShouldEscalateReturnsTrue");
        String updated = applier.apply(source, Map.ofEntries(
                Map.entry("type", "align_threshold_rejection_branch"),
                Map.entry("testMethodName", "testProcessRejectsWhenFeatureIsEnabledButShouldEscalateReturnsFalse"),
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

        assertTrue(updated.contains("boolean result = session.process(user, 0);"));
        assertTrue(updated.contains("assertFalse(result);"));
        assertTrue(updated.contains("verify(notificationService).sendDeactivationNotice(user);"));
        assertTrue(updated.contains("verify(auditTrailService).recordEvent(startsWith(\"Legacy upgrade rejected \"));"));
        assertTrue(updated.contains("testProcessPromotesWhenFeatureIsEnabledAndShouldEscalateReturnsTrue()"));
        assertTrue(updated.contains("boolean result = session.process(user, 80);"));
    }

    @Test
    void shouldStabilizeTemporalNowAssertionAroundActCall() {
        String source = """
                import java.time.Instant;
                import java.time.temporal.ChronoUnit;

                class UserTest {
                    void shouldReturnFalseAndSetLastLoginWhenInactive() {
                        User user = new User("JohnDoe", "john.doe@example.com");
                        user.deactivate();
                        Instant startOfTest = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                        boolean result = user.markLoggedIn();
                        assertThat(result).isFalse();
                        assertThat(user.getLastLogin()).withFailMessage("The last login time did not match the expected value.").isBetween(startOfTest.minusNanos(1000000), startOfTest.plusNanos(1000000));
                    }
                }
                """;

        RecipeOperationApplier applier = new RecipeOperationApplier("shouldReturnFalseAndSetLastLoginWhenInactive");
        String updated = applier.apply(source, Map.of(
                "type", "stabilize_temporal_now_assertion",
                "testMethodName", "shouldReturnFalseAndSetLastLoginWhenInactive",
                "sutMethod", "markLoggedIn"
        ));

        assertTrue(updated.contains("Instant beforeAct = Instant.now();"));
        assertTrue(updated.contains("boolean result = user.markLoggedIn();"));
        assertTrue(updated.contains("Instant afterAct = Instant.now();"));
        assertTrue(updated.contains("isBetween(beforeAct, afterAct);"));
        assertTrue(updated.indexOf("Instant beforeAct = Instant.now();")
                < updated.indexOf("boolean result = user.markLoggedIn();"));
        assertTrue(updated.indexOf("boolean result = user.markLoggedIn();")
                < updated.indexOf("Instant afterAct = Instant.now();"));
    }

    @Test
    void shouldPromoteZeroArgRefInitializerOnlyInsideTargetMethod() {
        String source = """
                class NEW_AUTOTest {
                    void anotherTest() {
                        Ref ref = new Ref();
                    }

                    void NEW_AUTO_EXECUTE_shouldReturnResolutionRefAndUpdateCacheMgr() {
                        Ref ref = new Ref();
                        Ref result = o.NEW_AUTO_EXECUTE(ref, plpClass);
                    }
                }
                """;

        RecipeOperationApplier applier = new RecipeOperationApplier("NEW_AUTO_EXECUTE_shouldReturnResolutionRefAndUpdateCacheMgr");
        String updated = applier.apply(source, Map.of(
                "type", "promote_ref_initializer_to_created_object",
                "testMethodName", "NEW_AUTO_EXECUTE_shouldReturnResolutionRefAndUpdateCacheMgr",
                "refVariable", "ref",
                "refTypeExpression", "Ref",
                "objectTypeFqcn", "bd.Abonent"
        ));

        assertTrue(updated.contains("void anotherTest() {\n        Ref ref = new Ref();"));
        assertTrue(updated.contains("void NEW_AUTO_EXECUTE_shouldReturnResolutionRefAndUpdateCacheMgr() {\n        Ref ref = new Ref(new bd.Abonent());"));
        assertFalse(updated.contains("void NEW_AUTO_EXECUTE_shouldReturnResolutionRefAndUpdateCacheMgr() {\n        Ref ref = new Ref();"));
    }

    @Test
    void shouldReplaceZeroArgRefInitializerWithMockFixtureForStaticInitFailures() {
        String source = """
                class NEW_AUTOTest {
                    void anotherTest() {
                        Ref ref = new Ref();
                    }

                    void test_NEW_AUTO_VALIDATE_WithNonNullAndValidThis() {
                        Ref ref = new Ref();
                        Varchar2 plpClass = new Varchar2("ABONENT");
                        NEW_AUTO o = new NEW_AUTO();
                        o.NEW_AUTO_VALIDATE(ref, plpClass, new Varchar2(), new Varchar2());
                    }
                }
                """;

        RecipeOperationApplier applier = new RecipeOperationApplier("test_NEW_AUTO_VALIDATE_WithNonNullAndValidThis");
        String updated = applier.apply(source, Map.of(
                "type", "replace_ref_initializer_with_mock_fixture",
                "testMethodName", "test_NEW_AUTO_VALIDATE_WithNonNullAndValidThis",
                "refVariable", "ref",
                "refTypeExpression", "Ref",
                "classIdLiteral", "ABONENT",
                "objectTypeFqcn", "bd.Abonent"
        ));

        assertTrue(updated.contains("void anotherTest() {\n        Ref ref = new Ref();"));
        assertTrue(updated.contains("void test_NEW_AUTO_VALIDATE_WithNonNullAndValidThis() {\n        Ref ref = mock(Ref.class);"));
        assertTrue(updated.contains("when(ref.isNull_booleanValue()).thenReturn(false);"));
        assertTrue(updated.contains("when(ref.isCreated()).thenReturn(true);"));
        assertTrue(updated.contains("when(ref.getClassId()).thenReturn(new Varchar2(\"ABONENT\"));"));
        assertFalse(updated.contains("void test_NEW_AUTO_VALIDATE_WithNonNullAndValidThis() {\n        Ref ref = new Ref();"));
    }

    @Test
    void shouldReplaceConstructorBackedRefInitializerWithMockFixtureForStaticInitFailures() {
        String source = """
                class NEW_AUTOTest {
                    void NEW_AUTO_VALIDATE_shouldSetLastRefAndLastClass() {
                        Abonent abonent = new Abonent();
                        Ref ref = new Ref(abonent);
                        Varchar2 plpClass = new Varchar2("ABONENT");
                        NEW_AUTO o = new NEW_AUTO();
                        o.NEW_AUTO_VALIDATE(ref, plpClass, new Varchar2(), new Varchar2());
                    }
                }
                """;

        RecipeOperationApplier applier = new RecipeOperationApplier("NEW_AUTO_VALIDATE_shouldSetLastRefAndLastClass");
        String updated = applier.apply(source, Map.of(
                "type", "replace_ref_initializer_with_mock_fixture",
                "testMethodName", "NEW_AUTO_VALIDATE_shouldSetLastRefAndLastClass",
                "refVariable", "ref",
                "refTypeExpression", "Ref",
                "classIdLiteral", "ABONENT",
                "objectTypeFqcn", "bd.Abonent"
        ));

        assertTrue(updated.contains("Abonent abonent = null;"));
        assertTrue(updated.contains("Ref ref = mock(Ref.class);"));
        assertTrue(updated.contains("when(ref.isNull_booleanValue()).thenReturn(false);"));
        assertTrue(updated.contains("when(ref.isCreated()).thenReturn(true);"));
        assertTrue(updated.contains("when(ref.getClassId()).thenReturn(new Varchar2(\"ABONENT\"));"));
        assertFalse(updated.contains("Ref ref = new Ref(abonent);"));
    }

    @Test
    void shouldReplaceInlineAbonentRefInitializerWithMockFixtureForStaticInitFailures() {
        String source = """
                class NEW_AUTOTest {
                    void NEW_AUTO_EXECUTE_ResolvesAndUpdatesCache() {
                        NEW_AUTO o = new NEW_AUTO();
                        Ref thisRef = new Ref(new Abonent());
                        Varchar2 plpClass = new Varchar2("ABONENT");
                        Ref result = o.NEW_AUTO_EXECUTE(thisRef, plpClass);
                    }
                }
                """;

        RecipeOperationApplier applier = new RecipeOperationApplier("NEW_AUTO_EXECUTE_ResolvesAndUpdatesCache");
        String updated = applier.apply(source, Map.of(
                "type", "replace_ref_initializer_with_mock_fixture",
                "testMethodName", "NEW_AUTO_EXECUTE_ResolvesAndUpdatesCache",
                "refVariable", "thisRef",
                "refTypeExpression", "Ref",
                "classIdLiteral", "ABONENT",
                "objectTypeFqcn", "bd.Abonent"
        ));

        assertTrue(updated.contains("Ref thisRef = mock(Ref.class);"));
        assertTrue(updated.contains("when(thisRef.isNull_booleanValue()).thenReturn(false);"));
        assertTrue(updated.contains("when(thisRef.isCreated()).thenReturn(true);"));
        assertTrue(updated.contains("when(thisRef.getClassId()).thenReturn(new Varchar2(\"ABONENT\"));"));
        assertFalse(updated.contains("Ref thisRef = new Ref(new Abonent());"));
    }

    @Test
    void shouldVerifyStaticVoidCallInsideMockScopeAndRemoveInstanceVerify() {
        String source = """
                class ParentConnectionWorkflowTest {
                    void testOpenParentConnection() {
                        User user = new User("JohnDoe", "john@example.com");
                        workflow.openParentConnection(user);
                        verify(legacyConnectionGateway).openRequiredChannel(anyString());
                        verify(auditTrailService).recordEvent("Parent connection opened for JohnDoe");
                    }
                }
                """;

        RecipeOperationApplier applier = new RecipeOperationApplier("testOpenParentConnection");
        String updated = applier.apply(source, Map.of(
                "type", "wrap_act_with_static_void_mock",
                "ownerClass", "LegacyConnectionGateway",
                "staticMethod", "openRequiredChannel",
                "stringLiteral", "legacy-shadow-db",
                "sutMethod", "openParentConnection"
        ));

        assertTrue(updated.contains("try (MockedStatic<LegacyConnectionGateway> legacyConnectionGatewayMock = mockStatic(LegacyConnectionGateway.class))"));
        assertTrue(updated.contains("legacyConnectionGatewayMock.verify(() -> LegacyConnectionGateway.openRequiredChannel(\"legacy-shadow-db\"));"));
        assertTrue(updated.indexOf("workflow.openParentConnection(user);")
                < updated.indexOf("legacyConnectionGatewayMock.verify(() -> LegacyConnectionGateway.openRequiredChannel(\"legacy-shadow-db\"));"));
        assertTrue(updated.indexOf("legacyConnectionGatewayMock.verify(() -> LegacyConnectionGateway.openRequiredChannel(\"legacy-shadow-db\"));")
                < updated.indexOf("}"));
        assertFalse(updated.contains("verify(legacyConnectionGateway).openRequiredChannel(anyString());"));
        assertTrue(updated.contains("verify(auditTrailService).recordEvent(\"Parent connection opened for JohnDoe\");"));
    }

    @Test
    void shouldMoveInjectMocksAnonymousSubclassInitializationIntoTestMethodBeforeStaticVoidAct() {
        String source = """
                class ParentConnectionWorkflowTest {
                    @InjectMocks
                    private ParentConnectionWorkflow workflow = new ParentConnectionWorkflow(auditTrailService) {};

                    void testOpenParentConnection() {
                        User user = new User("JohnDoe", "john@example.com");
                        workflow.openParentConnection(user);
                        verify(legacyConnectionGateway).openRequiredChannel(anyString());
                        verify(auditTrailService).recordEvent("Parent connection opened for JohnDoe");
                    }
                }
                """;

        RecipeOperationApplier applier = new RecipeOperationApplier("testOpenParentConnection");
        String updated = applier.apply(source, Map.of(
                "type", "wrap_act_with_static_void_mock",
                "ownerClass", "LegacyConnectionGateway",
                "staticMethod", "openRequiredChannel",
                "stringLiteral", "legacy-shadow-db",
                "sutMethod", "openParentConnection"
        ));

        assertFalse(updated.contains("@InjectMocks"));
        assertTrue(updated.contains("private ParentConnectionWorkflow workflow;"));
        assertTrue(updated.contains("workflow = new ParentConnectionWorkflow(auditTrailService) {};"));
        assertTrue(updated.indexOf("workflow = new ParentConnectionWorkflow(auditTrailService) {};")
                < updated.indexOf("try (MockedStatic<LegacyConnectionGateway>"));
        assertTrue(updated.contains("workflow.openParentConnection(user);"));
        assertTrue(updated.contains("legacyConnectionGatewayMock.verify(() -> LegacyConnectionGateway.openRequiredChannel(\"legacy-shadow-db\"));"));
        assertFalse(updated.contains("verify(legacyConnectionGateway).openRequiredChannel(anyString());"));
    }

    @Test
    void shouldRepairAlreadyWrappedStaticVoidMockByAddingScopedVerify() {
        String source = """
                class ParentConnectionWorkflowTest {
                    void testOpenParentConnection() {
                        User user = new User("JohnDoe", "john@example.com");
                        try (MockedStatic<LegacyConnectionGateway> legacyConnectionGatewayMock = mockStatic(LegacyConnectionGateway.class)) {
                            legacyConnectionGatewayMock.when(() -> LegacyConnectionGateway.openRequiredChannel("legacy-shadow-db"))
                                    .thenAnswer(invocation -> null);
                            workflow.openParentConnection(user);
                        }
                        verify(legacyConnectionGateway).openRequiredChannel(anyString());
                    }
                }
                """;

        RecipeOperationApplier applier = new RecipeOperationApplier("testOpenParentConnection");
        String updated = applier.apply(source, Map.of(
                "type", "wrap_act_with_static_void_mock",
                "ownerClass", "LegacyConnectionGateway",
                "staticMethod", "openRequiredChannel",
                "stringLiteral", "legacy-shadow-db",
                "sutMethod", "openParentConnection"
        ));

        assertTrue(updated.contains("workflow.openParentConnection(user);\n            legacyConnectionGatewayMock.verify(() -> LegacyConnectionGateway.openRequiredChannel(\"legacy-shadow-db\"));"));
        assertFalse(updated.contains("verify(legacyConnectionGateway).openRequiredChannel(anyString());"));
    }

    @Test
    void shouldAddMissingReboundAttemptsBeforeActCall() {
        String source = """
                class ShadowRollbackSessionTest {
                    void testRollbackHighRebound() {
                        User user = new User("jane.smith", "jane@example.com");
                        user.incrementAttempts();
                        when(featureToggleService.isEnabled("shadow-rollback")).thenReturn(true);
                        boolean result = session.rollback(user);
                        assertTrue(result);
                    }
                }
                """;

        RecipeOperationApplier applier = new RecipeOperationApplier("testRollbackHighRebound");
        String updated = applier.apply(source, Map.of(
                "type", "ensure_minimum_rebound_attempts",
                "testMethodName", "testRollbackHighRebound",
                "sutMethod", "rollback",
                "userVariable", "user",
                "minimumAttempts", "3"
        ));

        assertEquals(3, countOccurrences(updated, "user.incrementAttempts();"));
        assertTrue(updated.lastIndexOf("user.incrementAttempts();")
                < updated.indexOf("boolean result = session.rollback(user);"));
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
