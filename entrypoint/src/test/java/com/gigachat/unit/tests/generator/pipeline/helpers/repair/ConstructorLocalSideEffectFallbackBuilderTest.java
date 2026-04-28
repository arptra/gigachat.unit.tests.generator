package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.MockTarget;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodMetadata;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructorLocalSideEffectFallbackBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldBuildDisabledBranchBaselineFromConstructorLocalContextAfterE106() {
        ConstructorLocalSideEffectFallbackBuilder builder =
                new ConstructorLocalSideEffectFallbackBuilder(new PipelineLogger(tempDir));

        GeneratedTestSnippet fallback = builder.build(classInfo("LegacyWorkflowService"),
                coordinateShadowRollbackMethodInfo(),
                analysisSummary("LegacyWorkflowService", "coordinateShadowRollback"),
                invalidSnippet(),
                coordinateShadowRollbackContext(),
                "E106: forbidden Mockito usage on constructor-created local objects spy(session)");

        assertNotNull(fallback);
        assertTrue(fallback.methodBody().contains("when(featureToggleService.isEnabled(\"shadow-rollback\")).thenReturn(false);"));
        assertTrue(fallback.methodBody().contains("boolean result = service.coordinateShadowRollback(user);"));
        assertTrue(fallback.methodBody().contains("assertFalse(result);"));
        assertTrue(fallback.methodBody().contains("verify(auditTrailService).recordEvent(\"Shadow rollback skipped for \" + user.getUsername());"));
        assertTrue(fallback.methodBody().contains("verify(libraryComponent).load();"));
        assertTrue(fallback.imports().contains("static org.mockito.Mockito.verify"));
        assertTrue(fallback.imports().contains("static org.mockito.Mockito.when"));
        assertTrue(fallback.imports().contains("static org.mockito.Mockito.mock"));
    }

    @Test
    void shouldBuildFastPathForAnyMatchingConstructorLocalBooleanWorkflow() {
        ConstructorLocalSideEffectFallbackBuilder builder =
                new ConstructorLocalSideEffectFallbackBuilder(new PipelineLogger(tempDir));

        GeneratedTestSnippet fallback = builder.buildFastPath(classInfo("AuditWorkflow"),
                auditWorkflowMethodInfo(),
                analysisSummary("AuditWorkflow", "startAuditRollback"),
                auditWorkflowContext());

        assertNotNull(fallback);
        assertTrue(fallback.methodName().contains("StartAuditRollback"));
        assertTrue(fallback.methodName().contains("AuditModeDisabled"));
        assertTrue(fallback.methodBody().contains("when(featureToggleService.isEnabled(\"audit-mode\")).thenReturn(false);"));
        assertTrue(fallback.methodBody().contains("User user = new User(\"John Doe\", \"john.doe@example.com\");"));
        assertTrue(fallback.methodBody().contains("boolean result = service.startAuditRollback(user);"));
        assertTrue(fallback.methodBody().contains("verify(auditTrailService).recordEvent(\"Audit skipped for \" + user.getUsername());"));
        assertTrue(fallback.methodBody().contains("verify(libraryComponent).load();"));
        assertFalse(fallback.methodBody().contains("coordinateShadowRollback"));
    }

    @Test
    void shouldIgnoreWhenConstructorLocalContextIsMissing() {
        ConstructorLocalSideEffectFallbackBuilder builder =
                new ConstructorLocalSideEffectFallbackBuilder(new PipelineLogger(tempDir));

        GeneratedTestSnippet fallback = builder.build(classInfo("LegacyWorkflowService"),
                coordinateShadowRollbackMethodInfo(),
                analysisSummary("LegacyWorkflowService", "coordinateShadowRollback"),
                invalidSnippet(),
                new JSONObject(),
                "E109: void method invocation used inside Mockito.when(...)");

        assertNull(fallback);
    }

    private TestClassInfo classInfo(String className) {
        return new TestClassInfo(
                className,
                className + "Test",
                tempDir.resolve("src/test/java/com/example/app/service/" + className + "Test.java"),
                List.of(
                        "import com.example.app.model.User;",
                        "import com.example.lib.LibraryComponent;"),
                List.of());
    }

    private TestMethodInfo coordinateShadowRollbackMethodInfo() {
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration("""
                public boolean coordinateShadowRollback(User user) {
                    ShadowRollbackSession session = new ShadowRollbackSession(featureToggleService, auditTrailService, notificationService);
                    boolean rolledBack = session.rollback(user);
                    if (rolledBack) {
                        libraryComponent.close();
                    } else {
                        libraryComponent.load();
                    }
                    return rolledBack;
                }
                """);
        return new TestMethodInfo("public boolean coordinateShadowRollback(User user)",
                "boolean",
                declaration.getBody().orElseThrow().toString(),
                declaration);
    }

    private TestMethodInfo auditWorkflowMethodInfo() {
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration("""
                public boolean startAuditRollback(User user) {
                    AuditSession session = new AuditSession(featureToggleService, auditTrailService, notificationService);
                    boolean started = session.execute(user);
                    if (started) {
                        libraryComponent.close();
                    } else {
                        libraryComponent.load();
                    }
                    return started;
                }
                """);
        return new TestMethodInfo("public boolean startAuditRollback(User user)",
                "boolean",
                declaration.getBody().orElseThrow().toString(),
                declaration);
    }

    private Analyze.AnalysisSummary analysisSummary(String className, String methodName) {
        return new Analyze.AnalysisSummary(
                new MockPlan(
                        List.of(
                                new MockTarget("FeatureToggleService", "featureToggleService"),
                                new MockTarget("AuditTrailService", "auditTrailService"),
                                new MockTarget("NotificationService", "notificationService"),
                                new MockTarget("LibraryComponent", "libraryComponent")),
                        MockStrategy.MOCKITO,
                        List.of("featureToggleService", "auditTrailService", "notificationService", "libraryComponent"),
                        List.of()),
                new MethodAnalysisResult(new MethodMetadata(methodName,
                        "public boolean " + methodName + "(User user)",
                        "boolean"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext(className, "service", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(
                        className, List.of(new ConstructorMetadata(
                                className + "(FeatureToggleService featureToggleService, AuditTrailService auditTrailService, NotificationService notificationService, LibraryComponent libraryComponent)",
                                List.of(
                                        new ParameterMetadata("featureToggleService", "FeatureToggleService", List.of()),
                                        new ParameterMetadata("auditTrailService", "AuditTrailService", List.of()),
                                        new ParameterMetadata("notificationService", "NotificationService", List.of()),
                                        new ParameterMetadata("libraryComponent", "LibraryComponent", List.of())
                                ))),
                        "User", List.of(new ConstructorMetadata(
                                "User(String username, String email)",
                                List.of(
                                        new ParameterMetadata("username", "String", List.of()),
                                        new ParameterMetadata("email", "String", List.of())
                                )))),
                Map.of(),
                Set.of("User"),
                Set.of("boolean"));
    }

    private JSONObject coordinateShadowRollbackContext() {
        return baseContext("LegacyWorkflowService",
                "session",
                "ShadowRollbackSession",
                "rollback",
                """
                        public boolean rollback(User user) {
                            if (!featureToggleService.isEnabled("shadow-rollback")) {
                                auditTrailService.recordEvent("Shadow rollback skipped for " + user.getUsername());
                                return false;
                            }
                            notificationService.sendDeactivationNotice(user);
                            return true;
                        }
                        """);
    }

    private JSONObject auditWorkflowContext() {
        return baseContext("AuditWorkflow",
                "session",
                "AuditSession",
                "execute",
                """
                        public boolean execute(User user) {
                            if (!featureToggleService.isEnabled("audit-mode")) {
                                auditTrailService.recordEvent("Audit skipped for " + user.getUsername());
                                return false;
                            }
                            notificationService.sendWelcome(user);
                            return true;
                        }
                        """);
    }

    private JSONObject baseContext(String className,
                                   String localVariable,
                                   String localClass,
                                   String invokedMethod,
                                   String sourceSnippet) {
        return new JSONObject()
                .put("methodContext", new JSONObject()
                        .put("originalClassFqcn", "com.example.app.service." + className)
                        .put("imports", new JSONArray()
                                .put("import com.example.app.model.User;")
                                .put("import com.example.lib.LibraryComponent;")))
                .put("constructorLocalContexts", new JSONArray().put(new JSONObject()
                        .put("variable", localVariable)
                        .put("className", localClass)
                        .put("constructorArgumentCollaborators", new JSONArray()
                                .put("featureToggleService")
                                .put("auditTrailService")
                                .put("notificationService"))
                        .put("invokedMethods", new JSONArray().put(new JSONObject()
                                .put("name", invokedMethod)
                                .put("arity", 1)
                                .put("sourceSnippet", sourceSnippet)))));
    }

    private GeneratedTestSnippet invalidSnippet() {
        return new GeneratedTestSnippet(
                "LegacyWorkflowServiceTest",
                "testConstructorLocalWorkflow",
                "@Test void testConstructorLocalWorkflow() {}",
                List.of());
    }
}
