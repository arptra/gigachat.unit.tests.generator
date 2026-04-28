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
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalSideEffectFallbackBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldBuildPresentBranchFallbackForOptionalDomainSideEffects() {
        OptionalSideEffectFallbackBuilder builder = new OptionalSideEffectFallbackBuilder(new PipelineLogger(tempDir));

        GeneratedTestSnippet fallback = builder.buildFastPath(classInfo(),
                disableUserMethodInfo(),
                analysisSummary(),
                context());

        assertNotNull(fallback);
        assertTrue(fallback.methodBody().contains("UserRepository repository = mock(UserRepository.class);"));
        assertTrue(fallback.methodBody().contains("User user = new User(username, \"john.doe@example.com\");"));
        assertTrue(fallback.methodBody().contains("when(repository.findByUsername(username)).thenReturn(Optional.of(user));"));
        assertTrue(fallback.methodBody().indexOf("String username") < fallback.methodBody().indexOf("new User(username"));
        assertTrue(fallback.methodBody().indexOf("String username") < fallback.methodBody().indexOf("findByUsername(username)"));
        assertTrue(fallback.methodBody().contains("boolean result = service.disableUser(username);"));
        assertTrue(fallback.methodBody().contains("assertEquals(true, result);"));
        assertTrue(fallback.methodBody().contains("assertEquals(false, user.isActive());"));
        assertTrue(fallback.methodBody().contains("verify(notificationService).sendDeactivationNotice(user);"));
        assertTrue(fallback.methodBody().contains("verify(auditTrailService).recordEvent(\"Disabled user \" + username);"));
        assertFalse(fallback.methodBody().contains("verify(user).deactivate()"));
        assertFalse(fallback.methodBody().contains("verify(repository).save"));
        assertTrue(fallback.imports().contains("java.util.Optional"));
        assertTrue(fallback.imports().contains("static org.mockito.Mockito.verify"));
        assertTrue(fallback.imports().contains("static org.junit.jupiter.api.Assertions.assertEquals"));
    }

    private TestClassInfo classInfo() {
        return new TestClassInfo(
                "UserService",
                "UserServiceTest",
                tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java"),
                List.of(
                        "import com.example.app.model.User;",
                        "import com.example.app.repository.UserRepository;"),
                List.of());
    }

    private TestMethodInfo disableUserMethodInfo() {
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration("""
                public boolean disableUser(String username) {
                    Optional<User> user = repository.findByUsername(username);
                    user.ifPresent(value -> {
                        value.deactivate();
                        notificationService.sendDeactivationNotice(value);
                        auditTrailService.recordEvent("Disabled user " + username);
                    });
                    return user.isPresent();
                }
                """);
        return new TestMethodInfo("public boolean disableUser(String username)",
                "boolean",
                declaration.getBody().orElseThrow().toString(),
                declaration);
    }

    private Analyze.AnalysisSummary analysisSummary() {
        return new Analyze.AnalysisSummary(
                new MockPlan(
                        List.of(
                                new MockTarget("UserRepository", "repository"),
                                new MockTarget("AuditTrailService", "auditTrailService"),
                                new MockTarget("NotificationService", "notificationService")),
                        MockStrategy.MOCKITO,
                        List.of("repository", "auditTrailService", "notificationService"),
                        List.of()),
                new MethodAnalysisResult(new MethodMetadata("disableUser",
                        "public boolean disableUser(String username)",
                        "boolean"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext("UserService", "service", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(
                        "UserService", List.of(new ConstructorMetadata(
                                "UserService(UserRepository repository, AuditTrailService auditTrailService, NotificationService notificationService)",
                                List.of(
                                        new ParameterMetadata("repository", "UserRepository", List.of()),
                                        new ParameterMetadata("auditTrailService", "AuditTrailService", List.of()),
                                        new ParameterMetadata("notificationService", "NotificationService", List.of())
                                ))),
                        "User", List.of(new ConstructorMetadata(
                                "User(String username, String email)",
                                List.of(
                                        new ParameterMetadata("username", "String", List.of()),
                                        new ParameterMetadata("email", "String", List.of())
                                )))),
                Map.of("User", List.of("String getUsername()", "boolean isActive()", "void deactivate()")),
                Set.of("String"),
                Set.of("boolean"));
    }

    private JSONObject context() {
        return new JSONObject()
                .put("methodContext", new JSONObject()
                        .put("originalClassFqcn", "com.example.app.service.UserService")
                        .put("imports", new JSONArray()
                                .put("import com.example.app.model.User;")
                                .put("import com.example.app.repository.UserRepository;")));
    }
}
