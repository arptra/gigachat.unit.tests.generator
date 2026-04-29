package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.report.parser.TestReportFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicExecutionRecipeBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldBuildConstructorLocalRuntimeAlignmentRecipe() throws IOException {
        Path hiddenFeature = tempDir.resolve("src/main/java/com/example/app/feature/HiddenFeature.java");
        Files.createDirectories(hiddenFeature.getParent());
        Files.writeString(hiddenFeature, """
                package com.example.app.feature;

                class HiddenFeature {
                    private final FeatureToggleService featureToggleService;
                    private final AuditTrailService auditTrailService;

                    HiddenFeature(FeatureToggleService featureToggleService, AuditTrailService auditTrailService) {
                        this.featureToggleService = featureToggleService;
                        this.auditTrailService = auditTrailService;
                    }

                    boolean activate() {
                        if (!featureToggleService.isEnabled("hidden")) {
                            return false;
                        }
                        auditTrailService.recordEvent("Activated hidden feature with value " + 6);
                        return true;
                    }

                    void recalibrate() {
                        auditTrailService.recordEvent("Recalibrated feature with factor " + 24);
                    }
                }
                """);

        TestMethodInfo methodInfo = new TestMethodInfo(
                "public void deployHiddenFeature()",
                "void",
                """
                        HiddenFeature hiddenFeature = new HiddenFeature(featureToggleService, auditTrailService);
                        if (hiddenFeature.activate()) {
                            hiddenFeature.recalibrate();
                        }
                        """
        );
        TestClassInfo classInfo = new TestClassInfo(
                "com.example.app.Application",
                "ApplicationTest",
                tempDir.resolve("src/test/java/com/example/app/ApplicationTest.java"),
                List.of(),
                List.of(methodInfo)
        );
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("featureToggleService", "auditTrailService"), List.of("hiddenFeature")),
                null,
                "{}",
                Map.of(),
                null,
                true,
                List.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                Map.of(),
                Map.of(),
                java.util.Set.of(),
                java.util.Set.of()
        );

        List<Map<String, Object>> recipes = new DeterministicExecutionRecipeBuilder().build(tempDir,
                classInfo,
                methodInfo,
                summary,
                new com.gigachat.unit.tests.generator.execute.ExecuteResult(false,
                        List.of("com.example.app.ApplicationTest.testDeployHiddenFeature"),
                        "",
                        "Wanted but not invoked"),
                List.of());

        assertFalse(recipes.isEmpty());
        assertEquals("CONSTRUCTOR_LOCAL_RUNTIME_ALIGNMENT", recipes.get(0).get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        assertTrue(operations.stream().anyMatch(operation -> "replace_string_literal_argument".equals(operation.get("type"))));
        assertTrue(operations.stream().anyMatch(operation -> "rewrite_verify_block_with_prefixes".equals(operation.get("type"))));
    }

    @Test
    void shouldBuildParentStaticVoidBlockerRecipe() {
        TestMethodInfo methodInfo = new TestMethodInfo(
                "public boolean executeInheritedShadowUpgrade(User user, int rawSignal)",
                "boolean",
                """
                        openParentConnection(user);
                        return service.executeInheritedShadowUpgrade(user, rawSignal);
                        """
        );
        TestClassInfo classInfo = new TestClassInfo(
                "com.example.app.service.InheritedStaticVoidWorkflowService",
                "InheritedStaticVoidWorkflowServiceTest",
                tempDir.resolve("src/test/java/com/example/app/service/InheritedStaticVoidWorkflowServiceTest.java"),
                List.of(),
                List.of(methodInfo)
        );
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("featureToggleService"), List.of("session")),
                null,
                "{}",
                Map.of(),
                null,
                true,
                List.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                Map.of(),
                Map.of(),
                java.util.Set.of(),
                java.util.Set.of()
        );

        List<Map<String, Object>> recipes = new DeterministicExecutionRecipeBuilder().build(tempDir,
                classInfo,
                methodInfo,
                summary,
                new com.gigachat.unit.tests.generator.execute.ExecuteResult(false,
                        List.of("com.example.app.service.InheritedStaticVoidWorkflowServiceTest.testExecuteInheritedShadowUpgradeWhenUpgradeIsSuccessful"),
                        "",
                        """
                                java.lang.IllegalStateException: Real DB connection attempt for legacy-shadow-db
                                   com.example.app.legacy.LegacyConnectionGateway.openRequiredChannel(LegacyConnectionGateway.java:9)
                                   com.example.app.service.ParentConnectionWorkflow.openParentConnection(ParentConnectionWorkflow.java:16)
                                """),
                List.of());

        assertFalse(recipes.isEmpty());
        assertEquals("PARENT_STATIC_VOID_BLOCKER", recipes.get(0).get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        assertTrue(operations.stream().anyMatch(operation -> "wrap_act_with_static_void_mock".equals(operation.get("type"))));
    }

    @Test
    void shouldBuildReboundThresholdAttemptsRecipeWhenHighReboundBranchIsUnderDriven() throws IOException {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/legacy/ShadowRollbackSessionTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.legacy;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                class ShadowRollbackSessionTest {
                    @Test
                    void testRollbackHighRebound() {
                        User user = new User("jane.smith", "jane@example.com");
                        user.incrementAttempts();
                        when(featureToggleService.isEnabled("shadow-rollback")).thenReturn(true);
                        boolean result = session.rollback(user);
                        assertTrue(result);
                        verify(notificationService).sendDeactivationNotice(user);
                    }
                }
                """);

        TestMethodInfo methodInfo = new TestMethodInfo(
                "public boolean rollback(User user)",
                "boolean",
                """
                        int rebound = LegacyScoreRules.reboundFactor(user.getLoginAttempts(), user.isActive());
                        LegacyTelemetry.emit("shadow-rollback", "candidate:" + user.getUsername());
                        if (!featureToggleService.isEnabled("shadow-rollback")) {
                            auditTrailService.recordEvent("Shadow rollback skipped for " + user.getUsername());
                            return false;
                        }
                        auditTrailService.recordEvent("Shadow rollback rebound " + rebound + " for " + user.getUsername());
                        if (rebound > 6) {
                            notificationService.sendDeactivationNotice(user);
                            return true;
                        }
                        notificationService.sendWelcome(user);
                        return false;
                        """
        );
        TestClassInfo classInfo = new TestClassInfo(
                "com.example.app.legacy.ShadowRollbackSession",
                "ShadowRollbackSessionTest",
                testFile,
                List.of(),
                List.of(methodInfo)
        );
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("featureToggleService", "auditTrailService", "notificationService"), List.of("LegacyScoreRules")),
                null,
                "{}",
                Map.of(),
                null,
                true,
                List.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                Map.of(),
                Map.of(),
                java.util.Set.of(),
                java.util.Set.of()
        );

        List<Map<String, Object>> recipes = new DeterministicExecutionRecipeBuilder().build(tempDir,
                classInfo,
                methodInfo,
                summary,
                new com.gigachat.unit.tests.generator.execute.ExecuteResult(false,
                        List.of("com.example.app.legacy.ShadowRollbackSessionTest.testRollbackHighRebound"),
                        "",
                        "org.opentest4j.AssertionFailedError: expected: <true> but was: <false>"),
                List.of(new TestReportFailure("ShadowRollbackSessionTest",
                        "testRollbackHighRebound",
                        "expected: <true> but was: <false>",
                        List.of())));

        assertFalse(recipes.isEmpty());
        assertEquals("REBOUND_THRESHOLD_ATTEMPTS_RUNTIME_ALIGNMENT", recipes.get(0).get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        assertEquals("ensure_minimum_rebound_attempts", operations.get(0).get("type"));
        assertEquals("3", operations.get(0).get("minimumAttempts"));
    }

    @Test
    void shouldBuildBadClassIdRefRecipeFromFailedTestsWhenReportFailuresAreMissing() throws IOException {
        Path testFile = tempDir.resolve("src/test/java/mtd/abonent/NEW_AUTOTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package mtd.abonent;

                import bd.Abonent.Ref;
                import rt.Varchar2;

                class NEW_AUTOTest {
                    void NEW_AUTO_EXECUTE_shouldReturnResolutionRefAndUpdateCacheMgr() {
                        Ref ref = new Ref();
                        Varchar2 plpClass = new Varchar2("TEST_CLASS");
                        NEW_AUTO o = new NEW_AUTO();
                        Ref result = o.NEW_AUTO_EXECUTE(ref, plpClass);
                    }
                }
                """);

        TestMethodInfo methodInfo = new TestMethodInfo(
                "public bd.Abonent.Ref NEW_AUTO_EXECUTE(final bd.Abonent.Ref THIS, final Varchar2 PLP$CLASS)",
                "bd.Abonent.Ref",
                """
                        final Resolution resolution = resolveRef(THIS, PLP$CLASS);
                        cache_mgr.reg_obj_change(resolution.classId, resolution.ref.getClassId(), Boolean.FALSE);
                        return resolution.ref;
                        """
        );
        TestClassInfo classInfo = new TestClassInfo(
                "mtd.abonent.NEW_AUTO",
                "NEW_AUTOTest",
                testFile,
                List.of(),
                List.of(methodInfo)
        );
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of(), List.of()),
                null,
                "{}",
                Map.of(),
                null,
                true,
                List.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                Map.of(),
                Map.of(),
                java.util.Set.of(),
                java.util.Set.of()
        );

        List<Map<String, Object>> recipes = new DeterministicExecutionRecipeBuilder().build(tempDir,
                classInfo,
                methodInfo,
                summary,
                new com.gigachat.unit.tests.generator.execute.ExecuteResult(false,
                        List.of("mtd.abonent.NEW_AUTOTest.NEW_AUTO_EXECUTE_shouldReturnResolutionRefAndUpdateCacheMgr"),
                        "",
                        "rt.exception.TransactionException: EXEC:BAD_CLASS_ID:TEST_CLASS"),
                List.of());

        assertFalse(recipes.isEmpty());
        assertEquals("REF_NULL_GUARD_RUNTIME_ALIGNMENT", recipes.get(0).get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        assertEquals("promote_ref_initializer_to_created_object", operations.get(0).get("type"));
        assertEquals("NEW_AUTO_EXECUTE_shouldReturnResolutionRefAndUpdateCacheMgr", operations.get(0).get("testMethodName"));
        assertEquals("ref", operations.get(0).get("refVariable"));
        assertEquals("Ref", operations.get(0).get("refTypeExpression"));
        assertEquals("bd.Abonent", operations.get(0).get("objectTypeFqcn"));
    }

    @Test
    void shouldBuildStaticInitRefFixtureRecipeForAbonentBootstrapFailure() throws IOException {
        Path testFile = tempDir.resolve("src/test/java/mtd/abonent/NEW_AUTOTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package mtd.abonent;

                import bd.Abonent.Ref;
                import rt.Varchar2;

                class NEW_AUTOTest {
                    void test_NEW_AUTO_VALIDATE_WithNonNullAndValidThis() {
                        Ref ref = new Ref();
                        Varchar2 plpClass = new Varchar2("ABONENT");
                        NEW_AUTO o = new NEW_AUTO();
                        o.NEW_AUTO_VALIDATE(ref, plpClass, new Varchar2(), new Varchar2());
                    }
                }
                """);

        TestMethodInfo methodInfo = new TestMethodInfo(
                "public final void NEW_AUTO_VALIDATE(final bd.Abonent.Ref THIS, final Varchar2 PLP$CLASS, final Varchar2 P_MESSAGE, final Varchar2 P_INFO)",
                "void",
                """
                        final Resolution resolution = resolveRef(THIS, PLP$CLASS);
                        lastRef = resolution.ref;
                        lastClass = resolution.classId;
                        """
        );
        TestClassInfo classInfo = new TestClassInfo(
                "mtd.abonent.NEW_AUTO",
                "NEW_AUTOTest",
                testFile,
                List.of(),
                List.of(methodInfo)
        );
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of(), List.of()),
                null,
                "{}",
                Map.of(),
                null,
                true,
                List.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                Map.of(),
                Map.of(),
                java.util.Set.of(),
                java.util.Set.of()
        );

        List<Map<String, Object>> recipes = new DeterministicExecutionRecipeBuilder().build(tempDir,
                classInfo,
                methodInfo,
                summary,
                new com.gigachat.unit.tests.generator.execute.ExecuteResult(false,
                        List.of("mtd.abonent.NEW_AUTOTest.test_NEW_AUTO_VALIDATE_WithNonNullAndValidThis"),
                        "",
                        """
                                java.lang.AssertionError: No global settings provided
                                Unexpected exception thrown: java.lang.NoClassDefFoundError: Could not initialize class bd.Abonent
                                Caused by: java.lang.NoClassDefFoundError: Could not initialize class bd.Abonent
                                at bd.Abonent$Ref.getTargetId(Abonent.java:130)
                                at mtd.abonent.NEW_AUTO.NEW_AUTO_VALIDATE(NEW_AUTO.java:27)
                                """),
                List.of(new TestReportFailure(
                        "mtd.abonent.NEW_AUTOTest",
                        "test_NEW_AUTO_VALIDATE_WithNonNullAndValidThis",
                        "Unexpected exception thrown: java.lang.NoClassDefFoundError: Could not initialize class bd.Abonent",
                        List.of(
                                "java.lang.AssertionError: No global settings provided",
                                "at ibso.GlobalSettings.get(GlobalSettings.java:90)",
                                "Caused by: java.lang.NoClassDefFoundError: Could not initialize class bd.Abonent",
                                "at bd.Abonent$Ref.getTargetId(Abonent.java:130)"
                        ))));

        assertFalse(recipes.isEmpty());
        assertEquals("STATIC_INIT_REF_FIXTURE_RUNTIME_ALIGNMENT", recipes.get(0).get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        assertEquals("replace_ref_initializer_with_mock_fixture", operations.get(0).get("type"));
        assertEquals("ref", operations.get(0).get("refVariable"));
        assertEquals("Ref", operations.get(0).get("refTypeExpression"));
    }

    @Test
    void shouldBuildStaticInitRefFixtureRecipeForDirectBootstrapAssertionFailure() throws IOException {
        Path testFile = tempDir.resolve("src/test/java/mtd/abonent/NEW_AUTOTest.java");
        Path scratchFile = tempDir.resolve("src/test/java/mtd/abonent/NEW_AUTOTestPreMergeScratch.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package mtd.abonent;

                class NEW_AUTOTest {
                    void existingGreenTest() {
                    }
                }
                """);
        Files.writeString(scratchFile, """
                package mtd.abonent;

                import bd.Abonent;
                import bd.Abonent.Ref;
                import rt.Varchar2;

                class NEW_AUTOTest {
                    void NEW_AUTO_EXECUTE_ResolvesAndUpdatesCache() {
                        Abonent abonent = new Abonent();
                        Ref ref = new Ref(abonent);
                        Varchar2 plpClass = new Varchar2("ABONENT");
                        NEW_AUTO o = new NEW_AUTO();
                        o.NEW_AUTO_EXECUTE(ref, plpClass);
                    }
                }
                """);

        TestMethodInfo methodInfo = new TestMethodInfo(
                "public final bd.Abonent.Ref NEW_AUTO_EXECUTE(final bd.Abonent.Ref THIS, final Varchar2 PLP$CLASS)",
                "bd.Abonent.Ref",
                """
                        final Resolution resolution = resolveRef(THIS, PLP$CLASS);
                        cache_mgr.reg_obj_change(resolution.classId, resolution.ref.getClassId(), Boolean.FALSE);
                        cache_mgr.cache_clear(resolution.classId);
                        lastRef = resolution.ref;
                        lastClass = resolution.classId;
                        return resolution.ref;
                        """
        );
        TestClassInfo classInfo = new TestClassInfo(
                        "mtd.abonent.NEW_AUTO",
                        "NEW_AUTOTest",
                        testFile,
                List.of(),
                List.of(methodInfo)
        );
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of(), List.of()),
                null,
                "{}",
                Map.of(),
                null,
                true,
                List.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                Map.of(),
                Map.of(),
                java.util.Set.of(),
                java.util.Set.of()
        );

        List<Map<String, Object>> recipes = new DeterministicExecutionRecipeBuilder().build(tempDir,
                classInfo,
                methodInfo,
                summary,
                new com.gigachat.unit.tests.generator.execute.ExecuteResult(false,
                        List.of("mtd.abonent.NEW_AUTOTestPreMergeScratch.NEW_AUTO_EXECUTE_ResolvesAndUpdatesCache"),
                        "",
                        """
                                java.lang.AssertionError: No global settings provided
                                at ibso.GlobalSettings.get(GlobalSettings.java:8)
                                at cls.Meta.get(Meta.java:13)
                                at bd.Abonent.<clinit>(Abonent.java:18)
                                at mtd.abonent.NEW_AUTOTest.NEW_AUTO_EXECUTE_ResolvesAndUpdatesCache(NEW_AUTOTest.java:10)
                                """),
                List.of(new TestReportFailure(
                        "mtd.abonent.NEW_AUTOTestPreMergeScratch",
                        "NEW_AUTO_EXECUTE_ResolvesAndUpdatesCache",
                        "java.lang.AssertionError: No global settings provided",
                        List.of(
                                "at ibso.GlobalSettings.get(GlobalSettings.java:8)",
                                "at cls.Meta.get(Meta.java:13)",
                                "at bd.Abonent.<clinit>(Abonent.java:18)"
                        ))));

        assertFalse(recipes.isEmpty());
        assertEquals("STATIC_INIT_REF_FIXTURE_RUNTIME_ALIGNMENT", recipes.get(0).get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        assertEquals("replace_ref_initializer_with_mock_fixture", operations.get(0).get("type"));
        assertEquals("ref", operations.get(0).get("refVariable"));
        assertEquals("Ref", operations.get(0).get("refTypeExpression"));
        assertEquals("bd.Abonent", operations.get(0).get("objectTypeFqcn"));
    }

    @Test
    void shouldBuildStaticInitRefFixtureRecipeWhenOnlyJUnitConsoleContainsMethodName() throws IOException {
        Path testFile = tempDir.resolve("src/test/java/mtd/abonent/NEW_AUTOTest.java");
        Path scratchFile = tempDir.resolve("src/test/java/mtd/abonent/NEW_AUTOTestPreMergeScratch.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package mtd.abonent;

                class NEW_AUTOTest {
                    void existingGreenTest() {
                    }
                }
                """);
        Files.writeString(scratchFile, """
                package mtd.abonent;

                import bd.Abonent;
                import bd.Abonent.Ref;
                import rt.Varchar2;

                class NEW_AUTOTestPreMergeScratch {
                    void shouldAssignMessageAndInfoWhenProvided() {
                        Abonent abonent = new Abonent();
                        Ref ref = new Ref(abonent);
                        Varchar2 plpClass = new Varchar2("TEST_CLASS");
                        Varchar2 pMessage = new Varchar2();
                        Varchar2 pInfo = new Varchar2();
                        NEW_AUTO o = new NEW_AUTO();
                        o.NEW_AUTO_VALIDATE(ref, plpClass, pMessage, pInfo);
                    }
                }
                """);

        TestMethodInfo methodInfo = new TestMethodInfo(
                "public final void NEW_AUTO_VALIDATE(final bd.Abonent.Ref THIS, final Varchar2 PLP$CLASS, final Varchar2 P_MESSAGE, final Varchar2 P_INFO)",
                "void",
                """
                        final Resolution resolution = resolveRef(THIS, PLP$CLASS);
                        lastRef = resolution.ref;
                        lastClass = resolution.classId;
                        if (P_MESSAGE != null) {
                            P_MESSAGE.assign("OK");
                        }
                        if (P_INFO != null) {
                            P_INFO.assign("VALIDATED:" + resolution.classId.getValue());
                        }
                        """
        );
        TestClassInfo classInfo = new TestClassInfo(
                "mtd.abonent.NEW_AUTO",
                "NEW_AUTOTest",
                testFile,
                List.of(),
                List.of(methodInfo)
        );
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of(), List.of()),
                null,
                "{}",
                Map.of(),
                null,
                true,
                List.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                Map.of(),
                Map.of(),
                java.util.Set.of(),
                java.util.Set.of()
        );

        List<Map<String, Object>> recipes = new DeterministicExecutionRecipeBuilder().build(tempDir,
                classInfo,
                methodInfo,
                summary,
                new com.gigachat.unit.tests.generator.execute.ExecuteResult(false,
                        List.of("mtd.abonent.NEW_AUTOTestPreMergeScratch"),
                        "",
                        """
                                Failures (1):
                                  JUnit Jupiter:NEW_AUTOTestPreMergeScratch:shouldAssignMessageAndInfoWhenProvided()
                                    MethodSource [className = 'mtd.abonent.NEW_AUTOTestPreMergeScratch', methodName = 'shouldAssignMessageAndInfoWhenProvided', methodParameterTypes = '']
                                    => java.lang.AssertionError: No global settings provided
                                       ibso.GlobalSettings.get(GlobalSettings.java:12)
                                       cls.Meta.get(Meta.java:13)
                                       bd.Abonent.<clinit>(Abonent.java:18)
                                       mtd.abonent.NEW_AUTOTestPreMergeScratch.shouldAssignMessageAndInfoWhenProvided(NEW_AUTOTestPreMergeScratch.java:22)
                                """),
                List.of());

        assertFalse(recipes.isEmpty());
        assertEquals("STATIC_INIT_REF_FIXTURE_RUNTIME_ALIGNMENT", recipes.get(0).get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        assertEquals("replace_ref_initializer_with_mock_fixture", operations.get(0).get("type"));
        assertEquals("shouldAssignMessageAndInfoWhenProvided", operations.get(0).get("testMethodName"));
        assertEquals("ref", operations.get(0).get("refVariable"));
        assertEquals("Ref", operations.get(0).get("refTypeExpression"));
        assertEquals("bd.Abonent", operations.get(0).get("objectTypeFqcn"));
    }

    @Test
    void shouldBuildSourceDerivedRuntimeAlignmentRecipeForComputedAuditPayloads() {
        TestMethodInfo methodInfo = new TestMethodInfo(
                "public boolean upgrade(User user, int rawSignal)",
                "boolean",
                """
                        int normalized = LegacyScoreRules.normalizeSignal(rawSignal, user.getLoginAttempts());
                        if (!featureToggleService.isEnabled("inherited-shadow")) {
                            auditTrailService.recordEvent("Inherited shadow skipped for " + user.getUsername());
                            return false;
                        }
                        if (normalized >= 10) {
                            notificationService.sendWelcome(user);
                            auditTrailService.recordEvent("Inherited shadow promoted " + user.getUsername() + " with score " + normalized);
                            return true;
                        }
                        notificationService.sendDeactivationNotice(user);
                        auditTrailService.recordEvent("Inherited shadow rejected " + user.getUsername() + " with score " + normalized);
                        return false;
                        """
        );
        TestClassInfo classInfo = new TestClassInfo(
                "com.example.app.legacy.InheritedShadowUpgradeSession",
                "InheritedShadowUpgradeSessionTest",
                tempDir.resolve("src/test/java/com/example/app/legacy/InheritedShadowUpgradeSessionTest.java"),
                List.of(),
                List.of(methodInfo)
        );
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO,
                        List.of("featureToggleService", "auditTrailService", "notificationService"),
                        List.of("normalizeSignal")),
                null,
                "{}",
                Map.of(),
                null,
                true,
                List.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                Map.of(),
                Map.of(),
                java.util.Set.of(),
                java.util.Set.of()
        );

        List<Map<String, Object>> recipes = new DeterministicExecutionRecipeBuilder().build(tempDir,
                classInfo,
                methodInfo,
                summary,
                new com.gigachat.unit.tests.generator.execute.ExecuteResult(false,
                        List.of("com.example.app.legacy.InheritedShadowUpgradeSessionTest.testUpgradeWithHighNormalizedScore"),
                        "",
                        """
                                Argument(s) are different! Wanted:
                                auditTrailService.recordEvent("Inherited shadow promoted test-user with score 15");
                                Actual invocations have different arguments:
                                auditTrailService.recordEvent("Inherited shadow promoted test-user with score 16");
                                """),
                List.of());

        assertFalse(recipes.isEmpty());
        assertEquals("SOURCE_DERIVED_RUNTIME_ALIGNMENT", recipes.get(0).get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        assertTrue(operations.stream().anyMatch(operation ->
                "align_verify_literals_to_prefixes".equals(operation.get("type"))
                        && "auditTrailService".equals(operation.get("mock"))
                        && operation.get("prefixes").toString().contains("Inherited shadow promoted ")));
        @SuppressWarnings("unchecked")
        List<String> requiredImports = (List<String>) recipes.get(0).get("requiredImports");
        assertTrue(requiredImports.contains("static org.mockito.ArgumentMatchers.startsWith"));
    }

    @Test
    void shouldBuildThresholdRejectionRecipeForHighSignalRejectExpectation() throws IOException {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/legacy/LegacyUpgradeSessionTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.legacy;

                class LegacyUpgradeSessionTest {
                    void testProcessRejectsWhenFeatureIsEnabledButShouldEscalateReturnsFalse() {
                        User user = new User("testUser", "test@example.com");
                        when(featureToggleService.isEnabled("legacy-upgrade")).thenReturn(true);
                        boolean result = session.process(user, 40);
                        assertFalse(result);
                        verify(notificationService).sendDeactivationNotice(user);
                    }
                }
                """);
        TestMethodInfo methodInfo = new TestMethodInfo(
                "public boolean process(User user, int rawSignal)",
                "boolean",
                """
                        int normalized = LegacyScoreRules.normalizeSignal(rawSignal, user.getLoginAttempts());
                        if (!featureToggleService.isEnabled("legacy-upgrade")) {
                            auditTrailService.recordEvent("Legacy upgrade skipped for " + user.getUsername());
                            return false;
                        }
                        if (LegacyScoreRules.shouldEscalate(normalized, user.isActive())) {
                            notificationService.sendWelcome(user);
                            auditTrailService.recordEvent("Legacy upgrade promoted " + user.getUsername() + " with score " + normalized);
                            return true;
                        }
                        notificationService.sendDeactivationNotice(user);
                        auditTrailService.recordEvent("Legacy upgrade rejected " + user.getUsername() + " with score " + normalized);
                        return false;
                        """
        );
        TestClassInfo classInfo = new TestClassInfo(
                "com.example.app.legacy.LegacyUpgradeSession",
                "LegacyUpgradeSessionTest",
                testFile,
                List.of(),
                List.of(methodInfo)
        );
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO,
                        List.of("featureToggleService", "auditTrailService", "notificationService"),
                        List.of("normalizeSignal")),
                null,
                "{}",
                Map.of(),
                null,
                true,
                List.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                Map.of(),
                Map.of(),
                java.util.Set.of(),
                java.util.Set.of()
        );

        List<Map<String, Object>> recipes = new DeterministicExecutionRecipeBuilder().build(tempDir,
                classInfo,
                methodInfo,
                summary,
                new com.gigachat.unit.tests.generator.execute.ExecuteResult(false,
                        List.of("com.example.app.legacy.LegacyUpgradeSessionTest.testProcessRejectsWhenFeatureIsEnabledButShouldEscalateReturnsFalse"),
                        "",
                        """
                                org.opentest4j.AssertionFailedError: expected: <false> but was: <true>
                                   com.example.app.legacy.LegacyUpgradeSessionTest.testProcessRejectsWhenFeatureIsEnabledButShouldEscalateReturnsFalse(LegacyUpgradeSessionTest.java:8)
                                """),
                List.of(new com.gigachat.unit.tests.generator.report.parser.TestReportFailure(
                        "com.example.app.legacy.LegacyUpgradeSessionTest",
                        "testProcessRejectsWhenFeatureIsEnabledButShouldEscalateReturnsFalse",
                        "org.opentest4j.AssertionFailedError: expected: <false> but was: <true>",
                        List.of())));

        assertFalse(recipes.isEmpty());
        assertEquals("THRESHOLD_REJECTION_RUNTIME_ALIGNMENT", recipes.get(0).get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        assertTrue(operations.stream().anyMatch(operation ->
                "align_threshold_rejection_branch".equals(operation.get("type"))
                        && "0".equals(operation.get("numericArgumentValue"))
                        && "Legacy upgrade rejected ".equals(operation.get("auditPrefix"))));
    }

    @Test
    void shouldBuildTemporalNowAssertionWindowRecipe() throws IOException {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/model/UserTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.model;

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
                """);
        TestMethodInfo methodInfo = new TestMethodInfo(
                "public boolean markLoggedIn()",
                "boolean",
                """
                        Instant now = Instant.now();
                        if (!active) {
                            this.lastLogin = now;
                            return false;
                        }
                        this.lastLogin = now;
                        this.loginAttempts = 0;
                        return true;
                        """
        );
        TestClassInfo classInfo = new TestClassInfo(
                "com.example.app.model.User",
                "UserTest",
                testFile,
                List.of(),
                List.of(methodInfo)
        );
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                null,
                "{}",
                Map.of(),
                null,
                true,
                List.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                Map.of(),
                Map.of(),
                java.util.Set.of(),
                java.util.Set.of()
        );

        List<Map<String, Object>> recipes = new DeterministicExecutionRecipeBuilder().build(tempDir,
                classInfo,
                methodInfo,
                summary,
                new com.gigachat.unit.tests.generator.execute.ExecuteResult(false,
                        List.of("com.example.app.model.UserTest.shouldReturnFalseAndSetLastLoginWhenInactive"),
                        "",
                        """
                                java.lang.AssertionError: The last login time did not match the expected value.
                                   com.example.app.model.UserTest.shouldReturnFalseAndSetLastLoginWhenInactive(UserTest.java:14)
                                """),
                List.of(new com.gigachat.unit.tests.generator.report.parser.TestReportFailure(
                        "com.example.app.model.UserTest",
                        "shouldReturnFalseAndSetLastLoginWhenInactive",
                        "java.lang.AssertionError: The last login time did not match the expected value.",
                        List.of())));

        assertFalse(recipes.isEmpty());
        assertEquals("TEMPORAL_NOW_ASSERTION_WINDOW", recipes.get(0).get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        assertTrue(operations.stream().anyMatch(operation ->
                "stabilize_temporal_now_assertion".equals(operation.get("type"))
                        && "markLoggedIn".equals(operation.get("sutMethod"))));
    }

    @Test
    void shouldBuildRefEqAlignmentForConstructorLocalObjectPassedToCollaborators() {
        TestMethodInfo methodInfo = new TestMethodInfo(
                "public User createUser(String username, String email)",
                "User",
                """
                        User user = new User(username, email);
                        repository.save(user);
                        auditTrailService.recordEvent("Created user " + username);
                        notificationService.sendWelcome(user);
                        return user;
                        """
        );
        TestClassInfo classInfo = new TestClassInfo(
                "com.example.app.service.UserService",
                "UserServiceTest",
                tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java"),
                List.of(),
                List.of(methodInfo)
        );
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO,
                        List.of("repository", "auditTrailService", "notificationService"),
                        List.of()),
                null,
                "{}",
                Map.of(),
                null,
                true,
                List.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                Map.of(),
                Map.of(),
                java.util.Set.of(),
                java.util.Set.of()
        );

        List<Map<String, Object>> recipes = new DeterministicExecutionRecipeBuilder().build(tempDir,
                classInfo,
                methodInfo,
                summary,
                new com.gigachat.unit.tests.generator.execute.ExecuteResult(false,
                        List.of("com.example.app.service.UserServiceTest.testCreateUser"),
                        "",
                        "Argument(s) are different! Actual invocations have different arguments"),
                List.of());

        assertFalse(recipes.isEmpty());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        assertTrue(operations.stream().anyMatch(operation ->
                "wrap_verify_argument_with_ref_eq".equals(operation.get("type"))
                        && "repository".equals(operation.get("mock"))
                        && "save".equals(operation.get("method"))));
        assertTrue(operations.stream().anyMatch(operation ->
                "wrap_verify_argument_with_ref_eq".equals(operation.get("type"))
                        && "notificationService".equals(operation.get("mock"))
                        && "sendWelcome".equals(operation.get("method"))));
        @SuppressWarnings("unchecked")
        List<String> requiredImports = (List<String>) recipes.get(0).get("requiredImports");
        assertTrue(requiredImports.contains("static org.mockito.ArgumentMatchers.refEq"));
    }
}
