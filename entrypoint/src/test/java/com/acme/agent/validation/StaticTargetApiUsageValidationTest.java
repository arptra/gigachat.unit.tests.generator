package com.acme.agent.validation;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
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
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
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

class StaticTargetApiUsageValidationTest {

    @TempDir
    Path tempDir;

    private GeneratedSnippetValidator validator;
    private AgentConfig config;
    private TestClassInfo classInfo;
    private TestMethodInfo methodInfo;
    private Analyze.AnalysisSummary analysisSummary;

    @BeforeEach
    void setUp() {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerConstructor("EmailSender", new ConstructorMetadata("EmailSender()", List.of()));
        registry.registerMethod("EmailSender", "void send(String address, String subject, String body)");
        PipelineLogger logger = new PipelineLogger(tempDir);
        Analyze analyze = new Analyze(registry);
        validator = new GeneratedSnippetValidator(logger, analyze, registry);
        config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(tempDir)
                .targetClass("com.example.app.service.EmailSender")
                .build();
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration(
                "public static EmailSender systemSender() { return new EmailSender(); }");
        methodInfo = new TestMethodInfo("public static EmailSender systemSender()",
                "EmailSender",
                "{ return new EmailSender(); }",
                declaration);
        classInfo = new TestClassInfo(
                "EmailSender",
                "EmailSenderTest",
                tempDir.resolve("src/test/java/com/example/app/service/EmailSenderTest.java"),
                List.of(),
                List.of(methodInfo));
        analysisSummary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                new MethodAnalysisResult(new MethodMetadata("systemSender",
                        "public static EmailSender systemSender()",
                        "EmailSender"),
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
                Map.of("EmailSender", List.of("void send(String address, String subject, String body)")),
                Set.of(),
                Set.of());
    }

    @Test
    void exactCurrentStaticTargetCallDoesNotTriggerInventedMethodValidation() {
        GeneratedTestSnippet snippet = snippetFor("""
                @Test
                void shouldCreateSystemSender() {
                    EmailSender result = EmailSender.systemSender();
                    org.junit.jupiter.api.Assertions.assertNotNull(result);
                }
                """);

        assertDoesNotThrow(() -> validator.validateGeneratedSnippet(
                config,
                classInfo,
                snippet,
                methodInfo,
                analysisSummary,
                null));
    }

    @Test
    void otherUnlistedStaticCallStillTriggersInventedMethodValidation() {
        GeneratedTestSnippet snippet = snippetFor("""
                @Test
                void shouldCreateSystemSender() {
                    EmailSender result = EmailSender.missingFactory();
                    org.junit.jupiter.api.Assertions.assertNotNull(result);
                }
                """);

        assertThrows(InvalidLLMResponseException.class, () -> validator.validateGeneratedSnippet(
                config,
                classInfo,
                snippet,
                methodInfo,
                analysisSummary,
                null));
    }

    private GeneratedTestSnippet snippetFor(String methodBody) {
        return new GeneratedTestSnippet(
                "EmailSenderTest",
                "shouldCreateSystemSender",
                methodBody,
                List.of("org.junit.jupiter.api.Test"),
                List.of(),
                List.of(),
                List.of(),
                "");
    }
}
