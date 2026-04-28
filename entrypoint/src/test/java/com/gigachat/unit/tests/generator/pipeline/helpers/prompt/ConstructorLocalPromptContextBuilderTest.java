package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.testagent.entrypoint.pipeline.helpers.analyze.DependencyInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.InvocationInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodMetadata;
import com.testagent.entrypoint.pipeline.helpers.analyze.MockType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructorLocalPromptContextBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldBuildConstructorLocalContextWithSourceSnippet() throws Exception {
        Path sessionFile = tempDir.resolve("src/main/java/com/example/app/legacy/LegacyUpgradeSession.java");
        Files.createDirectories(sessionFile.getParent());
        Files.writeString(sessionFile, """
                package com.example.app.legacy;

                import com.example.app.model.User;
                import com.example.app.service.AuditTrailService;
                import com.example.app.service.FeatureToggleService;
                import com.example.app.service.NotificationService;

                public class LegacyUpgradeSession {
                    private final FeatureToggleService featureToggleService;
                    private final AuditTrailService auditTrailService;
                    private final NotificationService notificationService;

                    public LegacyUpgradeSession(FeatureToggleService featureToggleService,
                                                AuditTrailService auditTrailService,
                                                NotificationService notificationService) {
                        this.featureToggleService = featureToggleService;
                        this.auditTrailService = auditTrailService;
                        this.notificationService = notificationService;
                    }

                    public boolean process(User user, int rawSignal) {
                        if (!featureToggleService.isEnabled("legacy-upgrade")) {
                            auditTrailService.recordEvent("Legacy upgrade skipped for " + user.getUsername());
                            return false;
                        }
                        notificationService.sendWelcome(user);
                        return true;
                    }
                }
                """);

        TestClassInfo classInfo = new TestClassInfo(
                "LegacyWorkflowService",
                "LegacyWorkflowServiceTest",
                tempDir.resolve("src/test/java/com/example/app/service/LegacyWorkflowServiceTest.java"),
                List.of("import com.example.app.legacy.LegacyUpgradeSession;"),
                List.of()
        );
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("featureToggleService"), List.of("session")),
                new MethodAnalysisResult(
                        new MethodMetadata("synchronizeLegacyUpgrade",
                                "public boolean synchronizeLegacyUpgrade(User user, int rawSignal)",
                                "boolean"),
                        List.of(new DependencyInfo(
                                "LegacyUpgradeSession",
                                "session",
                                MockType.CONSTRUCTOR,
                                "new LegacyUpgradeSession(featureToggleService, auditTrailService, notificationService)",
                                false,
                                true,
                                3)),
                        List.of(new InvocationInfo("session", "process", List.of("User", "int"))),
                        List.of(),
                        List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.service.LegacyWorkflowService", "service", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of("User", List.of(
                        "String getUsername()",
                        "String getEmail()",
                        "boolean isActive()",
                        "int getLoginAttempts()",
                        "void incrementAttempts()",
                        "void deactivate()",
                        "void activate()",
                        "boolean markLoggedIn()")),
                Set.of(),
                Set.of()
        );

        List<Map<String, Object>> contexts = new ConstructorLocalPromptContextBuilder(tempDir).build(classInfo, summary);

        assertEquals(1, contexts.size());
        assertEquals("session", contexts.get(0).get("variable"));
        assertEquals(List.of("featureToggleService", "auditTrailService", "notificationService"),
                contexts.get(0).get("constructorArgumentCollaborators"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> invokedMethods = (List<Map<String, Object>>) contexts.get(0).get("invokedMethods");
        assertEquals(1, invokedMethods.size());
        assertTrue(invokedMethods.get(0).get("sourceSnippet").toString().contains("featureToggleService.isEnabled(\"legacy-upgrade\")"));
        assertTrue(invokedMethods.get(0).get("sourceSnippet").toString().contains("notificationService.sendWelcome(user)"));
        @SuppressWarnings("unchecked")
        List<String> branchDrivers = (List<String>) invokedMethods.get(0).get("branchDrivers");
        @SuppressWarnings("unchecked")
        List<String> voidSideEffects = (List<String>) invokedMethods.get(0).get("voidSideEffects");
        assertTrue(branchDrivers.stream().anyMatch(driver -> driver.contains("featureToggleService.isEnabled(\"legacy-upgrade\")")));
        assertTrue(voidSideEffects.stream().anyMatch(effect -> effect.contains("notificationService.sendWelcome(user)")));
    }

    @Test
    void shouldExtractDerivedBranchDriversAndVoidSideEffectsForConstructorLocalSession() throws Exception {
        Path sessionFile = tempDir.resolve("src/main/java/com/example/app/legacy/ShadowRollbackSession.java");
        Files.createDirectories(sessionFile.getParent());
        Files.writeString(sessionFile, """
                package com.example.app.legacy;

                import com.example.app.model.User;
                import com.example.app.service.AuditTrailService;
                import com.example.app.service.FeatureToggleService;
                import com.example.app.service.NotificationService;

                public class ShadowRollbackSession {
                    private final FeatureToggleService featureToggleService;
                    private final AuditTrailService auditTrailService;
                    private final NotificationService notificationService;

                    public ShadowRollbackSession(FeatureToggleService featureToggleService,
                                                 AuditTrailService auditTrailService,
                                                 NotificationService notificationService) {
                        this.featureToggleService = featureToggleService;
                        this.auditTrailService = auditTrailService;
                        this.notificationService = notificationService;
                    }

                    public boolean rollback(User user) {
                        int rebound = LegacyScoreRules.reboundFactor(user.getLoginAttempts(), user.isActive());
                        if (!featureToggleService.isEnabled("shadow-rollback")) {
                            auditTrailService.recordEvent("Shadow rollback skipped for " + user.getUsername());
                            return false;
                        }
                        if (rebound > 6) {
                            notificationService.sendDeactivationNotice(user);
                            return true;
                        }
                        notificationService.sendWelcome(user);
                        return false;
                    }
                }
                """);

        TestClassInfo classInfo = new TestClassInfo(
                "LegacyWorkflowService",
                "LegacyWorkflowServiceTest",
                tempDir.resolve("src/test/java/com/example/app/service/LegacyWorkflowServiceTest.java"),
                List.of("import com.example.app.legacy.ShadowRollbackSession;"),
                List.of()
        );
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("featureToggleService"), List.of("session")),
                new MethodAnalysisResult(
                        new MethodMetadata("coordinateShadowRollback",
                                "public boolean coordinateShadowRollback(User user)",
                                "boolean"),
                        List.of(new DependencyInfo(
                                "ShadowRollbackSession",
                                "session",
                                MockType.CONSTRUCTOR,
                                "new ShadowRollbackSession(featureToggleService, auditTrailService, notificationService)",
                                false,
                                true,
                                3)),
                        List.of(new InvocationInfo("session", "rollback", List.of("User"))),
                        List.of(),
                        List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.service.LegacyWorkflowService", "service", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of("User", List.of(
                        "String getUsername()",
                        "String getEmail()",
                        "boolean isActive()",
                        "int getLoginAttempts()",
                        "void incrementAttempts()",
                        "void deactivate()",
                        "void activate()",
                        "boolean markLoggedIn()")),
                Set.of(),
                Set.of()
        );

        List<Map<String, Object>> contexts = new ConstructorLocalPromptContextBuilder(tempDir).build(classInfo, summary);

        assertEquals(1, contexts.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> invokedMethods = (List<Map<String, Object>>) contexts.get(0).get("invokedMethods");
        assertEquals(1, invokedMethods.size());
        @SuppressWarnings("unchecked")
        List<String> branchDrivers = (List<String>) invokedMethods.get(0).get("branchDrivers");
        @SuppressWarnings("unchecked")
        List<String> voidSideEffects = (List<String>) invokedMethods.get(0).get("voidSideEffects");
        @SuppressWarnings("unchecked")
        List<String> publicStateMutators = (List<String>) invokedMethods.get(0).get("publicStateMutators");
        assertTrue(branchDrivers.stream().anyMatch(driver -> driver.contains("featureToggleService.isEnabled(\"shadow-rollback\")")));
        assertTrue(branchDrivers.stream().anyMatch(driver -> driver.contains("LegacyScoreRules.reboundFactor(user.getLoginAttempts(), user.isActive())")));
        assertTrue(voidSideEffects.stream().anyMatch(effect -> effect.contains("notificationService.sendDeactivationNotice(user)")));
        assertTrue(voidSideEffects.stream().anyMatch(effect -> effect.contains("notificationService.sendWelcome(user)")));
        assertTrue(publicStateMutators.stream().anyMatch(mutator -> mutator.equals("user.incrementAttempts()")));
        assertTrue(publicStateMutators.stream().anyMatch(mutator -> mutator.equals("user.deactivate()")));
        assertTrue(publicStateMutators.stream().anyMatch(mutator -> mutator.equals("user.activate()")));
    }
}
