package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.pipeline.InvalidLLMResponseException;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.resources.GenerationPatternCatalog;
import com.gigachat.unit.tests.generator.resources.StateModelCatalog;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationValidationRetryBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAttachPatternHintsStateModelAndSnippetFeedback() {
        GenerationValidationRetryBuilder builder = new GenerationValidationRetryBuilder(
                new PipelineLogger(tempDir),
                new GenerationPatternCatalog(),
                new StateModelCatalog(),
                summary -> List.of("Use explicit constructor injection for \"Application\"."));
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                null,
                "",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.Application", "application", true, false),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Set.of());
        InvalidLLMResponseException exception = new InvalidLLMResponseException("E107: SUT construction bypasses mocks");
        GeneratedTestSnippet snippet = new GeneratedTestSnippet("ApplicationTest",
                "shouldRun",
                "@Test void shouldRun() { assertTrue(true); }",
                List.of());

        JSONObject retryContext = builder.buildRetryContext(new JSONObject().put("goal", "repair"),
                exception,
                summary,
                snippet);

        assertTrue(builder.shouldRetry(exception));
        assertEquals("Application", retryContext.getJSONObject("stateModel").getString("targetClass"));
        assertTrue(retryContext.toString().contains("Use explicit constructor injection"));
        assertTrue(retryContext.toString().contains("Keep the test shape"));
        assertTrue(retryContext.toString().contains("assertTrue(true)"));
    }

    @Test
    void shouldAttachSourceDerivedStateMethodsForInventedApiRetries() {
        GenerationValidationRetryBuilder builder = new GenerationValidationRetryBuilder(
                new PipelineLogger(tempDir),
                new GenerationPatternCatalog(),
                new StateModelCatalog(),
                summary -> List.of());
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                null,
                "",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.service.UserService", "service", true, false),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of("User", List.of(
                        "String getUsername()",
                        "boolean isActive()",
                        "void activate()",
                        "void deactivate()",
                        "void incrementAttempts()"
                )),
                Set.of(),
                Set.of());

        JSONObject retryContext = builder.buildRetryContext(new JSONObject().put("goal", "repair"),
                new InvalidLLMResponseException("E102: Invented method User.setActive(true)"),
                summary,
                new GeneratedTestSnippet("UserServiceTest",
                        "shouldRetry",
                        "@Test void shouldRetry() {}",
                        List.of()));

        String retryText = retryContext.getJSONArray("retryConstraints").toString();
        assertTrue(retryText.contains("For type \\\"User\\\" use only listed public methods"));
        assertTrue(retryText.contains("activate"));
        assertTrue(retryText.contains("incrementAttempts"));
    }

    @Test
    void shouldAttachStateModelForRequiredSutConstructorArgs() {
        GenerationValidationRetryBuilder builder = new GenerationValidationRetryBuilder(
                new PipelineLogger(tempDir),
                new GenerationPatternCatalog(),
                new StateModelCatalog(),
                summary -> List.of("Do not pass null literals to required constructor parameters of the SUT."));
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("repository"), List.of()),
                null,
                "",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.service.UserService", "service", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Set.of());

        InvalidLLMResponseException exception = new InvalidLLMResponseException(
                "E110: class under test \"UserService\" must not receive null literals for required constructor arguments");
        JSONObject retryContext = builder.buildRetryContext(new JSONObject().put("goal", "repair"),
                exception,
                summary,
                new GeneratedTestSnippet("UserServiceTest", "shouldRetry", "@Test void shouldRetry() {}", List.of()));

        assertTrue(builder.shouldRetry(exception));
        assertEquals("REQUIRED_SUT_CONSTRUCTOR_ARG_IS_NULL",
                retryContext.getJSONObject("stateModel").getString("failurePattern"));
        assertTrue(retryContext.getJSONArray("retryConstraints").toString()
                .contains("Do not pass null literals to required constructor arguments of the class under test."));
    }

    @Test
    void shouldAttachAllowedConstructorsForMissingConstructorMetadata() {
        GenerationValidationRetryBuilder builder = new GenerationValidationRetryBuilder(
                new PipelineLogger(tempDir),
                new GenerationPatternCatalog(),
                new StateModelCatalog(),
                summary -> List.of());
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of(), List.of()),
                null,
                "",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.service.UserService", "service", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of("User", List.of(
                        new ConstructorMetadata("User(String username, String email)",
                                List.of(
                                        new ParameterMetadata("username", "String", List.of()),
                                        new ParameterMetadata("email", "String", List.of())
                                ))
                )),
                Map.of("User", List.of(
                        "String getUsername()",
                        "void deactivate()"
                )),
                Set.of(),
                Set.of());

        JSONObject retryContext = builder.buildRetryContext(new JSONObject().put("goal", "repair"),
                new InvalidLLMResponseException("E104: Missing constructor metadata for User()"),
                summary,
                new GeneratedTestSnippet("UserServiceTest", "shouldRetry", "@Test void shouldRetry() {}", List.of()));

        String retryText = retryContext.getJSONArray("retryConstraints").toString();
        assertTrue(retryText.contains("User(String username, String email)"));
        assertTrue(retryText.contains("use only listed constructors"));
        assertTrue(retryText.contains("deactivate"));
    }

    @Test
    void shouldAttachFixtureReuseStateModelForLifecycleRedefinition() {
        GenerationValidationRetryBuilder builder = new GenerationValidationRetryBuilder(
                new PipelineLogger(tempDir),
                new GenerationPatternCatalog(),
                new StateModelCatalog(),
                summary -> List.of());

        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("repository"), List.of()),
                null,
                "",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.service.UserService", "service", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Set.of());

        JSONObject retryContext = builder.buildRetryContext(new JSONObject().put("goal", "repair"),
                new InvalidLLMResponseException("E112: existing generated test class already defines lifecycle setup for \"service\""),
                summary,
                new GeneratedTestSnippet("UserServiceTest", "shouldRetry", "@Test void shouldRetry() {}", List.of()));

        assertEquals("CONFLICTING_LIFECYCLE_FIXTURE_REDEFINITION",
                retryContext.getJSONObject("stateModel").getString("failurePattern"));
        assertTrue(retryContext.getJSONArray("retryConstraints").toString()
                .contains("Do not add a second lifecycle helper"));
        assertTrue(retryContext.getJSONArray("retryConstraints").toString()
                .contains("Reuse the existing fixture"));
    }

    @Test
    void shouldAttachVoidMutatorValueRetryConstraints() {
        GenerationValidationRetryBuilder builder = new GenerationValidationRetryBuilder(
                new PipelineLogger(tempDir),
                new GenerationPatternCatalog(),
                new StateModelCatalog(),
                summary -> List.of());

        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("repository"), List.of()),
                null,
                "",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.service.UserService", "service", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of("User", List.of(
                        new ConstructorMetadata("User(String username, String email)",
                                List.of(
                                        new ParameterMetadata("username", "String", List.of()),
                                        new ParameterMetadata("email", "String", List.of())
                                ))
                )),
                Map.of("User", List.of(
                        "String getUsername()",
                        "boolean isActive()",
                        "void deactivate()",
                        "void activate()"
                )),
                Set.of(),
                Set.of());

        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserServiceTest",
                "shouldRetry",
                """
                        @Test
                        void shouldRetry() {
                            users.add(new User("Bob", "bob@example.com").deactivate());
                        }
                        """,
                List.of());

        JSONObject retryContext = builder.buildRetryContext(new JSONObject().put("goal", "repair"),
                new InvalidLLMResponseException("E113: void mutator used as value expression new User(\"Bob\", \"bob@example.com\").deactivate()"),
                summary,
                snippet);

        assertEquals("VOID_STATE_MUTATOR_USED_AS_VALUE",
                retryContext.getJSONObject("stateModel").getString("failurePattern"));
        String retryText = retryContext.getJSONArray("retryConstraints").toString();
        assertTrue(retryText.contains("separate statements"));
        assertTrue(retryText.contains("For type \\\"User\\\" use only listed public methods"));
        assertTrue(retryText.contains("deactivate"));
    }

    @Test
    void shouldAttachSnippetTypeConstructorHintsForWrongImportRetries() {
        GenerationValidationRetryBuilder builder = new GenerationValidationRetryBuilder(
                new PipelineLogger(tempDir),
                new GenerationPatternCatalog(),
                new StateModelCatalog(),
                summary -> List.of());

        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("repository"), List.of()),
                null,
                "",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.service.UserService", "service", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of("User", List.of(
                        new ConstructorMetadata("User(String username, String email)",
                                List.of(
                                        new ParameterMetadata("username", "String", List.of()),
                                        new ParameterMetadata("email", "String", List.of())
                                ))
                )),
                Map.of("User", List.of(
                        "String getUsername()",
                        "void deactivate()"
                )),
                Set.of(),
                Set.of());

        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserServiceTest",
                "shouldRetry",
                """
                        @Test
                        void shouldRetry() {
                            User user = new User();
                        }
                        """,
                List.of("import com.example.app.util.NotificationService;"));

        JSONObject retryContext = builder.buildRetryContext(new JSONObject().put("goal", "repair"),
                new InvalidLLMResponseException("E111: project import does not resolve and should use the authoritative in-project type [com.example.app.util.NotificationService -> com.example.app.service.NotificationService]"),
                summary,
                snippet);

        String retryText = retryContext.getJSONArray("retryConstraints").toString();
        assertTrue(retryText.contains("User(String username, String email)"));
        assertTrue(retryText.contains("For type \\\"User\\\" use only listed public methods"));
        assertTrue(retryText.contains("deactivate"));
    }

    @Test
    void shouldAttachExactConstructorPathAndMockPromotionHintsForWrongImportRetries() {
        GenerationValidationRetryBuilder builder = new GenerationValidationRetryBuilder(
                new PipelineLogger(tempDir),
                new GenerationPatternCatalog(),
                new StateModelCatalog(),
                summary -> List.of());

        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("repository", "notificationService"), List.of()),
                null,
                "",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.service.UserService", "service", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(
                        "User", List.of(new ConstructorMetadata("User(String username, String email)",
                                List.of(
                                        new ParameterMetadata("username", "String", List.of()),
                                        new ParameterMetadata("email", "String", List.of())
                                ))),
                        "NotificationService", List.of(new ConstructorMetadata("NotificationService(EmailSender emailSender)",
                                List.of(new ParameterMetadata("emailSender", "EmailSender", List.of())))),
                        "UserService", List.of(new ConstructorMetadata("UserService(UserRepository repository, AuditTrailService auditTrailService, NotificationService notificationService)",
                                List.of(
                                        new ParameterMetadata("repository", "UserRepository", List.of()),
                                        new ParameterMetadata("auditTrailService", "AuditTrailService", List.of()),
                                        new ParameterMetadata("notificationService", "NotificationService", List.of())
                                )))
                ),
                Map.of(
                        "User", List.of("String getUsername()", "void deactivate()", "void activate()", "void incrementAttempts()"),
                        "NotificationService", List.of("void sendWelcome(User user)", "void sendDeactivationNotice(User user)")
                ),
                Set.of(),
                Set.of());

        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserServiceTest",
                "shouldRetry",
                """
                        @Test
                        void shouldRetry() {
                            User user = new User();
                            NotificationService notificationService = new NotificationService(null);
                        }
                        """,
                List.of("import com.example.app.util.NotificationService;"));

        JSONObject retryContext = builder.buildRetryContext(new JSONObject().put("goal", "repair"),
                new InvalidLLMResponseException("E111: project import does not resolve and should use the authoritative in-project type [com.example.app.util.NotificationService -> com.example.app.service.NotificationService]"),
                summary,
                snippet);

        String retryText = retryContext.getJSONArray("retryConstraints").toString();
        assertTrue(retryText.contains("Do not call User()"));
        assertTrue(retryText.contains("planned Mockito mock"));
        assertTrue(retryText.contains("notificationService"));
        assertTrue(retryText.contains("Do not pass null literals to constructor arguments of \\\"NotificationService\\\"")
                || retryText.contains("promote it to a Mockito mock"));
    }
}
