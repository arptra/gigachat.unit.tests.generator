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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructorReturnSideEffectFallbackBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldBuildFastPathForConstructedReturnValueAndCollaboratorSideEffects() {
        ConstructorReturnSideEffectFallbackBuilder builder =
                new ConstructorReturnSideEffectFallbackBuilder(new PipelineLogger(tempDir));

        GeneratedTestSnippet fallback = builder.buildFastPath(classInfo(),
                createUserMethodInfo(),
                analysisSummary(),
                context());

        assertNotNull(fallback);
        assertTrue(fallback.methodBody().contains("String username = \"John Doe\";"));
        assertTrue(fallback.methodBody().contains("String email = \"john.doe@example.com\";"));
        assertTrue(fallback.methodBody().contains("UserService service = new UserService(repository, auditTrailService, notificationService);"));
        assertTrue(fallback.methodBody().contains("User result = service.createUser(username, email);"));
        assertTrue(fallback.methodBody().contains("assertEquals(username, result.getUsername());"));
        assertTrue(fallback.methodBody().contains("assertEquals(email, result.getEmail());"));
        assertTrue(fallback.methodBody().contains("verify(repository).save(result);"));
        assertTrue(fallback.methodBody().contains("verify(auditTrailService).recordEvent(\"Created user \" + username);"),
                fallback.methodBody());
        assertTrue(fallback.methodBody().contains("verify(notificationService).sendWelcome(result);"),
                fallback.methodBody());
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

    private TestMethodInfo createUserMethodInfo() {
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration("""
                public User createUser(String username, String email) {
                    User user = new User(username, email);
                    repository.save(user);
                    auditTrailService.recordEvent("Created user " + username);
                    notificationService.sendWelcome(user);
                    return user;
                }
                """);
        return new TestMethodInfo("public User createUser(String username, String email)",
                "User",
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
                        List.of("user")),
                new MethodAnalysisResult(new MethodMetadata("createUser",
                        "public User createUser(String username, String email)",
                        "User"),
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
                Map.of("UserService", List.of(new ConstructorMetadata(
                                "UserService(UserRepository repository, AuditTrailService auditTrailService, NotificationService notificationService)",
                                List.of(
                                        new ParameterMetadata("repository", "UserRepository", List.of()),
                                        new ParameterMetadata("auditTrailService", "AuditTrailService", List.of()),
                                        new ParameterMetadata("notificationService", "NotificationService", List.of())
                                )))),
                Map.of("User", List.of("String getUsername()", "String getEmail()")),
                Set.of("String"),
                Set.of("User"));
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
