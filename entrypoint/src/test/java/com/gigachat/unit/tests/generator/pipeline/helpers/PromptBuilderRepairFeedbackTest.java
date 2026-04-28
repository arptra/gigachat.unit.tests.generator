package com.gigachat.unit.tests.generator.pipeline.helpers;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderRepairFeedbackTest {

    @Test
    void shouldAddDeterministicRepairInstructionsWhenRepairFeedbackExists() {
        PromptBuilder builder = new PromptBuilder();
        JSONObject context = new JSONObject();
        context.put("goal", "Repair the generated test");
        context.put("instructions", new JSONObject().put("namingConvention", "SampleServiceTest"));
        context.put("repair", new JSONObject()
                .put("previousSnippet", "@Test void shouldWork() {}")
                .put("stageFeedback", new JSONArray()
                        .put(new JSONObject().put("stage", "compile"))
                        .put(new JSONObject().put("stage", "execute"))));

        String prompt = builder.buildPromptForLLM(context);

        assertTrue(prompt.contains("deterministic feedback from previous pipeline stages"));
        assertTrue(prompt.contains("\"repair.stageFeedback\""));
        assertTrue(prompt.contains("Preserve working parts of \"repair.previousSnippet\""));
        assertTrue(prompt.contains("Latest failing stages: compile, execute."));
    }

    @Test
    void shouldRenderStateModelAndSutConstructionPolicyWhenProvided() {
        PromptBuilder builder = new PromptBuilder();
        JSONObject context = new JSONObject();
        context.put("goal", "Generate a JUnit 5 unit test");
        context.put("instructions", new JSONObject().put("namingConvention", "ApplicationTest"));
        context.put("mockPlan", new JSONObject()
                .put("strategy", "MOCKITO")
                .put("shouldMock", new JSONArray().put("auditTrailService").put("featureToggleService")));
        context.put("sutConstructionPolicy", new JSONObject()
                .put("requiresExplicitConstructorInjection", true)
                .put("targetClass", "Application")
                .put("mockedCollaborators", new JSONArray().put("auditTrailService").put("featureToggleService"))
                .put("preferredConstructors", new JSONArray()
                        .put(new JSONObject().put("signature", "Application(UserService userService, AuditTrailService auditTrailService, LibraryComponent libraryComponent, FeatureToggleService featureToggleService)"))));
        context.put("stateModel", new JSONObject()
                .put("currentState", "S1_TESTS_GENERATED")
                .put("allowedActions", new JSONArray().put("REPLACE_SUT_CONSTRUCTION"))
                .put("forbiddenActions", new JSONArray().put("USE_NO_ARG_SUT_CONSTRUCTOR")));

        String prompt = builder.buildPromptForLLM(context);

        assertTrue(prompt.contains("sutConstructionPolicy"));
        assertTrue(prompt.contains("Do not fall back to the class-under-test no-arg constructor"));
        assertTrue(prompt.contains("bounded state/action model in \"stateModel\""));
        assertTrue(prompt.contains("Allowed actions in this state: REPLACE_SUT_CONSTRUCTION."));
        assertTrue(prompt.contains("Forbidden actions in this state: USE_NO_ARG_SUT_CONSTRUCTOR."));
    }
}
