package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.PromptConfig;
import com.gigachat.unit.tests.generator.config.PromptMode;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.MockTarget;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze.AnalysisSummary;
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.InstructionComposerFactory;
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.InstructionComposerStrategy;
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.InstructionContext;
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.ConstructorLocalPromptContextBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.PromptContextBlockBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.PromptJsonRenderer;
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.PromptTextRenderer;
import com.gigachat.unit.tests.generator.resources.PromptSnippetCatalog;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Combines different sources of information into a final prompt for LLM invocation.
 */
public class PromptBuilder {
    private final InstructionComposerFactory composerFactory;
    private final PromptJsonRenderer jsonRenderer;
    private final PromptTextRenderer promptTextRenderer;
    private final PromptContextBlockBuilder contextBlockBuilder;

    public PromptBuilder() {
        this(new InstructionComposerFactory(), new PromptJsonRenderer(), new PromptSnippetCatalog());
    }

    public PromptBuilder(InstructionComposerFactory composerFactory, PromptJsonRenderer jsonRenderer) {
        this(composerFactory, jsonRenderer, new PromptSnippetCatalog());
    }

    public PromptBuilder(InstructionComposerFactory composerFactory,
                         PromptJsonRenderer jsonRenderer,
                         PromptSnippetCatalog promptSnippetCatalog) {
        this.composerFactory = Objects.requireNonNull(composerFactory, "composerFactory");
        this.jsonRenderer = Objects.requireNonNull(jsonRenderer, "jsonRenderer");
        this.promptTextRenderer = new PromptTextRenderer(Objects.requireNonNull(promptSnippetCatalog, "promptSnippetCatalog"));
        this.contextBlockBuilder = new PromptContextBlockBuilder();
    }

    public String build(AgentConfig config,
                        TestClassInfo classInfo,
                        TestMethodInfo methodInfo,
                        String skeletonJson,
                        AnalysisSummary summary) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(classInfo, "classInfo");
        Objects.requireNonNull(methodInfo, "methodInfo");
        Objects.requireNonNull(summary, "summary");
        PromptConfig promptConfig = config.getPromptConfig();
        InstructionContext context = new InstructionContext(classInfo,
                methodInfo,
                summary.methodAnalysis(),
                summary.mockPlan(),
                summary.verificationPolicy(),
                promptConfig);
        InstructionComposerStrategy strategy = composerFactory.select(promptConfig.mode(), context);
        Map<String, Object> instructions = strategy.compose(context);
        sanitiseVerificationPolicy(instructions, summary);

