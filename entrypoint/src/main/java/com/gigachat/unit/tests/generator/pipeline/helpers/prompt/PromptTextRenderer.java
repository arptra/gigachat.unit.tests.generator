package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import com.gigachat.unit.tests.generator.config.PromptConfig;
import com.gigachat.unit.tests.generator.resources.PromptSnippetCatalog;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Renders the final LLM-facing prompt text from the prepared JSON context.
 */
public class PromptTextRenderer {

    private final PromptSnippetCatalog promptSnippetCatalog;

    public PromptTextRenderer(PromptSnippetCatalog promptSnippetCatalog) {
        this.promptSnippetCatalog = Objects.requireNonNull(promptSnippetCatalog, "promptSnippetCatalog");
    }

    public String render(JSONObject contextJson, PromptConfig promptConfig) {
        if (contextJson == null || contextJson.isEmpty()) {
            return fallbackPrompt();
        }
        PromptConfig effectiveConfig = promptConfig == null ? PromptConfig.defaults() : promptConfig;
        if (!effectiveConfig.includeInstructionHeader()) {
            return contextJson.toString(2);
        }
        String header = effectiveConfig.resolvedInstructionTemplate();
        String responseDirective = responseDirective(effectiveConfig.resolvedResponseFormat());
        String lineSeparator = System.lineSeparator();
        StringBuilder builder = new StringBuilder();
        builder.append(header).append(lineSeparator).append(lineSeparator);
        JSONObject instructionsBlock = contextJson.optJSONObject("instructions");
        JSONObject mockPlanBlock = contextJson.optJSONObject("mockPlan");
        String strategy = mockPlanBlock == null ? "" : mockPlanBlock.optString("strategy", "");
        boolean mocksAllowed = !"NONE".equalsIgnoreCase(strategy);
        boolean hasVerificationPolicy = instructionsBlock != null && instructionsBlock.has("verificationPolicy");
        boolean repairMode = hasRepairFeedback(contextJson);
        JSONObject methodContext = contextJson.optJSONObject("methodContext");
        JSONArray forbiddenDirectMockTargets = contextJson.optJSONArray("forbiddenDirectMockTargets");
        JSONArray retryConstraints = contextJson.optJSONArray("retryConstraints");
        JSONObject retryValidation = contextJson.optJSONObject("retryValidation");
        JSONObject sutConstructionPolicy = contextJson.optJSONObject("sutConstructionPolicy");
        JSONObject stateModel = contextJson.optJSONObject("stateModel");
        JSONArray constructorLocalContexts = contextJson.optJSONArray("constructorLocalContexts");
        boolean hasSourceSnippet = methodContext != null && !methodContext.optString("sourceSnippet").isBlank();
        boolean hasPackageName = methodContext != null && !methodContext.optString("packageName").isBlank();
        boolean hasOriginalClassFqcn = methodContext != null && !methodContext.optString("originalClassFqcn").isBlank();

        builder.append("Your task:").append(lineSeparator);
        appendRuleLines(builder, promptSnippetCatalog.commonGenerationRules(), lineSeparator);
        if (mocksAllowed) {
            appendRuleLines(builder, promptSnippetCatalog.mockitoGenerationRules(), lineSeparator);
            if (forbiddenDirectMockTargets != null && !forbiddenDirectMockTargets.isEmpty()) {
                builder.append("- The following constructor-created local objects are forbidden Mockito targets and must NEVER be mocked, spied, stubbed, or verified directly: ")
                        .append(joinJsonArray(forbiddenDirectMockTargets))
                        .append('.')
                        .append(lineSeparator);
            }
            if (hasVerificationPolicy) {
                builder.append("- Add verification calls from \"verificationPolicy\" using Mockito.verify().").append(lineSeparator);
            }
        } else {
            appendRuleLines(builder, promptSnippetCatalog.realObjectGenerationRules(), lineSeparator);
        }
        if (hasPackageName) {
            builder.append("- Use the package from \"methodContext.packageName\" as the authoritative package for the generated test and related source references.").append(lineSeparator);
        }
        if (hasOriginalClassFqcn) {
            builder.append("- Use \"methodContext.originalClassFqcn\" as the authoritative fully qualified name of the class under test; do not invent another package for it.").append(lineSeparator);
        }
        if (hasSourceSnippet) {
            appendRuleLines(builder, promptSnippetCatalog.sourceSnippetRules(), lineSeparator);
        }
        if (constructorLocalContexts != null && !constructorLocalContexts.isEmpty()) {
            appendConstructorLocalDirectives(builder, constructorLocalContexts, lineSeparator);
        }
        if (retryConstraints != null && !retryConstraints.isEmpty()) {
            for (int index = 0; index < retryConstraints.length(); index++) {
                String constraint = retryConstraints.optString(index, "").trim();
                if (!constraint.isBlank()) {
                    builder.append("- ").append(constraint).append(lineSeparator);
                }
            }
        }
        if (retryValidation != null && !retryValidation.isEmpty()) {
            String invalidReason = retryValidation.optString("reason").trim();
            if (!invalidReason.isBlank()) {
                builder.append("- Previous generation was rejected for this exact reason: ").append(invalidReason).append('.').append(lineSeparator);
            }
            String invalidSnippet = retryValidation.optString("invalidSnippetExcerpt").trim();
            if (!invalidSnippet.isBlank()) {
                builder.append("- Do not repeat patterns from this rejected snippet excerpt: ").append(invalidSnippet).append(lineSeparator);
            }
        }
        if (sutConstructionPolicy != null && !sutConstructionPolicy.isEmpty()) {
            appendSutConstructionDirectives(builder, sutConstructionPolicy, lineSeparator);
        }
        if (stateModel != null && !stateModel.isEmpty()) {
            appendStateModelDirectives(builder, stateModel, lineSeparator);
        }
        if (repairMode) {
            appendRepairDirectives(builder, contextJson, lineSeparator);
        }
        builder.append("- ").append(responseDirective).append(lineSeparator).append(lineSeparator);
        builder.append("JSON CONTEXT:").append(lineSeparator);
        builder.append(contextJson.toString(2));
        return builder.toString();
    }

