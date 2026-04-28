package com.acme.agent.validation;

import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.config.AgentMode;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.InvalidLLMResponseException;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.pipeline.helpers.validation.GeneratedSnippetValidator;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectImportValidationTest {

    @TempDir
    Path tempDir;

    private GeneratedSnippetValidator validator;

    @BeforeEach
    void setUp() {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        PipelineLogger logger = new PipelineLogger(tempDir);
        Analyze analyze = new Analyze(registry);
        validator = new GeneratedSnippetValidator(logger, analyze, registry);
    }

    @Test
    void wrongInProjectImportPackageIsRejectedBeforeScratchCompile() throws Exception {
        Path sourceFile = tempDir.resolve("src/main/java/com/example/app/service/NotificationService.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package com.example.app.service;

                public class NotificationService {
                }
                """);

        assertThrows(InvalidLLMResponseException.class, () -> validator.ensureProjectImportsAreResolvable(
                tempDir,
                com.github.javaparser.StaticJavaParser.parse("""
                        package com.example.app.service;

                        import com.example.app.util.NotificationService;

                        class UserServiceTest {
                            private NotificationService notificationService;
                        }
                        """)));
    }

    @Test
    void wrongProjectImportIsRejectedWhenSnippetHasSeparateFieldsAndHelpers() throws Exception {
        Path sourceFile = tempDir.resolve("src/main/java/com/example/app/service/NotificationService.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package com.example.app.service;

                public class NotificationService {
                }
                """);
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "package com.example.app.service;\nclass UserServiceTest {}\n");

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(tempDir)
                .targetClass("com.example.app.service.UserService")
                .build();
        TestMethodInfo methodInfo = new TestMethodInfo("public void target()", "void", "");
        TestClassInfo classInfo = new TestClassInfo(
                "UserService",
                "UserServiceTest",
                testFile,
                List.of(),
                List.of(methodInfo));
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserServiceTest",
                "shouldTarget",
                """
                        @Test
                        void shouldTarget() {
                            service.target();
                        }
                        """,
                List.of(
                        "org.junit.jupiter.api.Test",
                        "com.example.app.util.NotificationService"
                ),
                List.of(),
                List.of(
                        "private UserService service;",
                        "private NotificationService notificationService;"
                ),
                List.of(),
                "");
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                new MethodAnalysisResult(
                        new MethodMetadata("target", "public void target()", "void"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.service.UserService", "service", true, false),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Set.of());

        assertThrows(InvalidLLMResponseException.class, () -> validator.validateGeneratedSnippet(
                config,
                classInfo,
                snippet,
                methodInfo,
                summary,
                null));
    }
}
