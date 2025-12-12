package com.gigachat.unit.tests.generator.prompt.deep;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze.TestTargetContext;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodMetadata;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticAnalysis;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticStaticCall;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticTypeConstructor;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticTypeMethod;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepPromptContextBuilderTest {

    @Test
    void shouldEmbedSemanticInformationIntoPromptContext() {
        SemanticTypeMethod method = new SemanticTypeMethod(false,
                "convert",
                List.of("java.lang.String"),
                "com.example.UserDto");
        SemanticTypeConstructor constructor = new SemanticTypeConstructor("com.example.UserDto",
                List.of("java.lang.String"),
                "UserDto(String value)");
        SemanticStaticCall staticCall = new SemanticStaticCall("com.example.Stats",
                "publish",
                List.of("java.lang.String"));
        SemanticAnalysis semanticAnalysis = new SemanticAnalysis(Map.of("com.example.UserDto", List.of(method)),
                Map.of("com.example.UserDto", List.of(constructor)),
                List.of(staticCall),
                List.of("com.example.UserDto"));
        MethodAnalysisResult methodAnalysis = new MethodAnalysisResult(new MethodMetadata("save", "save(UserDto dto)", "void"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                semanticAnalysis);
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                methodAnalysis,
                "{}",
                Map.of(),
                new TestTargetContext("com.example.UserService", "service", true, false),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Set.of());
        TestClassInfo classInfo = new TestClassInfo("com.example.UserService",
                "com.example.UserServiceTest",
                Path.of("UserServiceTest.java"),
                List.of(),
                List.of(),
                null);
        TestMethodInfo methodInfo = new TestMethodInfo("void save()", "void", "{}", null);
        AgentConfig config = new AgentConfigBuilder().projectPath(Path.of(".")).build();

        DeepPromptContextBuilder builder = new DeepPromptContextBuilder();
        String promptJson = builder.build(config, classInfo, methodInfo, "{}", summary);
        JSONObject context = new JSONObject(promptJson);

        JSONObject semanticBlock = context.getJSONObject("semanticAnalysis");
        assertEquals("com.example.UserDto", semanticBlock.getJSONArray("domainTypes").getString(0));
        JSONObject availableMethods = context.getJSONObject("availableMethods");
        assertTrue(availableMethods.getJSONArray("com.example.UserDto").toList()
                .contains("com.example.UserDto convert(java.lang.String)"));
        JSONObject constructorsBlock = context.getJSONObject("availableConstructors");
        assertTrue(constructorsBlock.has("com.example.UserDto"));
        JSONObject staticCalls = semanticBlock.getJSONArray("staticCalls").getJSONObject(0);
        assertEquals("com.example.Stats", staticCalls.getString("ownerType"));
    }
}
