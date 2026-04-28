package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticBranchDriverFallbackBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRewriteLegacyUpgradeSessionHelpersToRealBranchDrivers() {
        StaticBranchDriverFallbackBuilder builder =
                new StaticBranchDriverFallbackBuilder(new PipelineLogger(tempDir));
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "LegacyUpgradeSessionTest",
                "testProcessWithDisabledFeature",
                """
                        @Test
                        void testProcessWithDisabledFeature() {
                            User user = new User("John Doe", "john@example.com");
                            when(featureToggleService.isEnabled("legacy-upgrade")).thenReturn(false);
                            boolean result = session.process(user, 100);
                            assertFalse(result);
                            verify(auditTrailService).recordEvent("Legacy upgrade skipped for John Doe");
                        }
                        """,
                List.of("import static org.mockito.ArgumentMatchers.any;"),
                List.of(),
                List.of(),
                List.of(
                        """
                                @Test
                                void testProcessWithEscalatedPromotion() {
                                    User user = new User("Jane Smith", "jane@example.com");
                                    when(featureToggleService.isEnabled("legacy-upgrade")).thenReturn(true);
                                    when(LegacyScoreRules.shouldEscalate(anyInt(), anyBoolean())).thenReturn(true);
                                    boolean result = session.process(user, 100);
                                    assertTrue(result);
                                    verify(notificationService).sendWelcome(user);
                                    verify(auditTrailService).recordEvent("Legacy upgrade promoted Jane Smith with score ");
                                }
                                """,
                        """
                                @Test
                                void testProcessWithRejection() {
                                    User user = new User("Alice Johnson", "alice@example.com");
                                    when(featureToggleService.isEnabled("legacy-upgrade")).thenReturn(true);
                                    when(LegacyScoreRules.shouldEscalate(anyInt(), anyBoolean())).thenReturn(false);
                                    boolean result = session.process(user, 100);
                                    assertFalse(result);
                                    verify(notificationService).sendDeactivationNotice(user);
                                    verify(auditTrailService).recordEvent("Legacy upgrade rejected Alice Johnson with score ");
                                }
                                """
                ),
                """
                        class LegacyUpgradeSessionTest {
                            @Test
                            void testProcessWithEscalatedPromotion() {
                                User user = new User("Jane Smith", "jane@example.com");
                                when(featureToggleService.isEnabled("legacy-upgrade")).thenReturn(true);
                                when(LegacyScoreRules.shouldEscalate(anyInt(), anyBoolean())).thenReturn(true);
                                boolean result = session.process(user, 100);
                                assertTrue(result);
                                verify(notificationService).sendWelcome(user);
                                verify(auditTrailService).recordEvent("Legacy upgrade promoted Jane Smith with score ");
                            }

                            @Test
                            void testProcessWithRejection() {
                                User user = new User("Alice Johnson", "alice@example.com");
                                when(featureToggleService.isEnabled("legacy-upgrade")).thenReturn(true);
                                when(LegacyScoreRules.shouldEscalate(anyInt(), anyBoolean())).thenReturn(false);
                                boolean result = session.process(user, 100);
                                assertFalse(result);
                                verify(notificationService).sendDeactivationNotice(user);
                                verify(auditTrailService).recordEvent("Legacy upgrade rejected Alice Johnson with score ");
                            }
                        }
                        """
        );

        GeneratedTestSnippet fallback = builder.build(
                snippet,
                legacyUpgradeSummary(),
                "E114: Mockito stubbing applied to non-mock static branch driver "
                        + "when(LegacyScoreRules.shouldEscalate(anyInt(), anyBoolean()))");

        assertNotNull(fallback);
        assertFalse(String.join("\n", fallback.helperMethods()).contains("LegacyScoreRules.shouldEscalate"));
        assertTrue(fallback.helperMethods().get(0).contains("session.process(user, 9)"));
        assertTrue(fallback.helperMethods().get(0).contains("Legacy upgrade promoted Jane Smith with score 10"));
        assertTrue(fallback.helperMethods().get(1).contains("user.deactivate();"));
        assertTrue(fallback.helperMethods().get(1).contains("session.process(user, 5)"));
        assertTrue(fallback.helperMethods().get(1).contains("Legacy upgrade rejected Alice Johnson with score 6"));
        assertFalse(fallback.fullClassSource().contains("LegacyScoreRules.shouldEscalate"));
    }

    private Analyze.AnalysisSummary legacyUpgradeSummary() {
        return new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("featureToggleService", "auditTrailService", "notificationService"), List.of()),
                new MethodAnalysisResult(new MethodMetadata("process", "process(User user, int rawSignal)", "boolean"),
                        List.of(),
                        List.of(),
                        List.of(
                                "LegacyScoreRules.normalizeSignal",
                                "LegacyTelemetry.emit",
                                "LegacyScoreRules.shouldEscalate"
                        ),
                        List.of()),
                "{}",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.legacy.LegacyUpgradeSession", "session", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(
                        "LegacyUpgradeSession", List.of(new ConstructorMetadata(
                                "LegacyUpgradeSession(FeatureToggleService featureToggleService, AuditTrailService auditTrailService, NotificationService notificationService)",
                                List.of(
                                        new ParameterMetadata("featureToggleService", "FeatureToggleService", List.of()),
                                        new ParameterMetadata("auditTrailService", "AuditTrailService", List.of()),
                                        new ParameterMetadata("notificationService", "NotificationService", List.of())
                                ))),
                        "User", List.of(new ConstructorMetadata(
                                "User(String username, String email)",
                                List.of(
                                        new ParameterMetadata("username", "String", List.of()),
                                        new ParameterMetadata("email", "String", List.of())
                                )))
                ),
                Map.of(
                        "User", List.of("boolean isActive()", "int getLoginAttempts()", "void deactivate()", "void activate()", "void incrementAttempts()"),
                        "FeatureToggleService", List.of("boolean isEnabled(String featureName)"),
                        "NotificationService", List.of("void sendWelcome(User user)", "void sendDeactivationNotice(User user)")
                ),
                Set.of(),
                Set.of());
    }
}
