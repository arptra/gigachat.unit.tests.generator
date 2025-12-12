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
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.PromptJsonRenderer;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.IntersectionType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnionType;
import com.github.javaparser.ast.type.WildcardType;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        Map<String, List<ConstructorMetadata>> constructorMetadata =
                mergeConstructorMetadata(summary, classInfo, methodInfo);
        if (!constructorMetadata.isEmpty()) {
            Map<String, Object> constructorsBlock = buildAvailableConstructors(constructorMetadata);
            if (!constructorsBlock.isEmpty()) {
                root.put("availableConstructors", constructorsBlock);
            }
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
        root.put("accessibleFields", filterAccessibleFields(summary.accessibleFields()));
        root.put("constructorPolicy", Map.of(
                "mustUseAvailableConstructors", Boolean.TRUE,
                "forbidInventedConstructors", Boolean.TRUE));
        String analysisJson = summary.jsonContext();
        if (analysisJson != null && !analysisJson.isBlank()) {
            root.put("analysis", PromptJsonRenderer.raw(analysisJson));
        }
        root.put("hasExternalCollaborators", summary.hasExternalCollaborators());
        if (!summary.invalidCalls().isEmpty()) {
            root.put("invalidCalls", summary.invalidCalls());
        }
        if (mockPlan != null) {
            Map<String, Object> planBlock = buildMockPlan(mockPlan);
            if (!planBlock.isEmpty()) {
                root.put("mockPlan", planBlock);
            }
        }
        List<String> hints = determineHints(context);
        if (!hints.isEmpty()) {
            root.put("hints", hints);
        }
        customizeContext(root, config, classInfo, methodInfo, skeletonJson, summary);
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
        JSONObject instructionsBlock = contextJson.optJSONObject("instructions");
        JSONObject mockPlanBlock = contextJson.optJSONObject("mockPlan");
        String strategy = mockPlanBlock == null ? "" : mockPlanBlock.optString("strategy", "");
        boolean mocksAllowed = !"NONE".equalsIgnoreCase(strategy);
        boolean hasVerificationPolicy = instructionsBlock != null && instructionsBlock.has("verificationPolicy");

        builder.append("Your task:").append(lineSeparator);
        builder.append("- Never access internal or private fields of the tested class.").append(lineSeparator);
        builder.append("- Use only constructors and methods provided in availableConstructors and availableMethods.").append(lineSeparator);
        builder.append("- Assert behavior using public APIs like findAll(), size(), or getters.").append(lineSeparator);
        builder.append("- Do not reimplement or simplify the tested method.").append(lineSeparator);
        builder.append("- Generate a JUnit 5 test class based on the following structured JSON context.").append(lineSeparator);
        builder.append("- Follow the \"goal\" and \"instructions\" fields to guide the behavior and structure.").append(lineSeparator);
        builder.append("- The \"methodSignature\" field describes the method that must be tested, NOT re-implemented.").append(lineSeparator);
        builder.append("- Do NOT include the original method implementation inside the test class.").append(lineSeparator);
        builder.append("- Always invoke the tested method on the instance of the class under test (for example: repository.save(user)).").append(lineSeparator);
        builder.append("- Use the \"testTarget.instanceName\" as the variable name for the tested object.").append(lineSeparator);
        builder.append("- Use only constructors listed in \"availableConstructors\".").append(lineSeparator);
        builder.append("- Follow each parameter type and count exactly.").append(lineSeparator);
        builder.append("- Do not invent or simplify constructor arguments.").append(lineSeparator);
        builder.append("- Use only methods listed in \"availableMethods\".").append(lineSeparator);
        builder.append("- When mocking or instantiating objects, use only constructors and methods provided in the JSON context.").append(lineSeparator);
        builder.append("- If you must create an object, use a constructor from \"availableConstructors\"; if none fit, skip that instance.").append(lineSeparator);
        builder.append("- Do not access or modify private or internal fields (like repository.users).").append(lineSeparator);
        builder.append("- Use only fields listed in \"accessibleFields\".").append(lineSeparator);
        builder.append("- To prepare state, call public methods (for example: save()) instead of assigning to fields.").append(lineSeparator);
        builder.append("- If state preparation requires internal field access, skip that test case.").append(lineSeparator);
        if (mocksAllowed) {
            builder.append("- Use Mockito to mock external dependencies listed in the mock plan.").append(lineSeparator);
            builder.append("- Only mock external dependencies such as services, repositories, or network clients.").append(lineSeparator);
            builder.append("- Do not mock private or internal data structures of the tested class.").append(lineSeparator);
            builder.append("- Mock dependencies listed in \"shouldMock\".").append(lineSeparator);
            builder.append("- Keep real objects listed in \"shouldNotMock\".").append(lineSeparator);
            if (hasVerificationPolicy) {
                builder.append("- Add verification calls from \"verificationPolicy\" using Mockito.verify().").append(lineSeparator);
            }
            builder.append("- Mockito should only be used for external or collaborative dependencies.").append(lineSeparator);
            builder.append("- Never use Mockito for internal fields or collections of the tested class.").append(lineSeparator);
        } else {
            builder.append("- Do not use Mockito. Use only JUnit 5 and real objects.").append(lineSeparator);
            builder.append("- Focus on asserting observable behaviour through the class's public API.").append(lineSeparator);
            builder.append("- Never modify or access internal fields of the tested class.").append(lineSeparator);
            builder.append("- Use only public methods or constructors listed in \"availableConstructors\" to prepare state.").append(lineSeparator);
            builder.append("- If preparing internal state is impossible without private access, skip that scenario.").append(lineSeparator);
        }
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

    protected void customizeContext(Map<String, Object> root,
                                    AgentConfig config,
                                    TestClassInfo classInfo,
                                    TestMethodInfo methodInfo,
                                    String skeletonJson,
                                    AnalysisSummary summary) {
        // Default implementation intentionally left blank.
    }


    private Map<String, Object> buildAvailableConstructors(Map<String, List<ConstructorMetadata>> constructors) {
        LinkedHashMap<String, Object> block = new LinkedHashMap<>();
        constructors.forEach((className, entries) -> {
            if (entries == null) {
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
                        if (parameter.modifiers() != null && !parameter.modifiers().isEmpty()) {
                            parameterBlock.put("modifiers", parameter.modifiers());
                        }
                        if (!parameterBlock.isEmpty()) {
                            parameters.add(parameterBlock);
                        }
                    }
                    if (!parameters.isEmpty()) {
                        descriptor.put("parameters", parameters);
                    }
                }
                constructorArray.add(descriptor);
            }
            if (!constructorArray.isEmpty()) {
                block.put(className, constructorArray);
            } else if (entries.isEmpty()) {
                block.put(className, List.of());
            }
        });
        return block;
    }

    private Map<String, List<ConstructorMetadata>> mergeConstructorMetadata(AnalysisSummary summary,
                                                                            TestClassInfo classInfo,
                                                                            TestMethodInfo methodInfo) {
        LinkedHashMap<String, List<ConstructorMetadata>> merged = new LinkedHashMap<>(summary.availableConstructors());
        addConstructorPlaceholders(merged, summary.methodParameterTypes());
        addConstructorPlaceholders(merged, summary.methodReturnTypes());
        Set<String> preferredTypes = determineModelDtoEntityTypes(classInfo, methodInfo);
        for (String type : preferredTypes) {
            merged.putIfAbsent(type, List.of());
        }
        return merged;
    }

    private void addConstructorPlaceholders(Map<String, List<ConstructorMetadata>> merged, Set<String> types) {
        if (merged == null || types == null || types.isEmpty()) {
            return;
        }
        for (String type : types) {
            if (type == null || type.isBlank()) {
                continue;
            }
            String simple = simpleName(type);
            if (simple.isEmpty()) {
                continue;
            }
            merged.putIfAbsent(simple, List.of());
        }
    }

    private Set<String> determineModelDtoEntityTypes(TestClassInfo classInfo, TestMethodInfo methodInfo) {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        if (classInfo == null || methodInfo == null) {
            return types;
        }
        Map<String, String> importLookup = buildImportLookup(classInfo.getImports());
        MethodDeclaration declaration = methodInfo.getDeclaration();
        if (declaration != null) {
            for (Parameter parameter : declaration.getParameters()) {
                addModelTypeFromType(parameter.getType(), importLookup, types);
            }
            addModelTypeFromType(declaration.getType(), importLookup, types);
        } else {
            addModelTypeFromString(methodInfo.getReturnType(), importLookup, types);
        }
        return types;
    }

    private void addModelTypeFromType(Type type,
                                      Map<String, String> importLookup,
                                      Set<String> collector) {
        if (type == null) {
            return;
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        collectTypeNames(type, names);
        for (String name : names) {
            addModelTypeFromString(name, importLookup, collector);
        }
    }

    private void addModelTypeFromString(String rawType,
                                        Map<String, String> importLookup,
                                        Set<String> collector) {
        if (rawType == null || rawType.isBlank()) {
            return;
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        collectTypeNamesFromString(rawType, tokens);
        for (String token : tokens) {
            maybeAddModelType(token, importLookup, collector);
        }
    }

    private void collectTypeNames(Type type, Set<String> collector) {
        if (type == null || collector == null) {
            return;
        }
        if (type.isPrimitiveType()) {
            return;
        }
        if (type instanceof ArrayType arrayType) {
            collectTypeNames(arrayType.getComponentType(), collector);
            return;
        }
        if (type instanceof UnionType unionType) {
            unionType.getElements().forEach(element -> collectTypeNames(element, collector));
            return;
        }
        if (type instanceof IntersectionType intersectionType) {
            intersectionType.getElements().forEach(element -> collectTypeNames(element, collector));
            return;
        }
        if (type instanceof WildcardType wildcardType) {
            wildcardType.getExtendedType().ifPresent(t -> collectTypeNames(t, collector));
            wildcardType.getSuperType().ifPresent(t -> collectTypeNames(t, collector));
            return;
        }
        if (type instanceof ClassOrInterfaceType classType) {
            collector.add(classType.getNameWithScope());
            classType.getTypeArguments()
                    .ifPresent(arguments -> arguments.forEach(argument -> collectTypeNames(argument, collector)));
            return;
        }
        collector.add(type.asString());
    }

    private void collectTypeNamesFromString(String type, Set<String> collector) {
        if (collector == null || type == null || type.isBlank()) {
            return;
        }
        collectTypeNamesFromStringInternal(type.trim(), collector);
    }

    private void collectTypeNamesFromStringInternal(String type, Set<String> collector) {
        if (type == null || type.isBlank()) {
            return;
        }
        String trimmed = stripDecorators(type);
        if (trimmed.isEmpty()) {
            return;
        }
        int genericStart = trimmed.indexOf('<');
        if (genericStart >= 0 && trimmed.endsWith(">")) {
            String base = trimmed.substring(0, genericStart).trim();
            if (!base.isEmpty()) {
                collector.add(base);
            }
            String content = trimmed.substring(genericStart + 1, trimmed.lastIndexOf('>'));
            int depth = 0;
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                if (ch == '<') {
                    depth++;
                } else if (ch == '>') {
                    depth = Math.max(0, depth - 1);
                } else if (ch == ',' && depth == 0) {
                    collectTypeNamesFromStringInternal(current.toString(), collector);
                    current.setLength(0);
                    continue;
                }
                current.append(ch);
            }
            if (current.length() > 0) {
                collectTypeNamesFromStringInternal(current.toString(), collector);
            }
            return;
        }
        if (trimmed.contains("|")) {
            for (String part : trimmed.split("\\|")) {
                collectTypeNamesFromStringInternal(part, collector);
            }
            return;
        }
        if (trimmed.contains("&")) {
            for (String part : trimmed.split("&")) {
                collectTypeNamesFromStringInternal(part, collector);
            }
            return;
        }
        collector.add(trimmed.trim());
    }

    private void maybeAddModelType(String candidate,
                                   Map<String, String> importLookup,
                                   Set<String> collector) {
        if (candidate == null) {
            return;
        }
        String base = stripDecorators(candidate);
        if (base.isEmpty()) {
            return;
        }
        int genericStart = base.indexOf('<');
        if (genericStart >= 0) {
            base = base.substring(0, genericStart).trim();
        }
        if (base.isEmpty()) {
            return;
        }
        String resolved = resolveQualifiedName(base, importLookup);
        String lower = resolved.toLowerCase(Locale.ROOT);
        if (lower.contains(".model.")
                || lower.contains(".dto.")
                || lower.contains(".entity.")
                || lower.startsWith("model.")
                || lower.startsWith("dto.")
                || lower.startsWith("entity.")) {
            collector.add(simpleName(resolved));
        }
    }

    private Map<String, String> buildImportLookup(List<String> imports) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (imports == null) {
            return map;
        }
        for (String entry : imports) {
            String cleaned = defaultString(entry);
            if (cleaned.isEmpty()) {
                continue;
            }
            cleaned = cleaned.replace("import", "").replace(";", "").trim();
            if (cleaned.endsWith(".*")) {
                continue;
            }
            int lastDot = cleaned.lastIndexOf('.');
            if (lastDot > 0 && lastDot + 1 < cleaned.length()) {
                String simple = cleaned.substring(lastDot + 1);
                map.put(simple, cleaned);
            }
        }
        return map;
    }

    private String resolveQualifiedName(String base, Map<String, String> importLookup) {
        if (base == null || base.isBlank()) {
            return "";
        }
        String trimmed = base.trim();
        if (trimmed.contains(".")) {
            return trimmed;
        }
        return importLookup.getOrDefault(trimmed, trimmed);
    }

    private String stripDecorators(String type) {
        String text = defaultString(type);
        if (text.endsWith("...")) {
            text = text.substring(0, text.length() - 3).trim();
        }
        while (text.endsWith("[]")) {
            text = text.substring(0, text.length() - 2).trim();
        }
        if (text.startsWith("? extends ")) {
            text = text.substring(10).trim();
        } else if (text.startsWith("? super ")) {
            text = text.substring(8).trim();
        } else if (text.startsWith("?")) {
            text = text.substring(1).trim();
        }
        return text.trim();
    }

    private String simpleName(String type) {
        String text = defaultString(type);
        if (text.isEmpty()) {
            return "";
        }
        int genericStart = text.indexOf('<');
        if (genericStart >= 0) {
            text = text.substring(0, genericStart);
        }
        int arrayIndex = text.indexOf('[');
        if (arrayIndex >= 0) {
            text = text.substring(0, arrayIndex);
        }
        int lastDot = text.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < text.length()) {
            return text.substring(lastDot + 1);
        }
        return text;
    }

    private String defaultString(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
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

    private List<String> filterAccessibleFields(Set<String> accessibleFields) {
        if (accessibleFields == null || accessibleFields.isEmpty()) {
            return List.of();
        }
        List<String> filtered = new ArrayList<>();
        for (String field : accessibleFields) {
            if (field == null) {
                continue;
            }
            String trimmed = field.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            filtered.add(trimmed);
        }
        return filtered;
    }
}
