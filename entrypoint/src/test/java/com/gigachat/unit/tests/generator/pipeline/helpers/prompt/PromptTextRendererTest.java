package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import com.gigachat.unit.tests.generator.config.PromptConfig;
import com.gigachat.unit.tests.generator.resources.PromptSnippetCatalog;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptTextRendererTest {

    @Test
    void shouldRenderConstructorLocalPromptDirectivesWhenContextIsPresent() {
        JSONObject context = new JSONObject();
        context.put("goal", "Generate a test");
        context.put("instructions", new JSONObject().put("testFramework", "JUnit5"));
        context.put("methodContext", new JSONObject()
                .put("packageName", "com.example.app.service")
                .put("originalClassFqcn", "com.example.app.service.LegacyWorkflowService")
                .put("sourceSnippet", "public boolean synchronizeLegacyUpgrade(User user, int rawSignal) { return true; }"));
        context.put("constructorLocalContexts", new JSONArray().put(new JSONObject()
                .put("variable", "session")
                .put("className", "LegacyUpgradeSession")
                .put("constructorArgumentCollaborators", new JSONArray()
                        .put("featureToggleService")
                        .put("auditTrailService")
                        .put("notificationService"))
                .put("invokedMethods", new JSONArray().put(new JSONObject()
                        .put("name", "process")
                        .put("arity", 2)
                        .put("sourceSnippet", "public boolean process(User user, int rawSignal) { if (!featureToggleService.isEnabled(\"legacy-upgrade\")) { return false; } notificationService.sendWelcome(user); return true; }")
                        .put("branchDrivers", new JSONArray().put("featureToggleService.isEnabled(\"legacy-upgrade\")"))
                        .put("voidSideEffects", new JSONArray().put("notificationService.sendWelcome(user)"))
                        .put("publicStateMutators", new JSONArray().put("user.incrementAttempts()").put("user.deactivate()"))))));

        String prompt = new PromptTextRenderer(new PromptSnippetCatalog()).render(context, PromptConfig.defaults());

        assertTrue(prompt.contains("constructorLocalContexts"));
        assertTrue(prompt.contains("LegacyUpgradeSession.process"));
        assertTrue(prompt.contains("Branch drivers for"));
        assertTrue(prompt.contains("featureToggleService.isEnabled(\"legacy-upgrade\")"));
        assertTrue(prompt.contains("void collaborator side effects"));
        assertTrue(prompt.contains("Void side effects for"));
        assertTrue(prompt.contains("notificationService.sendWelcome(user)"));
        assertTrue(prompt.contains("Public state mutators for"));
        assertTrue(prompt.contains("user.incrementAttempts()"));
    }

    @Test
    void shouldRenderRequiredSutConstructorArgumentDirectives() {
        JSONObject context = new JSONObject();
        context.put("goal", "Generate a test");
        context.put("instructions", new JSONObject().put("testFramework", "JUnit5"));
        context.put("sutConstructionPolicy", new JSONObject()
                .put("requiresExplicitConstructorInjection", true)
                .put("mockedCollaborators", new JSONArray().put("repository"))
                .put("fallbackMockCandidates", new JSONArray().put("NotificationService"))
                .put("preferredConstructors", new JSONArray().put(new JSONObject()
                        .put("signature", "UserService(UserRepository repository, AuditTrailService auditTrailService, NotificationService notificationService)")
                        .put("mockBindings", new JSONArray().put(new JSONObject()
                                .put("position", 1)
                                .put("parameterName", "repository")
                                .put("parameterType", "UserRepository")))
                        .put("requiredConstructorArgs", new JSONArray()
                                .put(new JSONObject().put("position", 1).put("parameterName", "repository"))
                                .put(new JSONObject().put("position", 2).put("parameterName", "auditTrailService"))
                                .put(new JSONObject().put("position", 3).put("parameterName", "notificationService"))))));

        String prompt = new PromptTextRenderer(new PromptSnippetCatalog()).render(context, PromptConfig.defaults());

        assertTrue(prompt.contains("requiredConstructorArgs"));
        assertTrue(prompt.contains("must not be null literals"));
        assertTrue(prompt.contains("#2 auditTrailService"));
        assertTrue(prompt.contains("#3 notificationService"));
        assertTrue(prompt.contains("must be promoted to Mockito mocks"));
        assertTrue(prompt.contains("NotificationService"));
    }
}
