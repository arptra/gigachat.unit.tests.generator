package com.gigachat.unit.tests.generator.pipeline;

import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.config.AgentMode;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PromptBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.SkeletonPromptBuilder;
import com.gigachat.unit.tests.generator.scanner.JavaProjectScanner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptConstructorMetadataTest {
    private Path projectRoot;
    private JavaProjectScanner scanner;
    private Analyze analyze;
    private PromptBuilder promptBuilder;
    private SkeletonPromptBuilder skeletonPromptBuilder;
    private MethodSignatureRegistry registry;

    @BeforeEach
    void setUp() {
        projectRoot = Path.of("..", "example-project").toAbsolutePath().normalize();
        registry = new MethodSignatureRegistry();
        scanner = new JavaProjectScanner(registry);
        analyze = new Analyze(registry);
        promptBuilder = new PromptBuilder();
        skeletonPromptBuilder = new SkeletonPromptBuilder();
    }

    @Test
    void shouldIncludeDetailedConstructorMetadataInJson() throws Exception {
        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(projectRoot)
                .targetClass("com.example.app.service.UserService")
                .scanWholeProject(true)
                .build();

        List<TestClassInfo> classes = scanner.scan(config);
        TestClassInfo classInfo = classes.stream()
                .filter(info -> "UserService".equals(info.getClassName()))
                .findFirst()
                .orElseThrow();
        TestMethodInfo methodInfo = classInfo.getMethods().stream()
                .filter(method -> method.getSignature().contains("createUser"))
                .findFirst()
                .orElseThrow();

        Analyze.AnalysisSummary summary = analyze.analyze(config, classInfo, methodInfo);
        String skeleton = skeletonPromptBuilder.build(classInfo, methodInfo);
        String promptJson = promptBuilder.build(config, classInfo, methodInfo, skeleton, summary);

        assertTrue(promptJson.contains("\"availableConstructors\""));
        assertTrue(promptJson.contains("\"parameters\""));
        assertTrue(promptJson.contains("\"username\""));
        assertTrue(promptJson.contains("\"email\""));
        assertTrue(promptJson.contains("\"availableMethods\""));
        assertTrue(promptJson.contains("\"UserRepository\""));
        assertTrue(promptJson.contains("\"User\""));
        assertTrue(promptJson.contains("\"constructorPolicy\""));
        assertFalse(promptJson.contains("\"internalFields\""));
    }

    @Test
    void shouldExposeConstructorsForMethodSignatureTypes() throws Exception {
        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(projectRoot)
                .targetClass("com.example.app.repository.UserRepository")
                .scanWholeProject(true)
                .build();

        List<TestClassInfo> classes = scanner.scan(config);
        TestClassInfo classInfo = classes.stream()
                .filter(info -> "UserRepository".equals(info.getClassName()))
                .findFirst()
                .orElseThrow();

        TestMethodInfo saveMethod = classInfo.getMethods().stream()
                .filter(method -> method.getSignature().contains("save"))
                .findFirst()
                .orElseThrow();
        String savePrompt = buildPromptJson(config, classInfo, saveMethod);
        assertTrue(savePrompt.contains("\"availableConstructors\""));
        assertTrue(savePrompt.contains("\"UserRepository\""));
        assertTrue(savePrompt.contains("UserRepository()"));
        assertTrue(savePrompt.contains("User(String username, String email)"));

        TestMethodInfo findByUsername = classInfo.getMethods().stream()
                .filter(method -> method.getSignature().contains("findByUsername"))
                .findFirst()
                .orElseThrow();
        String optionalPrompt = buildPromptJson(config, classInfo, findByUsername);
        assertTrue(optionalPrompt.contains("User(String username, String email)"));

        TestMethodInfo findAll = classInfo.getMethods().stream()
                .filter(method -> method.getSignature().contains("findAll"))
                .findFirst()
                .orElseThrow();
        String collectionPrompt = buildPromptJson(config, classInfo, findAll);
        assertTrue(collectionPrompt.contains("User(String username, String email)"));
    }

    private String buildPromptJson(AgentConfig config,
                                   TestClassInfo classInfo,
                                   TestMethodInfo methodInfo) {
        Analyze.AnalysisSummary summary = analyze.analyze(config, classInfo, methodInfo);
        String skeleton = skeletonPromptBuilder.build(classInfo, methodInfo);
        return promptBuilder.build(config, classInfo, methodInfo, skeleton, summary);
    }
}