        MockPlan mockPlan = summary.mockPlan();
        if (mockPlan != null && mockPlan.strategy() == MockStrategy.NONE) {
            instructions.remove("verificationPolicy");
            instructions.remove("mockFramework");
            instructions.remove("mockStrategy");
        }

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        String goal = resolveGoal(promptConfig.mode(), context);
        if (!goal.isBlank()) {
            root.put("goal", goal);
        }
        if (!instructions.isEmpty()) {
            root.put("instructions", instructions);
        }
        Map<String, Object> mockPolicy = contextBlockBuilder.buildMockPolicyBlock();
        if (!mockPolicy.isEmpty()) {
            root.put("mockPolicy", mockPolicy);
        }
        if (skeletonJson != null && !skeletonJson.isBlank()) {
            root.put("methodContext", PromptJsonRenderer.raw(skeletonJson));
        }
        Analyze.TestTargetContext targetContext = summary.testTargetContext();
        if (targetContext != null) {
            Map<String, Object> targetBlock = contextBlockBuilder.buildTestTargetBlock(targetContext);
            if (!targetBlock.isEmpty()) {
                root.put("testTarget", targetBlock);
            }
        }
        Map<String, List<ConstructorMetadata>> constructorMetadata =
                contextBlockBuilder.mergeConstructorMetadata(summary, classInfo, methodInfo);
        if (!constructorMetadata.isEmpty()) {
            Map<String, Object> constructorsBlock = contextBlockBuilder.buildAvailableConstructors(constructorMetadata);
            if (!constructorsBlock.isEmpty()) {
                root.put("availableConstructors", constructorsBlock);
            }
        }
        Map<String, Object> sutConstructionPolicy = contextBlockBuilder.buildSutConstructionPolicy(summary, config.getProjectPath());
        if (!sutConstructionPolicy.isEmpty()) {
            root.put("sutConstructionPolicy", sutConstructionPolicy);
        }
        Map<String, List<String>> availableMethods = new LinkedHashMap<>(summary.availableMethods());
        for (String stdType : Analyze.STANDARD_TYPES) {
            String simple = simpleName(stdType);
            List<String> methods = summary.availableMethods().get(simple);
            if (methods != null && !methods.isEmpty()) {
                availableMethods.putIfAbsent(simple, methods);
            }
        }
        if (!availableMethods.isEmpty()) {
            root.put("availableMethods", availableMethods);
        }
        List<Map<String, Object>> constructorLocalContexts =
                new ConstructorLocalPromptContextBuilder(config.getProjectPath()).build(classInfo, summary);
        if (!constructorLocalContexts.isEmpty()) {
            root.put("constructorLocalContexts", constructorLocalContexts);
        }
        root.put("accessibleFields", contextBlockBuilder.filterAccessibleFields(summary.accessibleFields()));
        root.put("constructorPolicy", Map.of(
                "mustUseAvailableConstructors", Boolean.TRUE,
                "forbidInventedConstructors", Boolean.TRUE));
        String analysisJson = summary.jsonContext();
        if (analysisJson != null && !analysisJson.isBlank()) {
            root.put("analysis", PromptJsonRenderer.raw(analysisJson));
        }
        List<String> forbiddenDirectMockTargets = contextBlockBuilder.determineForbiddenDirectMockTargets(context);
        if (!forbiddenDirectMockTargets.isEmpty()) {
            root.put("forbiddenDirectMockTargets", forbiddenDirectMockTargets);
        }
        root.put("hasExternalCollaborators", summary.hasExternalCollaborators());
        if (!summary.invalidCalls().isEmpty()) {
            root.put("invalidCalls", summary.invalidCalls());
        }
        if (mockPlan != null) {
            Map<String, Object> planBlock = contextBlockBuilder.buildMockPlan(mockPlan);
            if (!planBlock.isEmpty()) {
                root.put("mockPlan", planBlock);
            }
        }
        List<String> hints = contextBlockBuilder.determineHints(context);
        if (!hints.isEmpty()) {
            root.put("hints", hints);
        }
        return jsonRenderer.render(root);
    }

    public String buildPromptForLLM(JSONObject contextJson) {
        return buildPromptForLLM(contextJson, PromptConfig.defaults());
    }

    public String buildPromptForLLM(JSONObject contextJson, PromptConfig promptConfig) {
        return promptTextRenderer.render(contextJson, promptConfig);
    }

    private String resolveGoal(PromptMode mode, InstructionContext context) {
        return switch (mode) {
            case GENERATION -> generationGoal(context);
            case REPAIR -> "Repair an existing JUnit 5 unit test using the provided diagnostics.";
            case EXECUTION -> "Analyse runtime behaviour for the generated unit test and surface actionable checks.";
            case SYNTHESIS -> "Synthesize supporting fixtures or utilities that help stabilise the target unit test.";
        };
    }

    private String generationGoal(InstructionContext context) {
        if (context.isUtilityLike()) {
            return "Generate a JUnit 5 unit test for a utility method without relying on mocks.";
        }
        if (context.isPureFunction()) {
            return "Generate a focused JUnit 5 unit test for a pure function emphasising assertions.";
        }
        if (context.hasMocks()) {
            return "Generate a JUnit 5 unit test for the target method using Mockito to isolate dependencies.";
        }
        return "Generate a JUnit 5 unit test for the target method.";
    }

    private void sanitiseVerificationPolicy(Map<String, Object> instructions, AnalysisSummary summary) {
        if (instructions == null || summary == null) {
            return;
        }
        Object block = instructions.get("verificationPolicy");
        if (!(block instanceof Map<?, ?> rawPolicy)) {
            return;
        }
        Set<String> internal = summary.internalFields();
        if (internal == null || internal.isEmpty()) {
            return;
        }
        LinkedHashMap<String, String> sanitised = new LinkedHashMap<>();
        rawPolicy.forEach((key, value) -> {
            String keyText = key == null ? "" : key.toString();
            String valueText = value == null ? "" : value.toString();
            if (referencesInternalField(keyText, internal) || referencesInternalField(valueText, internal)) {
                return;
            }
            sanitised.put(keyText, valueText);
        });
        if (sanitised.isEmpty()) {
            instructions.remove("verificationPolicy");
        } else {
            instructions.put("verificationPolicy", sanitised);
        }
    }

    private boolean referencesInternalField(String text, Set<String> internalFields) {
        if (text == null || text.isBlank() || internalFields == null || internalFields.isEmpty()) {
            return false;
        }
        String candidate = text.trim();
        for (String field : internalFields) {
            if (field == null || field.isBlank()) {
                continue;
            }
            String token = field.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (candidate.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String simpleName(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        int lastDot = type.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < type.length()) {
            return type.substring(lastDot + 1);
        }
        return type;
    }

}