    private String responseDirective(String responseFormat) {
        if (responseFormat == null || responseFormat.isBlank()) {
            return "Return only valid Java code of the test class. Do not include explanations, markdown, or JSON.";
        }
        String normalised = responseFormat.trim().toUpperCase(Locale.ROOT);
        return switch (normalised) {
            case "JAVA_CODE_ONLY" -> "Return only valid Java code of the test class. Do not include explanations, markdown, or JSON.";
            case "JAVA_CODE_WITH_COMMENTS" -> "Return Java test code and inline comments only, without additional prose or formatting.";
            default -> "Return output adhering strictly to the " + normalised + " format.";
        };
    }

    private String fallbackPrompt() {
        return "You are an AI agent that generates Java JUnit 5 unit tests using Mockito." + System.lineSeparator()
                + "The context is missing or incomplete — generate a generic unit test skeleton with mocks." + System.lineSeparator()
                + "Return only valid Java code of the test class.";
    }

    private boolean hasRepairFeedback(JSONObject contextJson) {
        return contextJson != null && contextJson.has("repair");
    }

    private void appendRepairDirectives(StringBuilder builder, JSONObject contextJson, String lineSeparator) {
        JSONObject repair = contextJson.optJSONObject("repair");
        if (repair == null) {
            return;
        }
        appendRuleLines(builder, promptSnippetCatalog.repairRules(), lineSeparator);
        List<String> latestStages = describeRepairStages(repair);
        if (!latestStages.isEmpty()) {
            builder.append("- Latest failing stages: ").append(String.join(", ", latestStages)).append(".").append(lineSeparator);
        }
    }

