package com.acme.agent.validation;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.pipeline.InvalidLLMResponseException;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.pipeline.helpers.validation.GeneratedSnippetValidator;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SutConstructionValidationTest {

    @TempDir
    Path tempDir;

    private GeneratedSnippetValidator validator;
    private Analyze.AnalysisSummary analysisSummary;

    @BeforeEach
    void setUp() {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        PipelineLogger logger = new PipelineLogger(tempDir);
        Analyze analyze = new Analyze(registry);
        validator = new GeneratedSnippetValidator(logger, analyze, registry);

        MethodAnalysisResult methodAnalysis = new MethodAnalysisResult(new MethodMetadata("deployHiddenFeature", "deployHiddenFeature()", "void"),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        analysisSummary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("auditTrailService", "featureToggleService"), List.of()),
                methodAnalysis,
                "{}",
                Map.of(),
                new Analyze.TestTargetContext("Application", "application", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of("Application", List.of(
                        new ConstructorMetadata("Application(UserService userService, AuditTrailService auditTrailService, LibraryComponent libraryComponent, FeatureToggleService featureToggleService)",
                                List.of(
                                        new ParameterMetadata("userService", "UserService", List.of()),
                                        new ParameterMetadata("auditTrailService", "AuditTrailService", List.of()),
                                        new ParameterMetadata("libraryComponent", "LibraryComponent", List.of()),
                                        new ParameterMetadata("featureToggleService", "FeatureToggleService", List.of())
                                )),
                        new ConstructorMetadata("Application()", List.of())
                )),
                Map.of(),
                Set.of(),
                Set.of());
    }

    @Test
    void zeroArgSutConstructionFailsWhenMocksMustBeInjected() {
        assertThrows(InvalidLLMResponseException.class,
                () -> invokeValidator("""
                        package com.example.app;

                        class ApplicationTest {
                            private Application application;

                            void setUp() {
                                application = new Application();
                            }
                        }
                        """));
    }

    @Test
    void explicitMockAwareConstructorInjectionPassesValidation() {
        assertDoesNotThrow(() -> invokeValidator("""
                package com.example.app;

                class ApplicationTest {
                    private Application application;
                    private AuditTrailService auditTrailService;
                    private FeatureToggleService featureToggleService;

                    void setUp() {
                        application = new Application(null, auditTrailService, null, featureToggleService);
                    }
                }
                """));
    }

    @Test
    void strictConstructorRejectsNullLiteralForRequiredArgs() throws Exception {
        Path sourceFile = tempDir.resolve("src/main/java/com/example/app/service/UserService.java");
        java.nio.file.Files.createDirectories(sourceFile.getParent());
        java.nio.file.Files.writeString(sourceFile, """
                package com.example.app.service;

                import java.util.Objects;

                public class UserService {
                    public UserService(UserRepository repository,
                                       AuditTrailService auditTrailService,
                                       NotificationService notificationService) {
                        Objects.requireNonNull(repository, "repository");
                        Objects.requireNonNull(auditTrailService, "auditTrailService");
                        Objects.requireNonNull(notificationService, "notificationService");
                    }
                }
                """);

        Analyze.AnalysisSummary strictSummary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("repository"), List.of()),
                analysisSummary.methodAnalysis(),
                """
                        {
                          "packageName":"com.example.app.service",
                          "originalClassFqcn":"com.example.app.service.UserService"
                        }
                        """,
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.service.UserService", "service", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of("UserService", List.of(
                        new ConstructorMetadata("UserService(UserRepository repository, AuditTrailService auditTrailService, NotificationService notificationService)",
                                List.of(
                                        new ParameterMetadata("repository", "UserRepository", List.of()),
                                        new ParameterMetadata("auditTrailService", "AuditTrailService", List.of()),
                                        new ParameterMetadata("notificationService", "NotificationService", List.of())
                                ))
                )),
                Map.of(),
                Set.of(),
                Set.of());

        assertThrows(InvalidLLMResponseException.class, () -> validator.ensureRequiredConstructorArgumentsAreNotNull(
                tempDir,
                com.github.javaparser.StaticJavaParser.parse("""
                        package com.example.app.service;

                        class UserServiceTest {
                            private UserService service;
                            private UserRepository repository;

                            void setUp() {
                                service = new UserService(repository, null, null);
                            }
                        }
                        """),
                strictSummary));
    }

    @Test
    void conflictingLifecycleHelperThatReinitializesSutFailsValidation() throws Exception {
        Path existingTestPath = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        java.nio.file.Files.createDirectories(existingTestPath.getParent());
        java.nio.file.Files.writeString(existingTestPath, """
                package com.example.app.service;

                import org.junit.jupiter.api.BeforeEach;

                class UserServiceTest {
                    private UserService service;

                    @BeforeEach
                    void setUp() {
                        service = new UserService(repository, auditTrailService, notificationService);
                    }
                }
                """);

        Analyze.AnalysisSummary userServiceSummary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("repository"), List.of()),
                analysisSummary.methodAnalysis(),
                """
                        {
                          "packageName":"com.example.app.service",
                          "originalClassFqcn":"com.example.app.service.UserService"
                        }
                        """,
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

        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserServiceTest",
                "averageLoginAttempts_emptyUsers_returnsZero",
                "@Test void averageLoginAttempts_emptyUsers_returnsZero() {}",
                List.of(),
                List.of(),
                List.of(),
                List.of("""
                        @BeforeEach
                        void setup() {
                            service = new UserService(repository, new AuditTrailService(), new NotificationService(null));
                        }
                        """),
                """
                        package com.example.app.service;

                        import org.junit.jupiter.api.BeforeEach;
                        import org.junit.jupiter.api.Test;

                        class UserServiceTest {
                            private UserService service;

                            @BeforeEach
                            void setup() {
                                service = new UserService(repository, new AuditTrailService(), new NotificationService(null));
                            }

                            @Test
                            void averageLoginAttempts_emptyUsers_returnsZero() {
                            }
                        }
                        """);

        assertThrows(InvalidLLMResponseException.class, () -> validator.ensureNoConflictingLifecycleFixtureRedefinition(
                new TestClassInfo("com.example.app.service.UserService",
                        "UserServiceTest",
                        existingTestPath,
                        List.of(),
                        List.of()),
                snippet,
                com.github.javaparser.StaticJavaParser.parse(snippet.fullClassSource()),
                userServiceSummary));
    }

    @Test
    void identicalLifecycleHelperMayBeReusedWithoutValidationFailure() throws Exception {
        Path existingTestPath = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        java.nio.file.Files.createDirectories(existingTestPath.getParent());
        String existingSource = """
                package com.example.app.service;

                import org.junit.jupiter.api.BeforeEach;

                class UserServiceTest {
                    private UserService service;

                    @BeforeEach
                    public void setUp() {
                        service = new UserService(repository, auditTrailService, notificationService);
                    }
                }
                """;
        java.nio.file.Files.writeString(existingTestPath, existingSource);

        Analyze.AnalysisSummary userServiceSummary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("repository"), List.of()),
                analysisSummary.methodAnalysis(),
                """
                        {
                          "packageName":"com.example.app.service",
                          "originalClassFqcn":"com.example.app.service.UserService"
                        }
                        """,
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

        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserServiceTest",
                "findUser_validIndex_returnsCorrectUser",
                "@Test void findUser_validIndex_returnsCorrectUser() {}",
                List.of(),
                List.of(),
                List.of(),
                List.of("""
                        // Existing fixture setup reused here
                        @BeforeEach
                        void setUp() {
                            service = new UserService(repository, auditTrailService, notificationService);
                        }
                        """),
                existingSource);

        assertDoesNotThrow(() -> validator.ensureNoConflictingLifecycleFixtureRedefinition(
                new TestClassInfo("com.example.app.service.UserService",
                        "UserServiceTest",
                        existingTestPath,
                        List.of(),
                        List.of()),
                snippet,
                com.github.javaparser.StaticJavaParser.parse(snippet.fullClassSource()),
                userServiceSummary));
    }

    @Test
    void strongerLifecycleHelperWithSameSutBindingMayReuseExistingFixture() throws Exception {
        Path existingTestPath = tempDir.resolve("src/test/java/com/example/app/repository/UserRepositoryTest.java");
        java.nio.file.Files.createDirectories(existingTestPath.getParent());
        java.nio.file.Files.writeString(existingTestPath, """
                package com.example.app.repository;

                import org.junit.jupiter.api.BeforeEach;

                class UserRepositoryTest {
                    private UserRepository repository;

                    @BeforeEach
                    void setUp() {
                        this.repository = new UserRepository();
                    }
                }
                """);

        Analyze.AnalysisSummary repositorySummary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                analysisSummary.methodAnalysis(),
                """
                        {
                          "packageName":"com.example.app.repository",
                          "originalClassFqcn":"com.example.app.repository.UserRepository"
                        }
                        """,
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.repository.UserRepository", "repository", true, false),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Set.of());

        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserRepositoryTest",
                "shouldFindUserByUsername",
                "@Test void shouldFindUserByUsername() {}",
                List.of(),
                List.of(),
                List.of(),
                List.of("""
                        @BeforeEach
                        void setUp() {
                            repository = new UserRepository();
                            users.add(new User("Alice", "alice@example.com"));
                            users.forEach(repository::save);
                        }
                        """),
                """
                        package com.example.app.repository;

                        import org.junit.jupiter.api.BeforeEach;
                        import org.junit.jupiter.api.Test;

                        class UserRepositoryTest {
                            private UserRepository repository;

                            @BeforeEach
                            void setUp() {
                                repository = new UserRepository();
                                users.add(new User("Alice", "alice@example.com"));
                                users.forEach(repository::save);
                            }

                            @Test
                            void shouldFindUserByUsername() {
                            }
                        }
                        """);

        assertDoesNotThrow(() -> validator.ensureNoConflictingLifecycleFixtureRedefinition(
                new TestClassInfo("com.example.app.repository.UserRepository",
                        "UserRepositoryTest",
                        existingTestPath,
                        List.of(),
                        List.of()),
                snippet,
                com.github.javaparser.StaticJavaParser.parse(snippet.fullClassSource()),
                repositorySummary));
    }

    private void invokeValidator(String source) {
        validator.ensureTargetUsesMockAwareConstruction(
                com.github.javaparser.StaticJavaParser.parse(source),
                analysisSummary);
    }
}
