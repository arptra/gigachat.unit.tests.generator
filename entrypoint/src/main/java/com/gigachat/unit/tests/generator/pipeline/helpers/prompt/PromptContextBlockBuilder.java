package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.MockTarget;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.TargetConstructorPolicyResolver;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze.AnalysisSummary;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.IntersectionType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnionType;
import com.github.javaparser.ast.type.WildcardType;
import com.testagent.entrypoint.pipeline.helpers.analyze.DependencyInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.MockType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.file.Path;
import java.util.Set;

/**
 * Builds structured prompt context blocks without owning prompt orchestration.
 */
public class PromptContextBlockBuilder {

    private final TargetConstructorPolicyResolver targetConstructorPolicyResolver = new TargetConstructorPolicyResolver();

    public Map<String, Object> buildAvailableConstructors(Map<String, List<ConstructorMetadata>> constructors) {
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

    public Map<String, List<ConstructorMetadata>> mergeConstructorMetadata(AnalysisSummary summary,
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

    public Map<String, Object> buildMockPlan(MockPlan plan) {
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

    public Map<String, Object> buildMockPolicyBlock() {
        LinkedHashMap<String, Object> block = new LinkedHashMap<>();
        block.put("internalFieldsAreInaccessible", true);
        block.put("mockInternalStructures", false);
        block.put("mockOnlyExternalDependencies", true);
        return block;
    }

    public Map<String, Object> buildSutConstructionPolicy(AnalysisSummary summary, Path projectRoot) {
        if (summary == null || summary.testTargetContext() == null || summary.mockPlan() == null) {
            return Map.of();
        }
        Analyze.TestTargetContext targetContext = summary.testTargetContext();
        if (!targetContext.requiresInstance() || targetContext.isStatic()) {
            return Map.of();
        }
        MockPlan plan = summary.mockPlan();
        if (plan.shouldMock().isEmpty()) {
            return Map.of();
        }
        String targetClass = simpleName(targetContext.className());
        if (targetClass.isBlank()) {
            return Map.of();
        }
        LinkedHashSet<String> shouldMock = new LinkedHashSet<>(plan.shouldMock());
        Map<String, List<TargetConstructorPolicyResolver.RequiredConstructorArgument>> requiredArgsBySignature =
                targetConstructorPolicyResolver.resolveRequiredNonNullArguments(projectRoot, summary);
        List<Map<String, Object>> preferredConstructors = new ArrayList<>();
        boolean hasRequiredArguments = false;
        LinkedHashSet<String> fallbackMockCandidates = new LinkedHashSet<>();
        for (Map.Entry<String, List<ConstructorMetadata>> entry : summary.availableConstructors().entrySet()) {
            if (!targetClass.equals(simpleName(entry.getKey()))) {
                continue;
            }
            for (ConstructorMetadata metadata : entry.getValue()) {
                if (metadata == null || metadata.parameters() == null || metadata.parameters().isEmpty()) {
                    continue;
                }
                List<Map<String, Object>> mockBindings = new ArrayList<>();
                for (int index = 0; index < metadata.parameters().size(); index++) {
                    ParameterMetadata parameter = metadata.parameters().get(index);
                    if (parameter == null) {
                        continue;
                    }
                    String parameterName = defaultString(parameter.name());
                    String parameterType = simpleName(parameter.type());
                    if (!shouldMock.contains(parameterName) && !shouldMock.contains(lowerCamel(parameterType))) {
                        continue;
                    }
                    LinkedHashMap<String, Object> binding = new LinkedHashMap<>();
                    binding.put("position", index + 1);
                    if (!parameterName.isBlank()) {
                        binding.put("parameterName", parameterName);
                    }
                    if (!parameterType.isBlank()) {
                        binding.put("parameterType", parameterType);
                    }
                    mockBindings.add(binding);
                }
                if (mockBindings.isEmpty()) {
                    continue;
                }
                for (ParameterMetadata parameter : metadata.parameters()) {
                    if (parameter == null) {
                        continue;
                    }
                    String parameterName = defaultString(parameter.name());
                    String parameterType = simpleName(parameter.type());
                    if (shouldMock.contains(parameterName) || shouldMock.contains(lowerCamel(parameterType))) {
                        continue;
                    }
                    if (!isConstructibleFromAvailableConstructors(parameterType, summary.availableConstructors(), new LinkedHashSet<>())) {
                        fallbackMockCandidates.add(parameterType);
                    }
                }
                LinkedHashMap<String, Object> constructorBlock = new LinkedHashMap<>();
                constructorBlock.put("signature", metadata.signature());
                constructorBlock.put("mockBindings", mockBindings);
                List<TargetConstructorPolicyResolver.RequiredConstructorArgument> requiredArguments =
                        requiredArgsBySignature.getOrDefault(metadata.signature(), List.of());
                if (!requiredArguments.isEmpty()) {
                    List<Map<String, Object>> requiredArgumentBlocks = new ArrayList<>();
                    for (TargetConstructorPolicyResolver.RequiredConstructorArgument argument : requiredArguments) {
                        LinkedHashMap<String, Object> argumentBlock = new LinkedHashMap<>();
                        argumentBlock.put("position", argument.position());
                        if (argument.parameterName() != null && !argument.parameterName().isBlank()) {
                            argumentBlock.put("parameterName", argument.parameterName());
                        }
                        if (argument.parameterType() != null && !argument.parameterType().isBlank()) {
                            argumentBlock.put("parameterType", argument.parameterType());
                        }
                        requiredArgumentBlocks.add(argumentBlock);
                    }
                    constructorBlock.put("requiredConstructorArgs", requiredArgumentBlocks);
                    hasRequiredArguments = true;
                }
                preferredConstructors.add(constructorBlock);
            }
        }
        if (preferredConstructors.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> block = new LinkedHashMap<>();
        block.put("requiresExplicitConstructorInjection", Boolean.TRUE);
        block.put("targetClass", targetClass);
        block.put("targetInstance", defaultString(targetContext.instanceName()));
        block.put("mockedCollaborators", plan.shouldMock());
        block.put("preferredConstructors", preferredConstructors);
        if (!fallbackMockCandidates.isEmpty()) {
            block.put("fallbackMockCandidates", new ArrayList<>(fallbackMockCandidates));
        }
        if (hasRequiredArguments) {
            block.put("forbidNullLiteralForRequiredArgs", Boolean.TRUE);
        }
        return block;
    }

    private boolean isConstructibleFromAvailableConstructors(String type,
                                                             Map<String, List<ConstructorMetadata>> availableConstructors,
                                                             Set<String> visiting) {
        String simpleType = simpleName(type);
        if (simpleType.isBlank()) {
            return false;
        }
        if (isIntrinsicConstructibleType(simpleType)) {
            return true;
        }
        if (visiting.contains(simpleType)) {
            return false;
        }
        List<ConstructorMetadata> constructors = constructorsForSimpleName(simpleType, availableConstructors);
        if (constructors.isEmpty()) {
            return false;
        }
        visiting.add(simpleType);
        try {
            for (ConstructorMetadata constructor : constructors) {
                if (constructor == null) {
                    continue;
                }
                if (constructor.parameters() == null || constructor.parameters().isEmpty()) {
                    return true;
                }
                boolean allArgsConstructible = true;
                for (ParameterMetadata parameter : constructor.parameters()) {
                    if (parameter == null || !isConstructibleFromAvailableConstructors(parameter.type(), availableConstructors, visiting)) {
                        allArgsConstructible = false;
                        break;
                    }
                }
                if (allArgsConstructible) {
                    return true;
                }
            }
            return false;
        } finally {
            visiting.remove(simpleType);
        }
    }

    private List<ConstructorMetadata> constructorsForSimpleName(String simpleType,
                                                                Map<String, List<ConstructorMetadata>> availableConstructors) {
        if (availableConstructors == null || availableConstructors.isEmpty()) {
            return List.of();
        }
        List<ConstructorMetadata> constructors = availableConstructors.get(simpleType);
        if (constructors != null) {
            return constructors;
        }
        for (Map.Entry<String, List<ConstructorMetadata>> entry : availableConstructors.entrySet()) {
            if (simpleType.equals(simpleName(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return List.of();
    }

    private boolean isIntrinsicConstructibleType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        return switch (type) {
            case "String", "int", "Integer", "long", "Long", "double", "Double",
                 "float", "Float", "boolean", "Boolean", "byte", "Byte", "short",
                 "Short", "char", "Character" -> true;
            default -> false;
        };
    }

    public Map<String, Object> buildTestTargetBlock(Analyze.TestTargetContext targetContext) {
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

    public List<String> determineHints(InstructionContext context) {
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

    public List<String> determineForbiddenDirectMockTargets(InstructionContext context) {
        LinkedHashSet<String> forbidden = new LinkedHashSet<>();
        if (context == null || context.analysis() == null) {
            return List.of();
        }
        for (DependencyInfo dependency : context.analysis().dependencies()) {
            if (dependency == null || dependency.mockType() != MockType.CONSTRUCTOR) {
                continue;
            }
            String variableName = defaultString(dependency.variableName());
            if (!variableName.isBlank()) {
                forbidden.add(variableName);
            }
        }
        return new ArrayList<>(forbidden);
    }

    public List<String> filterAccessibleFields(Set<String> accessibleFields) {
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

    private String lowerCamel(String value) {
        String text = defaultString(value);
        if (text.isEmpty()) {
            return "";
        }
        if (text.length() == 1) {
            return text.toLowerCase(Locale.ROOT);
        }
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    private String defaultString(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}