    private void appendSutConstructionDirectives(StringBuilder builder,
                                                 JSONObject sutConstructionPolicy,
                                                 String lineSeparator) {
        if (builder == null || sutConstructionPolicy == null || sutConstructionPolicy.isEmpty()) {
            return;
        }
        if (!sutConstructionPolicy.optBoolean("requiresExplicitConstructorInjection")) {
            return;
        }
        appendRuleLines(builder, promptSnippetCatalog.sutConstructionRules(), lineSeparator);
        JSONArray preferredConstructors = sutConstructionPolicy.optJSONArray("preferredConstructors");
        JSONArray fallbackMockCandidates = sutConstructionPolicy.optJSONArray("fallbackMockCandidates");
        if (fallbackMockCandidates != null && !fallbackMockCandidates.isEmpty()) {
            builder.append("- The following required collaborators have no legal real construction path in the current context and must be promoted to Mockito mocks: ")
                    .append(joinJsonArray(fallbackMockCandidates))
                    .append('.')
                    .append(lineSeparator);
        }
        if (preferredConstructors == null || preferredConstructors.isEmpty()) {
            return;
        }
        for (int index = 0; index < preferredConstructors.length(); index++) {
            JSONObject constructor = preferredConstructors.optJSONObject(index);
            if (constructor == null) {
                continue;
            }
            JSONArray requiredArgs = constructor.optJSONArray("requiredConstructorArgs");
            if (requiredArgs == null || requiredArgs.isEmpty()) {
                continue;
            }
            List<String> argumentSummaries = new ArrayList<>();
            for (int argIndex = 0; argIndex < requiredArgs.length(); argIndex++) {
                JSONObject argument = requiredArgs.optJSONObject(argIndex);
                if (argument == null) {
                    continue;
                }
                StringBuilder summary = new StringBuilder();
                int position = argument.optInt("position", -1);
                if (position > 0) {
                    summary.append('#').append(position);
                }
                String parameterName = argument.optString("parameterName", "").trim();
                if (!parameterName.isBlank()) {
                    if (!summary.isEmpty()) {
                        summary.append(' ');
                    }
                    summary.append(parameterName);
                }
                if (!summary.isEmpty()) {
                    argumentSummaries.add(summary.toString());
                }
            }
            if (argumentSummaries.isEmpty()) {
                continue;
            }
            String signature = constructor.optString("signature", "").trim();
            builder.append("- For constructor ")
                    .append(signature.isBlank() ? "the preferred SUT constructor" : '"' + signature + '"')
                    .append(", the following required constructor arguments must not be null literals: ")
                    .append(String.join(", ", argumentSummaries))
                    .append('.')
                    .append(lineSeparator);
        }
    }

    private void appendStateModelDirectives(StringBuilder builder,
                                            JSONObject stateModel,
                                            String lineSeparator) {
        if (builder == null || stateModel == null || stateModel.isEmpty()) {
            return;
        }
        appendRuleLines(builder, promptSnippetCatalog.stateModelRules(), lineSeparator);
        String currentState = stateModel.optString("currentState").trim();
        if (!currentState.isBlank()) {
            builder.append("- Current state: ").append(currentState).append('.').append(lineSeparator);
        }
        JSONArray allowedActions = stateModel.optJSONArray("allowedActions");
        if (allowedActions != null && !allowedActions.isEmpty()) {
            builder.append("- Allowed actions in this state: ").append(joinJsonArray(allowedActions)).append('.').append(lineSeparator);
        }
        JSONArray forbiddenActions = stateModel.optJSONArray("forbiddenActions");
        if (forbiddenActions != null && !forbiddenActions.isEmpty()) {
            builder.append("- Forbidden actions in this state: ").append(joinJsonArray(forbiddenActions)).append('.').append(lineSeparator);
        }
    }

