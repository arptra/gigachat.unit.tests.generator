package com.gigachat.unit.tests.generator.pipeline.helpers;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderSourceSnippetTest {

    @Test
    void shouldEmphasizeSourceSnippetAsAuthoritativeContext() {
        PromptBuilder builder = new PromptBuilder();
        JSONObject context = new JSONObject();
        context.put("goal", "Generate a JUnit 5 unit test");
        context.put("instructions", new JSONObject().put("namingConvention", "HiddenFeatureTest"));
        context.put("mockPlan", new JSONObject()
                .put("strategy", "MOCKITO")
                .put("shouldMock", List.of("featureToggleService"))
                .put("shouldNotMock", List.of("hiddenFeature")));
        context.put("forbiddenDirectMockTargets", List.of("hiddenFeature"));
        context.put("methodContext", new JSONObject()
                .put("packageName", "com.example.app.feature")
                .put("sourceSnippet", """
                        public boolean activate() {
                            if (!featureToggleService.isEnabled("hidden")) {
                                return false;
                            }
                            auditTrailService.recordEvent("Activated hidden feature");
                            return true;
                        }
                        """)
                .put("imports", "[]"));

        String prompt = builder.buildPromptForLLM(context);

        assertTrue(prompt.contains("methodContext.packageName"));
        assertTrue(prompt.contains("methodContext.sourceSnippet"));
        assertTrue(prompt.contains("Do not invent alternative event messages"));
        assertTrue(prompt.contains("no side effect"));
        assertTrue(prompt.contains("Never use Mockito.when(...) or Mockito.verify(...) on a real object"));
        assertTrue(prompt.contains("creates a new local object inside the method"));
        assertTrue(prompt.contains("must NEVER be mocked, spied, stubbed, or verified directly: hiddenFeature"));
    }
}
