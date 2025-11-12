package com.gigachat.unit.tests.generator.pipeline;

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

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockStrategyIntegrationTest {
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
    void selectsMockitoStrategyForApplication() throws Exception {
        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(projectRoot)
                .targetClass("com.example.app.Application")
                .scanWholeProject(true)
                .build();

        List<TestClassInfo> classes = scanner.scan(config);
        assertEquals(1, classes.size());
        TestClassInfo classInfo = classes.get(0);
        TestMethodInfo methodInfo = classInfo.getMethods().get(0);

        Analyze.AnalysisSummary summary = analyze.analyze(config, classInfo, methodInfo);
        assertEquals(MockStrategy.MOCKITO, summary.mockPlan().strategy());

        String skeleton = skeletonPromptBuilder.build(classInfo, methodInfo);
        String promptJson = promptBuilder.build(config, classInfo, methodInfo, skeleton, summary);
        JSONObject context = new JSONObject(promptJson);
        assertEquals("MOCKITO", context.getJSONObject("mockPlan").getString("strategy"));
        String llmPrompt = promptBuilder.buildPromptForLLM(context, config.getPromptConfig());
        assertTrue(llmPrompt.contains("Use Mockito to mock external dependencies"));
    }

    @Test
    void keepsNoneStrategyForMathUtil() throws Exception {
        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(projectRoot)
                .targetClass("com.example.app.util.MathUtil")
                .scanWholeProject(true)
                .build();

        List<TestClassInfo> classes = scanner.scan(config);
        assertEquals(1, classes.size());
        TestClassInfo classInfo = classes.get(0);
        TestMethodInfo methodInfo = classInfo.getMethods().stream()
                .findFirst()
                .orElseThrow();

        Analyze.AnalysisSummary summary = analyze.analyze(config, classInfo, methodInfo);
        assertEquals(MockStrategy.NONE, summary.mockPlan().strategy());

        String skeleton = skeletonPromptBuilder.build(classInfo, methodInfo);
        String promptJson = promptBuilder.build(config, classInfo, methodInfo, skeleton, summary);
        JSONObject context = new JSONObject(promptJson);
        assertEquals("NONE", context.getJSONObject("mockPlan").getString("strategy"));
        JSONObject instructions = context.getJSONObject("instructions");
        assertFalse(instructions.has("verificationPolicy"));
        String llmPrompt = promptBuilder.buildPromptForLLM(context, config.getPromptConfig());
        assertTrue(llmPrompt.contains("Do not use Mockito. Use only JUnit 5 and real objects."));
        assertFalse(llmPrompt.contains("Mockito.verify"));
    }
}
