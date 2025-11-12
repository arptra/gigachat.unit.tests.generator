package com.acme.agent.validation;

import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.config.AgentMode;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationIntegrationTest {

    private Path projectRoot;
    private MethodSignatureRegistry registry;
    private JavaProjectScanner scanner;
    private Analyze analyze;
    private PromptBuilder promptBuilder;
    private SkeletonPromptBuilder skeletonPromptBuilder;

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
    void userRepositoryAnalysisShouldExposeInternalFieldsWithoutLeakingThem() throws Exception {
        AgentConfig config = baseConfig("com.example.app.repository.UserRepository");
        TestContext context = resolveContext(config, "UserRepository", "save(");

        Analyze.AnalysisSummary summary = analyze.analyze(config, context.classInfo(), context.methodInfo());

        assertTrue(summary.internalFields().contains("users"), "Expected internal field metadata for repository users");
        assertTrue(summary.accessibleFields().isEmpty(), "UserRepository should not expose public fields");
        assertFalse(summary.jsonContext().contains("repository.users"), "Internal field references must be sanitised");
    }

    @Test
    void userServiceShouldSelectMockitoStrategyAndPopulatePromptMetadata() throws Exception {
        AgentConfig config = baseConfig("com.example.app.service.UserService");
        TestContext context = resolveContext(config, "UserService", "createUser(");

        Analyze.AnalysisSummary summary = analyze.analyze(config, context.classInfo(), context.methodInfo());
        assertEquals(MockStrategy.MOCKITO, summary.mockPlan().strategy(), "External collaborators require Mockito");

        String skeleton = skeletonPromptBuilder.build(context.classInfo(), context.methodInfo());
        String promptJson = promptBuilder.build(config, context.classInfo(), context.methodInfo(), skeleton, summary);

        assertTrue(promptJson.contains("\"availableConstructors\""));
        assertTrue(promptJson.contains("\"availableMethods\""));
        assertTrue(promptJson.contains("\"accessibleFields\""));
        assertTrue(promptJson.contains("\"constructorPolicy\""));
        assertFalse(promptJson.contains("\"internalFields\""));
        assertTrue(promptJson.contains("\"mustUseAvailableConstructors\""));
    }

    @Test
    void mathUtilShouldRemainWithoutMocks() throws Exception {
        AgentConfig config = baseConfig("com.example.app.util.MathUtil");
        TestContext context = resolveContext(config, "MathUtil", "sum(");

        Analyze.AnalysisSummary summary = analyze.analyze(config, context.classInfo(), context.methodInfo());
        assertEquals(MockStrategy.NONE, summary.mockPlan().strategy(), "Pure utility methods must not use Mockito");

        String skeleton = skeletonPromptBuilder.build(context.classInfo(), context.methodInfo());
        String promptJson = promptBuilder.build(config, context.classInfo(), context.methodInfo(), skeleton, summary);

        assertTrue(promptJson.contains("\"constructorPolicy\""));
        assertFalse(promptJson.contains("mockFramework"));
        assertFalse(promptJson.contains("\"internalFields\""));
    }

    private AgentConfig baseConfig(String targetClass) {
        return new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(projectRoot)
                .targetClass(targetClass)
                .scanWholeProject(true)
                .build();
    }

    private TestContext resolveContext(AgentConfig config, String simpleClassName, String methodSignatureFragment) throws Exception {
        List<TestClassInfo> classes = scanner.scan(config);
        TestClassInfo classInfo = classes.stream()
                .filter(info -> simpleClassName.equals(info.getClassName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Class not found: " + simpleClassName));
        TestMethodInfo methodInfo = classInfo.getMethods().stream()
                .filter(method -> method.getSignature().contains(methodSignatureFragment))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Method fragment not found: " + methodSignatureFragment));
        assertNotNull(methodInfo.getDeclaration(), "Method declaration should be available for analysis");
        return new TestContext(classInfo, methodInfo);
    }

    private record TestContext(TestClassInfo classInfo, TestMethodInfo methodInfo) {
    }
}