    private void appendConstructorLocalDirectives(StringBuilder builder,
                                                  JSONArray constructorLocalContexts,
                                                  String lineSeparator) {
        appendRuleLines(builder, promptSnippetCatalog.constructorLocalGenerationRules(), lineSeparator);
        for (int index = 0; index < constructorLocalContexts.length(); index++) {
            JSONObject context = constructorLocalContexts.optJSONObject(index);
            if (context == null) {
                continue;
            }
            String variable = context.optString("variable", "").trim();
            String className = context.optString("className", "").trim();
            JSONArray invokedMethods = context.optJSONArray("invokedMethods");
            if (invokedMethods == null || invokedMethods.isEmpty()) {
                continue;
            }
            List<String> methodSummaries = new ArrayList<>();
            for (int methodIndex = 0; methodIndex < invokedMethods.length(); methodIndex++) {
                JSONObject method = invokedMethods.optJSONObject(methodIndex);
                if (method == null) {
                    continue;
                }
                String name = method.optString("name", "").trim();
                if (!name.isBlank()) {
                    methodSummaries.add((className.isBlank() ? "" : className + ".") + name);
                }
            }
            if (methodSummaries.isEmpty()) {
                continue;
            }
            String helperLabel = variable.isBlank() ? "the constructor-local helper" : '"' + variable + '"';
            builder.append("- Constructor-local context available for ")
                    .append(helperLabel)
                    .append(": ")
                    .append(String.join(", ", methodSummaries))
                    .append('.')
                    .append(lineSeparator);
            appendConstructorLocalMethodDetails(builder, helperLabel, invokedMethods, lineSeparator);
        }
    }

    private void appendConstructorLocalMethodDetails(StringBuilder builder,
                                                     String helperLabel,
                                                     JSONArray invokedMethods,
                                                     String lineSeparator) {
        if (builder == null || invokedMethods == null || invokedMethods.isEmpty()) {
            return;
        }
        for (int index = 0; index < invokedMethods.length(); index++) {
            JSONObject method = invokedMethods.optJSONObject(index);
            if (method == null) {
                continue;
            }
            JSONArray branchDrivers = method.optJSONArray("branchDrivers");
            if (branchDrivers != null && !branchDrivers.isEmpty()) {
                builder.append("- Branch drivers for ")
                        .append(helperLabel)
                        .append(" via ")
                        .append(methodLabel(method))
                        .append(": ")
                        .append(joinJsonArray(branchDrivers))
                        .append('.')
                        .append(lineSeparator);
            }
            JSONArray voidSideEffects = method.optJSONArray("voidSideEffects");
            if (voidSideEffects != null && !voidSideEffects.isEmpty()) {
                builder.append("- Void side effects for ")
                        .append(helperLabel)
                        .append(" via ")
                        .append(methodLabel(method))
                        .append(": ")
                        .append(joinJsonArray(voidSideEffects))
                        .append('.')
                        .append(lineSeparator);
            }
            JSONArray publicStateMutators = method.optJSONArray("publicStateMutators");
            if (publicStateMutators != null && !publicStateMutators.isEmpty()) {
                builder.append("- Public state mutators for ")
                        .append(helperLabel)
                        .append(" via ")
                        .append(methodLabel(method))
                        .append(": ")
                        .append(joinJsonArray(publicStateMutators))
                        .append('.')
                        .append(lineSeparator);
            }
        }
    }

    private String methodLabel(JSONObject method) {
        if (method == null) {
            return "the invoked method";
        }
        String name = method.optString("name", "").trim();
        int arity = method.optInt("arity", -1);
        if (name.isBlank()) {
            return "the invoked method";
        }
        if (arity < 0) {
            return name;
        }
        return name + "/" + arity;
    }

    private List<String> describeRepairStages(JSONObject repair) {
        JSONArray stageFeedback = repair.optJSONArray("stageFeedback");
        if (stageFeedback == null || stageFeedback.length() == 0) {
            return List.of();
        }
        List<String> stages = new ArrayList<>();
        for (int index = 0; index < stageFeedback.length(); index++) {
            JSONObject entry = stageFeedback.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            String stage = entry.optString("stage");
            if (!stage.isBlank()) {
                stages.add(stage);
            }
        }
        return stages;
    }

    private void appendRuleLines(StringBuilder builder, List<String> rules, String lineSeparator) {
        if (builder == null || rules == null || rules.isEmpty()) {
            return;
        }
        for (String rule : rules) {
            if (rule == null || rule.isBlank()) {
                continue;
            }
            builder.append("- ").append(rule).append(lineSeparator);
        }
    }

    private String joinJsonArray(JSONArray array) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            String value = array.optString(index, "").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return String.join(", ", values);
    }
}
