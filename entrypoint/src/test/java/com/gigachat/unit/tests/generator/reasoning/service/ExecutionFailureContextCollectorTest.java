package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.cleaner.parser.ExecutionFailureParseResult;
import com.gigachat.unit.tests.generator.cleaner.parser.TestFailure;
import com.gigachat.unit.tests.generator.dto.ClassMetadata;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.report.parser.TestReportFailure;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionFailureContextCollectorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCollectMockDiagnosticsAndRelatedProjectClasses() throws Exception {
        Path mainRoot = tempDir.resolve("src/main/java/com/example/app/service");
        Path testRoot = tempDir.resolve("src/test/java/com/example/app/service");
        Files.createDirectories(mainRoot);
        Files.createDirectories(testRoot);

        Path userService = mainRoot.resolve("UserService.java");
        Files.writeString(userService, """
                package com.example.app.service;

                public class UserService {
                    private final NotificationService notificationService;

                    public UserService(NotificationService notificationService) {
                        this.notificationService = notificationService;
                    }

                    public void register() {
                        notificationService.send("hello");
                    }
                }
                """);
        Path notificationService = mainRoot.resolve("NotificationService.java");
        Files.writeString(notificationService, """
                package com.example.app.service;

                public class NotificationService {
                    public void send(String message) {
                    }
                }
                """);
        Path testFile = testRoot.resolve("UserServiceTest.java");
        Files.writeString(testFile, """
                package com.example.app.service;

                class UserServiceTest {
                }
                """);

        TestClassInfo classInfo = new TestClassInfo(
                "com.example.app.service.UserService",
                "com.example.app.service.UserServiceTest",
                testFile,
                List.of(),
                List.of(),
                new ClassMetadata("com.example.app.service.UserService", List.of())
        );
        TestMethodInfo methodInfo = new TestMethodInfo("register()", "void", "");
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("NotificationService"), List.of("User")),
                new MethodAnalysisResult(new MethodMetadata("register", "register()", "void"), List.of(), List.of(), List.of(), List.of()),
                "{}",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.service.UserService", "userService", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Set.of()
        );
        ExecuteResult executeResult = new ExecuteResult(
                false,
                List.of("com.example.app.service.UserServiceTest.register"),
                "",
                "org.mockito.exceptions.misusing.NotAMockException: Argument passed to when() is not a mock!"
        );
        ExecutionFailureParseResult parseResult = new ExecutionFailureParseResult(
                List.of(new TestFailure("com.example.app.service.UserServiceTest", "register")),
                Optional.empty()
        );
        List<TestReportFailure> reportFailures = List.of(new TestReportFailure(
                "com.example.app.service.UserServiceTest",
                "register",
                "org.mockito.exceptions.misusing.NotAMockException: Argument passed to when() is not a mock!",
                List.of(
                        "org.mockito.exceptions.misusing.NotAMockException: Argument passed to when() is not a mock!",
                        "at com.example.app.service.UserService.register(UserService.java:10)",
                        "at com.example.app.service.NotificationService.send(NotificationService.java:4)"
                )
        ));

        ExecutionFailureContextCollector collector = new ExecutionFailureContextCollector();
        Map<String, Object> information = collector.collect(tempDir, classInfo, methodInfo, summary, executeResult, parseResult, reportFailures)
                .getInformation();

        assertEquals("execute", information.get("failureStage"));
        assertTrue(information.containsKey("executionFailures"));
        assertTrue(information.containsKey("mockContext"));
        assertTrue(information.containsKey("relatedClassSources"));

        @SuppressWarnings("unchecked")
        Map<String, Object> mockContext = (Map<String, Object>) information.get("mockContext");
        assertEquals("MOCKITO", mockContext.get("strategy"));
        assertTrue(mockContext.toString().contains("NotificationService"));
        assertTrue(mockContext.toString().contains("real object is being stubbed"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relatedSources = (List<Map<String, Object>>) information.get("relatedClassSources");
        assertTrue(relatedSources.stream().anyMatch(item -> item.get("className").toString().contains("UserService")));
        assertTrue(relatedSources.stream().anyMatch(item -> item.get("className").toString().contains("NotificationService")));
    }

    @Test
    void shouldAddIdentityMismatchHintForRealDomainObjectAssertions() throws Exception {
        Path mainRoot = tempDir.resolve("src/main/java/com/example/app/model");
        Path testRoot = tempDir.resolve("src/test/java/com/example/app/service");
        Files.createDirectories(mainRoot);
        Files.createDirectories(testRoot);
        Files.writeString(mainRoot.resolve("User.java"), "package com.example.app.model; public class User { String getUsername(){ return \"x\"; } }");
        Path testFile = testRoot.resolve("UserServiceTest.java");
        Files.writeString(testFile, "package com.example.app.service; class UserServiceTest {}");

        ExecutionFailureContextCollector collector = new ExecutionFailureContextCollector();
        Map<String, Object> information = collector.collect(
                tempDir,
                new TestClassInfo(
                        "com.example.app.service.UserService",
                        "com.example.app.service.UserServiceTest",
                        testFile,
                        List.of(),
                        List.of(),
                        new ClassMetadata("com.example.app.service.UserService", List.of())),
                new TestMethodInfo("createUser()", "User", ""),
                new Analyze.AnalysisSummary(
                        new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("repository"), List.of("User")),
                        new MethodAnalysisResult(new MethodMetadata("createUser", "createUser()", "User"), List.of(), List.of(), List.of(), List.of()),
                        "{}",
                        Map.of(),
                        new Analyze.TestTargetContext("com.example.app.service.UserService", "service", true, false),
                        true,
                        List.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(),
                        Map.of(),
                        Set.of(),
                        Set.of()),
                new ExecuteResult(false,
                        List.of("com.example.app.service.UserServiceTest.createUser"),
                        "",
                        "AssertionFailedError: expected: <com.example.app.model.User@abc123> but was: <com.example.app.model.User@def456>"),
                new ExecutionFailureParseResult(List.of(), Optional.empty()),
                List.of(new TestReportFailure(
                        "com.example.app.service.UserServiceTest",
                        "createUser",
                        "AssertionFailedError: expected: <com.example.app.model.User@abc123> but was: <com.example.app.model.User@def456>",
                        List.of("AssertionFailedError: expected: <com.example.app.model.User@abc123> but was: <com.example.app.model.User@def456>"))))
                .getInformation();

        @SuppressWarnings("unchecked")
        Map<String, Object> mockContext = (Map<String, Object>) information.get("mockContext");
        assertTrue(mockContext.toString().contains("object identity mismatch"));
    }
}
