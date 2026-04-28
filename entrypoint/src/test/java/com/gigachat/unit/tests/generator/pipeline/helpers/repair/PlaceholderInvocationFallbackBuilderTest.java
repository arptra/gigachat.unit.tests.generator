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
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodMetadata;

class PlaceholderInvocationFallbackBuilderTest {

    private final PlaceholderInvocationFallbackBuilder builder = new PlaceholderInvocationFallbackBuilder();

    @Test
    void buildCreatesBootstrapInvocationForInstanceMethod() {
        TestClassInfo classInfo = new TestClassInfo(
                "Application",
                "ApplicationTest",
                Path.of("/tmp/src/test/java/com/example/app/ApplicationTest.java"),
                List.of(
                        "import com.example.app.service.UserService;",
                        "import com.example.app.service.AuditTrailService;",
                        "import com.example.lib.LibraryComponent;",
                        "import com.example.app.service.FeatureToggleService;"
                ),
                List.of()
        );
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration("public void shutdown() {}");
        TestMethodInfo methodInfo = new TestMethodInfo("public void shutdown()", "void", "{}", declaration);
        Analyze.AnalysisSummary analysisSummary = new Analyze.AnalysisSummary(
                new MockPlan(
                        List.of(
                                new MockTarget("com.example.app.service.AuditTrailService", "auditTrailService"),
                                new MockTarget("com.example.lib.LibraryComponent", "libraryComponent")
                        ),
                        MockStrategy.MOCKITO,
                        List.of("auditTrailService", "libraryComponent"),
                        List.of()
                ),
                new MethodAnalysisResult(new MethodMetadata("shutdown", "public void shutdown()", "void"), List.of(), List.of(), List.of(), List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext("Application", "application", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(
                        "Application", List.of(new ConstructorMetadata(
                                "Application(UserService userService, AuditTrailService auditTrailService, LibraryComponent libraryComponent, FeatureToggleService featureToggleService)",
                                List.of(
                                        new ParameterMetadata("userService", "UserService", List.of()),
                                        new ParameterMetadata("auditTrailService", "AuditTrailService", List.of()),
                                        new ParameterMetadata("libraryComponent", "LibraryComponent", List.of()),
                                        new ParameterMetadata("featureToggleService", "FeatureToggleService", List.of())
                                ))),
                        "com.example.app.service.FeatureToggleService", List.of(new ConstructorMetadata("FeatureToggleService()", List.of()))
                ),
                Map.of(),
                Set.of(),
                Set.of()
        );

        GeneratedTestSnippet fallback = builder.build(classInfo,
                methodInfo,
                analysisSummary,
                new GeneratedTestSnippet("ApplicationTest", "shouldPublicVoidShutdown", "@Test void shouldPublicVoidShutdown() {}", List.of()));

        assertNotNull(fallback);
        assertTrue(fallback.methodBody().contains("application = new "));
        assertTrue(fallback.methodBody().contains("application.shutdown();"));
        assertTrue(fallback.methodBody().contains("org.mockito.Mockito.mock("));
    }

    @Test
    void buildCreatesBootstrapInvocationForStaticMethod() {
        TestClassInfo classInfo = new TestClassInfo(
                "MathUtil",
                "MathUtilTest",
                Path.of("/tmp/src/test/java/com/example/app/util/MathUtilTest.java"),
                List.of(),
                List.of()
        );
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration("public static double average(int total, int count) { return 0; }");
        TestMethodInfo methodInfo = new TestMethodInfo("public static double average(int total, int count)",
                "double",
                "{ return 0; }",
                declaration);
        Analyze.AnalysisSummary analysisSummary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                new MethodAnalysisResult(new MethodMetadata("average", "public static double average(int total, int count)", "double"), List.of(), List.of(), List.of(), List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext("MathUtil", "mathUtil", false, true),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of("int"),
                Set.of("double")
        );

        GeneratedTestSnippet fallback = builder.build(classInfo,
                methodInfo,
                analysisSummary,
                new GeneratedTestSnippet("MathUtilTest", "shouldPublicStaticDoubleAverageIntTotalInt", "@Test void shouldPublicStaticDoubleAverageIntTotalInt() {}", List.of()));

        assertNotNull(fallback);
        assertTrue(fallback.methodBody().contains("MathUtil.average(10, 10);"));
        assertTrue(fallback.imports().contains("org.junit.jupiter.api.Test"));
    }

    @Test
    void buildFastPathCreatesNotNullAssertionForStaticFactoryTarget() {
        TestClassInfo classInfo = new TestClassInfo(
                "EmailSender",
                "EmailSenderTest",
                Path.of("/tmp/src/test/java/com/example/app/service/EmailSenderTest.java"),
                List.of(),
                List.of()
        );
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration(
                "public static EmailSender systemSender() { return new EmailSender(); }");
        TestMethodInfo methodInfo = new TestMethodInfo("public static EmailSender systemSender()",
                "EmailSender",
                "{ return new EmailSender(); }",
                declaration);
        Analyze.AnalysisSummary analysisSummary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                new MethodAnalysisResult(new MethodMetadata("systemSender", "public static EmailSender systemSender()", "EmailSender"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext("EmailSender", "emailSender", false, true),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of("EmailSender", List.of(new ConstructorMetadata("EmailSender()", List.of()))),
                Map.of(),
                Set.of(),
                Set.of()
        );

        GeneratedTestSnippet fastPath = builder.buildFastPath(classInfo,
                methodInfo,
                analysisSummary,
                new GeneratedTestSnippet("EmailSenderTest", "shouldInvokeSystemSender", "", List.of()));

        assertNotNull(fastPath);
        assertTrue(fastPath.methodBody().contains(".systemSender();"));
        assertTrue(fastPath.methodBody().contains("org.junit.jupiter.api.Assertions.assertNotNull(result);"));
    }

    @Test
    void buildFastPathCreatesCaseInsensitiveOptionalLookupThroughPublicSave() {
        TestClassInfo classInfo = new TestClassInfo(
                "com.example.app.repository.UserRepository",
                "UserRepositoryTest",
                Path.of("/tmp/src/test/java/com/example/app/repository/UserRepositoryTest.java"),
                List.of("import com.example.app.model.User;"),
                List.of()
        );
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration("""
                public Optional<User> findByUsername(String username) {
                    return users.stream()
                            .filter(user -> user.getUsername().equalsIgnoreCase(username))
                            .findFirst();
                }
                """);
        TestMethodInfo methodInfo = new TestMethodInfo(
                "public Optional<User> findByUsername(String username)",
                "Optional<User>",
                declaration.getBody().orElseThrow().toString(),
                declaration);
        Analyze.AnalysisSummary analysisSummary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                new MethodAnalysisResult(new MethodMetadata("findByUsername", "public Optional<User> findByUsername(String username)", "Optional<User>"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.repository.UserRepository", "repository", true, false),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(
                        "UserRepository", List.of(new ConstructorMetadata("UserRepository()", List.of())),
                        "User", List.of(new ConstructorMetadata("User(String username, String email)", List.of(
                                new ParameterMetadata("username", "String", List.of()),
                                new ParameterMetadata("email", "String", List.of())
                        )))
                ),
                Map.of(
                        "UserRepository", List.of(
                                "User save(User user)",
                                "Optional<User> findByUsername(String username)",
                                "List<User> findAll()",
                                "boolean delete(String username)")
                ),
                Set.of("String"),
                Set.of("Optional")
        );

        GeneratedTestSnippet fallback = builder.buildFastPath(classInfo,
                methodInfo,
                analysisSummary,
                new GeneratedTestSnippet("UserRepositoryTest", "shouldFindExistingUserByUsername", "", List.of()));

        assertNotNull(fallback);
        assertTrue(fallback.methodBody().contains("repository.save(savedUser);"));
        assertTrue(fallback.methodBody().contains("repository.findByUsername(\"JOHN.DOE\")"));
        assertTrue(fallback.methodBody().contains("assertThat(foundUser).isPresent();"));
        assertTrue(fallback.methodBody().contains("assertThat(foundUser.get()).isSameAs(savedUser);"));
        assertTrue(fallback.methodBody().contains("repository.findByUsername(\"missing-user\")"));
        assertTrue(fallback.methodBody().contains("isEmpty();"));
        assertTrue(fallback.imports().contains("static org.assertj.core.api.Assertions.assertThat"));
    }

    @Test
    void buildFastPathCreatesStateTransitionForActivateTarget() {
        TestClassInfo classInfo = new TestClassInfo(
                "User",
                "UserTest",
                Path.of("/tmp/src/test/java/com/example/app/model/UserTest.java"),
                List.of(),
                List.of()
        );
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration(
                "public void activate() { this.active = true; }");
        TestMethodInfo methodInfo = new TestMethodInfo("public void activate()",
                "void",
                "{ this.active = true; }",
                declaration);
        Analyze.AnalysisSummary analysisSummary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                new MethodAnalysisResult(new MethodMetadata("activate", "public void activate()", "void"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext("User", "user", true, false),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of("User", List.of(new ConstructorMetadata(
                        "User(String username, String email)",
                        List.of(
                                new ParameterMetadata("username", "String", List.of()),
                                new ParameterMetadata("email", "String", List.of())
                        )))),
                Map.of("User", List.of("boolean isActive()", "void activate()", "void deactivate()")),
                Set.of(),
                Set.of()
        );

        GeneratedTestSnippet fastPath = builder.buildFastPath(classInfo,
                methodInfo,
                analysisSummary,
                new GeneratedTestSnippet("UserTest", "shouldActivateUser", "", List.of()));

        assertNotNull(fastPath);
        assertTrue(fastPath.methodBody().contains("user.deactivate();"));
        assertTrue(fastPath.methodBody().contains("Assertions.assertFalse(user.isActive())"));
        assertTrue(fastPath.methodBody().contains("user.activate();"));
        assertTrue(fastPath.methodBody().contains("Assertions.assertTrue(user.isActive())"));
    }

    @Test
    void buildFastPathCreatesFixedClockSnapshotAccessorTest() {
        TestClassInfo classInfo = new TestClassInfo(
                "AuditTrailService",
                "AuditTrailServiceTest",
                Path.of("/tmp/src/test/java/com/example/app/service/AuditTrailServiceTest.java"),
                List.of(),
                List.of()
        );
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration("""
                public java.util.List<AuditRecord> events() {
                    return java.util.Collections.unmodifiableList(events);
                }
                """);
        TestMethodInfo methodInfo = new TestMethodInfo("public java.util.List<AuditRecord> events()",
                "java.util.List<AuditRecord>",
                declaration.getBody().orElseThrow().toString(),
                declaration);
        Analyze.AnalysisSummary analysisSummary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                new MethodAnalysisResult(new MethodMetadata("events", "public java.util.List<AuditRecord> events()", "java.util.List<AuditRecord>"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext("AuditTrailService", "service", true, false),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of("AuditTrailService", List.of(
                        new ConstructorMetadata("AuditTrailService()", List.of()),
                        new ConstructorMetadata("AuditTrailService(Clock clock)",
                                List.of(new ParameterMetadata("clock", "Clock", List.of()))))),
                Map.of(
                        "AuditTrailService", List.of("void recordEvent(String event)", "java.util.List<AuditRecord> events()"),
                        "AuditRecord", List.of("String message()", "Instant timestamp()")),
                Set.of(),
                Set.of()
        );

        GeneratedTestSnippet fastPath = builder.buildFastPath(classInfo,
                methodInfo,
                analysisSummary,
                new GeneratedTestSnippet("AuditTrailServiceTest", "shouldReturnEventsSnapshot", "", List.of()));

        assertNotNull(fastPath);
        assertTrue(fastPath.methodBody().contains("java.time.Clock.fixed"));
        assertTrue(fastPath.methodBody().contains("service.recordEvent(\"coverage-event\");"));
        assertTrue(fastPath.methodBody().contains("var result = service.events();"));
        assertTrue(fastPath.methodBody().contains("assertEquals(\"coverage-event\", result.get(0).message())"));
        assertTrue(fastPath.methodBody().contains("assertThrows(UnsupportedOperationException.class, result::clear)"));
    }

    @Test
    void buildFastPathCreatesInstanceMutableCollectionCountTest() {
        TestClassInfo classInfo = new TestClassInfo(
                "AuditTrailService",
                "AuditTrailServiceTest",
                Path.of("/tmp/src/test/java/com/example/app/service/AuditTrailServiceTest.java"),
                List.of(),
                List.of()
        );
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration("""
                public int countEvents() {
                    return events.size();
                }
                """);
        TestMethodInfo methodInfo = new TestMethodInfo("public int countEvents()",
                "int",
                declaration.getBody().orElseThrow().toString(),
                declaration);
        Analyze.AnalysisSummary analysisSummary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                new MethodAnalysisResult(new MethodMetadata("countEvents", "public int countEvents()", "int"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext("AuditTrailService", "service", true, false),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of("AuditTrailService", List.of(
                        new ConstructorMetadata("AuditTrailService()", List.of()),
                        new ConstructorMetadata("AuditTrailService(Clock clock)",
                                List.of(new ParameterMetadata("clock", "Clock", List.of()))))),
                Map.of("AuditTrailService", List.of(
                        "void recordEvent(String event)",
                        "java.util.List<AuditRecord> events()",
                        "int countEvents()",
                        "void clear()")),
                Set.of(),
                Set.of()
        );

        GeneratedTestSnippet fastPath = builder.buildFastPath(classInfo,
                methodInfo,
                analysisSummary,
                new GeneratedTestSnippet("AuditTrailServiceTest", "shouldInvokeCountEvents", "", List.of()));

        assertNotNull(fastPath);
        assertTrue(fastPath.methodBody().contains("AuditTrailService"));
        assertTrue(fastPath.methodBody().contains(" service = new "));
        assertTrue(fastPath.methodBody().contains("assertEquals(0, service.countEvents())"));
        assertTrue(fastPath.methodBody().contains("service.recordEvent(\"coverage-message\");"));
        assertTrue(fastPath.methodBody().contains("assertEquals(1, service.countEvents())"));
        assertTrue(fastPath.methodBody().contains("service.clear();"));
    }

    @Test
    void buildFastPathCreatesStaticMutableCollectionEmitterTest() {
        TestClassInfo classInfo = legacyTelemetryClassInfo();
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration(
                "public static void emit(String stream, String message) { EVENTS.add(stream + \"::\" + message); }");
        TestMethodInfo methodInfo = new TestMethodInfo("public static void emit(String stream, String message)",
                "void",
                declaration.getBody().orElseThrow().toString(),
                declaration);

        GeneratedTestSnippet fastPath = builder.buildFastPath(classInfo,
                methodInfo,
                legacyTelemetryAnalysisSummary("emit", "public static void emit(String stream, String message)", "void"),
                new GeneratedTestSnippet("LegacyTelemetryTest", "shouldInvokeEmit", "", List.of()));

        assertNotNull(fastPath);
        assertTrue(fastPath.methodBody().contains("LegacyTelemetry.clear();"));
        assertTrue(fastPath.methodBody().contains("try {"));
        assertTrue(fastPath.methodBody().contains("LegacyTelemetry.emit(\"coverage-stream\", \"coverage-message\");"));
        assertTrue(fastPath.methodBody().contains("var result = "));
        assertTrue(fastPath.methodBody().contains("LegacyTelemetry.snapshot();"));
        assertTrue(fastPath.methodBody().contains("assertEquals(1, result.size())"));
        assertTrue(fastPath.methodBody().contains("assertEquals(\"coverage-stream::coverage-message\", result.get(0))"));
        assertTrue(fastPath.methodBody().contains("finally"));
    }

    @Test
    void buildFastPathCreatesStaticMutableCollectionSnapshotTest() {
        TestClassInfo classInfo = legacyTelemetryClassInfo();
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration(
                "public static java.util.List<String> snapshot() { return java.util.Collections.unmodifiableList(EVENTS); }");
        TestMethodInfo methodInfo = new TestMethodInfo("public static java.util.List<String> snapshot()",
                "java.util.List<String>",
                declaration.getBody().orElseThrow().toString(),
                declaration);

        GeneratedTestSnippet fastPath = builder.buildFastPath(classInfo,
                methodInfo,
                legacyTelemetryAnalysisSummary("snapshot", "public static java.util.List<String> snapshot()", "java.util.List<String>"),
                new GeneratedTestSnippet("LegacyTelemetryTest", "shouldInvokeSnapshot", "", List.of()));

        assertNotNull(fastPath);
        assertTrue(fastPath.methodBody().contains("LegacyTelemetry.clear();"));
        assertTrue(fastPath.methodBody().contains("LegacyTelemetry.emit(\"coverage-stream\", \"coverage-message\");"));
        assertTrue(fastPath.methodBody().contains("var result = "));
        assertTrue(fastPath.methodBody().contains("LegacyTelemetry.snapshot();"));
        assertTrue(fastPath.methodBody().contains("assertEquals(1, result.size())"));
        assertTrue(fastPath.methodBody().contains("assertThrows(UnsupportedOperationException.class, result::clear)"));
        assertTrue(fastPath.methodBody().contains("finally"));
    }

    @Test
    void buildFastPathCreatesStaticMutableCollectionClearTest() {
        TestClassInfo classInfo = legacyTelemetryClassInfo();
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration(
                "public static void clear() { EVENTS.clear(); }");
        TestMethodInfo methodInfo = new TestMethodInfo("public static void clear()",
                "void",
                declaration.getBody().orElseThrow().toString(),
                declaration);

        GeneratedTestSnippet fastPath = builder.buildFastPath(classInfo,
                methodInfo,
                legacyTelemetryAnalysisSummary("clear", "public static void clear()", "void"),
                new GeneratedTestSnippet("LegacyTelemetryTest", "shouldInvokeClear", "", List.of()));

        assertNotNull(fastPath);
        assertTrue(fastPath.methodBody().contains("LegacyTelemetry.clear();"));
        assertTrue(fastPath.methodBody().contains("LegacyTelemetry.emit(\"coverage-stream\", \"coverage-message\");"));
        assertTrue(fastPath.methodBody().contains("Assertions.assertFalse("));
        assertTrue(fastPath.methodBody().contains("LegacyTelemetry.snapshot().isEmpty()"));
        assertTrue(fastPath.methodBody().contains("Assertions.assertTrue("));
        assertTrue(fastPath.methodBody().contains("finally"));
    }

    @Test
    void buildFastPathUsesClassSiblingMethodsWhenAvailableMethodsOmitStaticApi() {
        MethodDeclaration emit = StaticJavaParser.parseMethodDeclaration(
                "public static void emit(String stream, String message) { EVENTS.add(stream + \"::\" + message); }");
        MethodDeclaration snapshot = StaticJavaParser.parseMethodDeclaration(
                "public static java.util.List<String> snapshot() { return java.util.Collections.unmodifiableList(EVENTS); }");
        MethodDeclaration clear = StaticJavaParser.parseMethodDeclaration(
                "public static void clear() { EVENTS.clear(); }");
        TestClassInfo classInfo = new TestClassInfo(
                "LegacyTelemetry",
                "LegacyTelemetryTest",
                Path.of("/tmp/src/test/java/com/example/app/legacy/LegacyTelemetryTest.java"),
                List.of(),
                List.of(
                        new TestMethodInfo("public static void emit(String stream, String message)",
                                "void",
                                emit.getBody().orElseThrow().toString(),
                                emit),
                        new TestMethodInfo("public static java.util.List<String> snapshot()",
                                "java.util.List<String>",
                                snapshot.getBody().orElseThrow().toString(),
                                snapshot),
                        new TestMethodInfo("public static void clear()",
                                "void",
                                clear.getBody().orElseThrow().toString(),
                                clear)
                )
        );
        Analyze.AnalysisSummary analysisSummary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                new MethodAnalysisResult(new MethodMetadata("snapshot", "public static java.util.List<String> snapshot()", "java.util.List<String>"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext("LegacyTelemetry", "legacyTelemetry", false, true),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Set.of()
        );

        GeneratedTestSnippet fastPath = builder.buildFastPath(classInfo,
                new TestMethodInfo("public static java.util.List<String> snapshot()",
                        "java.util.List<String>",
                        snapshot.getBody().orElseThrow().toString(),
                        snapshot),
                analysisSummary,
                new GeneratedTestSnippet("LegacyTelemetryTest", "shouldInvokeSnapshot", "", List.of()));

        assertNotNull(fastPath);
        assertTrue(fastPath.methodBody().contains("LegacyTelemetry.clear();"));
        assertTrue(fastPath.methodBody().contains("LegacyTelemetry.emit(\"coverage-stream\", \"coverage-message\");"));
        assertTrue(fastPath.methodBody().contains("LegacyTelemetry.snapshot();"));
    }

    @Test
    void buildFastPathCreatesDelegatingAssertionForApplicationActiveUsers() {
        TestClassInfo classInfo = new TestClassInfo(
                "Application",
                "ApplicationTest",
                Path.of("/tmp/src/test/java/com/example/app/ApplicationTest.java"),
                List.of(
                        "import com.example.app.service.UserService;",
                        "import com.example.app.service.AuditTrailService;",
                        "import com.example.app.service.FeatureToggleService;",
                        "import com.example.lib.LibraryComponent;"
                ),
                List.of()
        );
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration(
                "public java.util.List<String> activeUsers() { return userService.activeUsernames(); }");
        TestMethodInfo methodInfo = new TestMethodInfo(
                "public java.util.List<String> activeUsers()",
                "java.util.List<String>",
                "{ return userService.activeUsernames(); }",
                declaration
        );
        Analyze.AnalysisSummary analysisSummary = new Analyze.AnalysisSummary(
                new MockPlan(
                        List.of(new MockTarget("UserService", "userService")),
                        MockStrategy.MOCKITO,
                        List.of("userService"),
                        List.of()
                ),
                new MethodAnalysisResult(new MethodMetadata("activeUsers", "public java.util.List<String> activeUsers()", "java.util.List<String>"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext("Application", "application", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(
                        "Application", List.of(new ConstructorMetadata(
                                "Application(UserService userService, AuditTrailService auditTrailService, LibraryComponent libraryComponent, FeatureToggleService featureToggleService)",
                                List.of(
                                        new ParameterMetadata("userService", "UserService", List.of()),
                                        new ParameterMetadata("auditTrailService", "AuditTrailService", List.of()),
                                        new ParameterMetadata("libraryComponent", "LibraryComponent", List.of()),
                                        new ParameterMetadata("featureToggleService", "FeatureToggleService", List.of())
                                ))),
                        "AuditTrailService", List.of(new ConstructorMetadata("AuditTrailService()", List.of())),
                        "LibraryComponent", List.of(new ConstructorMetadata("LibraryComponent()", List.of())),
                        "FeatureToggleService", List.of(new ConstructorMetadata("FeatureToggleService()", List.of()))
                ),
                Map.of(),
                Set.of(),
                Set.of()
        );

        GeneratedTestSnippet fastPath = builder.buildFastPath(classInfo,
                methodInfo,
                analysisSummary,
                new GeneratedTestSnippet("ApplicationTest", "shouldPublicListStringActiveUsers", "", List.of()));

        assertNotNull(fastPath);
        assertTrue(fastPath.methodBody().contains("var expected = java.util.List.of(\"alpha\", \"beta\");"));
        assertTrue(fastPath.methodBody().contains("org.mockito.Mockito.when(userService.activeUsernames()).thenReturn(expected);"));
        assertTrue(fastPath.methodBody().contains("org.junit.jupiter.api.Assertions.assertEquals(expected, application.activeUsers());"));
    }

    @Test
    void buildFastPathCreatesBooleanBranchTestForDeployHiddenFeature() {
        TestClassInfo classInfo = new TestClassInfo(
                "Application",
                "ApplicationTest",
                Path.of("/tmp/src/test/java/com/example/app/ApplicationTest.java"),
                List.of(
                        "import com.example.app.service.UserService;",
                        "import com.example.app.service.AuditTrailService;",
                        "import com.example.app.service.FeatureToggleService;",
                        "import com.example.lib.LibraryComponent;"
                ),
                List.of()
        );
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration("""
                public void deployHiddenFeature() {
                    HiddenFeature hiddenFeature = new HiddenFeature(featureToggleService, auditTrailService);
                    if (hiddenFeature.activate()) {
                        hiddenFeature.recalibrate();
                    }
                }
                """);
        TestMethodInfo methodInfo = new TestMethodInfo(
                "public void deployHiddenFeature()",
                "void",
                declaration.getBody().orElseThrow().toString(),
                declaration
        );
        Analyze.AnalysisSummary analysisSummary = new Analyze.AnalysisSummary(
                new MockPlan(
                        List.of(
                                new MockTarget("FeatureToggleService", "featureToggleService"),
                                new MockTarget("AuditTrailService", "auditTrailService")
                        ),
                        MockStrategy.MOCKITO,
                        List.of("featureToggleService", "auditTrailService"),
                        List.of("hiddenFeature")
                ),
                new MethodAnalysisResult(new MethodMetadata("deployHiddenFeature", "public void deployHiddenFeature()", "void"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext("Application", "application", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(
                        "Application", List.of(new ConstructorMetadata(
                                "Application(UserService userService, AuditTrailService auditTrailService, LibraryComponent libraryComponent, FeatureToggleService featureToggleService)",
                                List.of(
                                        new ParameterMetadata("userService", "UserService", List.of()),
                                        new ParameterMetadata("auditTrailService", "AuditTrailService", List.of()),
                                        new ParameterMetadata("libraryComponent", "LibraryComponent", List.of()),
                                        new ParameterMetadata("featureToggleService", "FeatureToggleService", List.of())
                                ))),
                        "UserService", List.of(new ConstructorMetadata("UserService()", List.of())),
                        "LibraryComponent", List.of(new ConstructorMetadata("LibraryComponent()", List.of())),
                        "FeatureToggleService", List.of(new ConstructorMetadata("FeatureToggleService()", List.of())),
                        "AuditTrailService", List.of(new ConstructorMetadata("AuditTrailService()", List.of()))
                ),
                Map.of("FeatureToggleService", List.of("boolean isEnabled(String featureName)")),
                Set.of(),
                Set.of()
        );

        GeneratedTestSnippet fastPath = builder.buildFastPath(classInfo,
                methodInfo,
                analysisSummary,
                new GeneratedTestSnippet("ApplicationTest", "shouldPublicVoidDeployHiddenFeature", "", List.of()));

        assertNotNull(fastPath);
        assertTrue(fastPath.methodBody().contains(
                "org.mockito.Mockito.when(featureToggleService.isEnabled(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);"));
        assertTrue(fastPath.methodBody().contains("application.deployHiddenFeature();"));
    }

    private TestClassInfo legacyTelemetryClassInfo() {
        return new TestClassInfo(
                "LegacyTelemetry",
                "LegacyTelemetryTest",
                Path.of("/tmp/src/test/java/com/example/app/legacy/LegacyTelemetryTest.java"),
                List.of(),
                List.of()
        );
    }

    private Analyze.AnalysisSummary legacyTelemetryAnalysisSummary(String methodName,
                                                                   String methodSignature,
                                                                   String returnType) {
        return new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                new MethodAnalysisResult(new MethodMetadata(methodName, methodSignature, returnType),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext("LegacyTelemetry", "legacyTelemetry", false, true),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of("LegacyTelemetry", List.of(
                        "void emit(String stream, String message)",
                        "java.util.List<String> snapshot()",
                        "void clear()")),
                Set.of(),
                Set.of()
        );
    }
}
