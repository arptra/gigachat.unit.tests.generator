package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.PromptConfig;
import com.gigachat.unit.tests.generator.config.PromptMode;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockTarget;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze.AnalysisSummary;
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.InstructionComposerFactory;
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.InstructionComposerStrategy;
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.InstructionContext;
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.PromptJsonRenderer;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;

/**
 * Combines different sources of information into a final prompt for LLM invocation.
 */
public class PromptBuilder {
    private final InstructionComposerFactory composerFactory;
    private final PromptJsonRenderer jsonRenderer;

    public PromptBuilder() {
        this(new InstructionComposerFactory(), new PromptJsonRenderer());
    }

    public PromptBuilder(InstructionComposerFactory composerFactory, PromptJsonRenderer jsonRenderer) {
        this.composerFactory = Objects.requireNonNull(composerFactory, "composerFactory");
        this.jsonRenderer = Objects.requireNonNull(jsonRenderer, "jsonRenderer");
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

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        String goal = resolveGoal(promptConfig.mode(), context);
        if (!goal.isBlank()) {
            root.put("goal", goal);
        }
        if (!instructions.isEmpty()) {
            root.put("instructions", instructions);
        }
        Map<String, Object> mockPolicy = buildMockPolicyBlock();
        if (!mockPolicy.isEmpty()) {
            root.put("mockPolicy", mockPolicy);
        }
        if (skeletonJson != null && !skeletonJson.isBlank()) {
            root.put("methodContext", PromptJsonRenderer.raw(skeletonJson));
        }
        Analyze.TestTargetContext targetContext = summary.testTargetContext();
        if (targetContext != null) {
            Map<String, Object> targetBlock = buildTestTargetBlock(targetContext);
            if (!targetBlock.isEmpty()) {
                root.put("testTarget", targetBlock);
            }
        }
        if (!summary.availableConstructors().isEmpty()) {
            root.put("availableConstructors", buildAvailableConstructors(summary.availableConstructors()));
        }
        if (!summary.availableMethods().isEmpty()) {
            root.put("availableMethods", summary.availableMethods());
        }
        String analysisJson = summary.jsonContext();
        if (analysisJson != null && !analysisJson.isBlank()) {
            root.put("analysis", PromptJsonRenderer.raw(analysisJson));
        }
        root.put("hasExternalCollaborators", summary.hasExternalCollaborators());
        if (!summary.invalidCalls().isEmpty()) {
            root.put("invalidCalls", summary.invalidCalls());
        }
        if (summary.mockPlan() != null) {
            Map<String, Object> planBlock = buildMockPlan(summary.mockPlan());
            if (!planBlock.isEmpty()) {
                root.put("mockPlan", planBlock);
            }
        }
        List<String> hints = determineHints(context);
        if (!hints.isEmpty()) {
            root.put("hints", hints);
        }
        return jsonRenderer.render(root);
    }

    public String buildPromptForLLM(JSONObject contextJson) {
        return buildPromptForLLM(contextJson, PromptConfig.defaults());
    }

    public String buildPromptForLLM(JSONObject contextJson, PromptConfig promptConfig) {
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
        builder.append("Your task:").append(lineSeparator);
        builder.append("- Generate a JUnit 5 test class using Mockito based on the following structured JSON context.").append(lineSeparator);
        builder.append("- Follow the \"goal\" and \"instructions\" fields to guide the behavior and structure.").append(lineSeparator);
        builder.append("- The \"methodSignature\" field describes the method that must be tested, NOT re-implemented.").append(lineSeparator);
        builder.append("- Do NOT include the original method implementation inside the test class.").append(lineSeparator);
        builder.append("- Always invoke the tested method on the instance of the class under test (for example: repository.save(user)).").append(lineSeparator);
        builder.append("- Use the \"testTarget.instanceName\" as the variable name for the tested object.").append(lineSeparator);
        builder.append("- Use only constructors listed in \"availableConstructors\".").append(lineSeparator);
        builder.append("- Follow each parameter type and count exactly.").append(lineSeparator);
        builder.append("- Do not invent or simplify constructor arguments.").append(lineSeparator);
        builder.append("- When mocking or instantiating objects, use only constructors and methods provided in the JSON context.").append(lineSeparator);
        builder.append("- Determine mock usage automatically based on dependencies.").append(lineSeparator);
        builder.append("- Do not mock private or internal data structures of the tested class.").append(lineSeparator);
        builder.append("- Only mock external dependencies such as services, repositories, or network clients.").append(lineSeparator);
        builder.append("- Mockito should only be used for external or collaborative dependencies.").append(lineSeparator);
        builder.append("- If the tested method has no external dependencies, use real objects and assert state changes.").append(lineSeparator);
        if (contextJson.optBoolean("hasExternalCollaborators", false)) {
            builder.append("- Use Mockito to mock external dependencies listed in the mock plan.").append(lineSeparator);
        } else {
            builder.append("- Do not use Mockito. Use only JUnit 5 and real objects.").append(lineSeparator);
        }
        builder.append("- Mock dependencies listed in \"shouldMock\".").append(lineSeparator);
        builder.append("- Keep real objects listed in \"shouldNotMock\".").append(lineSeparator);
        builder.append("- Add verification calls from \"verificationPolicy\" using Mockito.verify().").append(lineSeparator);
        builder.append("- Add assertions for return values or side effects.").append(lineSeparator);
        builder.append("- Respect the naming convention from \"instructions.namingConvention\".").append(lineSeparator);
        builder.append("- Use standard Java indentation and line breaks.").append(lineSeparator);
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


    private Map<String, Object> buildAvailableConstructors(Map<String, List<ConstructorMetadata>> constructors) {
        LinkedHashMap<String, Object> block = new LinkedHashMap<>();
        constructors.forEach((className, entries) -> {
            if (entries == null || entries.isEmpty()) {
                return;
            }
            List<Map<String, Object>> constructorArray = new ArrayList<>();
            for (ConstructorMetadata metadata : entries) {
                if (metadata == null || metadata.signature().isBlank()) {
                    continue;
                }
                LinkedHashMap<String, Object> descriptor = new LinkedHashMap<>();
                descriptor.put("signature", metadata.signature());
                if (metadata.parameters() != null && !metadata.parameters().isEmpty()) {
                    List<Map<String, Object>> parameters = new ArrayList<>();
                    for (ParameterMetadata parameter : metadata.parameters()) {
                        if (parameter == null) {
                            continue;
                        }
                        LinkedHashMap<String, Object> parameterBlock = new LinkedHashMap<>();
                        if (parameter.name() != null && !parameter.name().isBlank()) {
                            parameterBlock.put("name", parameter.name());
                        }
                        if (parameter.type() != null && !parameter.type().isBlank()) {
                            parameterBlock.put("type", parameter.type());
                        }
                        if (parameter.description() != null) {
                            parameterBlock.put("description", parameter.description());
                        }
                        if (!parameterBlock.isEmpty()) {
                            parameters.add(parameterBlock);
                        }
                    }
                    if (!parameters.isEmpty()) {
                        descriptor.put("parameters", parameters);
                    }
                }
                if (metadata.hints() != null && !metadata.hints().isEmpty()) {
                    descriptor.put("hints", metadata.hints());
                }
                constructorArray.add(descriptor);
            }
            if (!constructorArray.isEmpty()) {
                block.put(className, constructorArray);
            }
        });
        return block;
    }

    private Map<String, Object> buildMockPlan(MockPlan plan) {
        LinkedHashMap<String, Object> block = new LinkedHashMap<>();
        block.put("strategy", plan.strategy());
        if (!plan.targets().isEmpty()) {
            List<Map<String, String>> targets = new ArrayList<>();
            for (MockTarget target : plan.targets()) {
                LinkedHashMap<String, String> entry = new LinkedHashMap<>();
                entry.put("type", target.qualifiedType());
                entry.put("identifier", target.identifier());
                targets.add(entry);
            }
            block.put("targets", targets);
        }
        if (!plan.shouldMock().isEmpty()) {
            block.put("shouldMock", plan.shouldMock());
        }
        if (!plan.shouldNotMock().isEmpty()) {
            block.put("shouldNotMock", plan.shouldNotMock());
        }
        return block;
    }

    private Map<String, Object> buildMockPolicyBlock() {
        LinkedHashMap<String, Object> block = new LinkedHashMap<>();
        block.put("internalFieldsAreInaccessible", true);
        block.put("mockInternalStructures", false);
        block.put("mockOnlyExternalDependencies", true);
        return block;
    }

    private Map<String, Object> buildTestTargetBlock(Analyze.TestTargetContext targetContext) {
        LinkedHashMap<String, Object> block = new LinkedHashMap<>();
        if (targetContext.className() != null && !targetContext.className().isBlank()) {
            block.put("className", targetContext.className());
        }
        if (targetContext.instanceName() != null && !targetContext.instanceName().isBlank()) {
            block.put("instanceName", targetContext.instanceName());
        }
        block.put("requiresInstance", targetContext.requiresInstance());
        block.put("isStatic", targetContext.isStatic());
        return block;
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

    private List<String> determineHints(InstructionContext context) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (context.isPureFunction()) {
            hints.add("Method appears to have no external dependencies.");
        }
        if (!context.hasMocks()) {
            hints.add("Use assertions instead of mocks.");
        }
        if (context.isStaticMethod()) {
            hints.add("Static method can be invoked directly; minimise setup.");
        }
        return new ArrayList<>(hints);
    }
}
