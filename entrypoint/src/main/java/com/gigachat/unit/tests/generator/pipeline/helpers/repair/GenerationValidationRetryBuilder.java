package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.pipeline.InvalidLLMResponseException;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.resources.GenerationPatternCatalog;
import com.gigachat.unit.tests.generator.resources.GenerationValidationPattern;
import com.gigachat.unit.tests.generator.resources.StateModelCatalog;
import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.testagent.entrypoint.pipeline.helpers.analyze.DependencyInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.MockType;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Builds bounded retry context after generation validation rejects an LLM response.
 */
public class GenerationValidationRetryBuilder {

    private final PipelineLogger logger;
    private final GenerationPatternCatalog generationPatternCatalog;
    private final StateModelCatalog stateModelCatalog;
    private final Function<Analyze.AnalysisSummary, List<String>> targetConstructionConstraintProvider;

    public GenerationValidationRetryBuilder(PipelineLogger logger,
                                            GenerationPatternCatalog generationPatternCatalog,
                                            StateModelCatalog stateModelCatalog,
                                            Function<Analyze.AnalysisSummary, List<String>> targetConstructionConstraintProvider) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.generationPatternCatalog = Objects.requireNonNull(generationPatternCatalog, "generationPatternCatalog");
        this.stateModelCatalog = Objects.requireNonNull(stateModelCatalog, "stateModelCatalog");
        this.targetConstructionConstraintProvider = Objects.requireNonNull(targetConstructionConstraintProvider, "targetConstructionConstraintProvider");
    }

    public boolean shouldRetry(InvalidLLMResponseException exception) {
        if (exception == null) {
            return false;
        }
        String message = exception.getMessage();
        return message != null
                && (message.contains("E102")
                || message.contains("E103")
                || message.contains("E104")
                || message.contains("E_PARSE")
                || generationPatternCatalog.matchValidationPattern(message).isPresent());
    }

    public JSONObject buildRetryContext(JSONObject baseContext,
                                        InvalidLLMResponseException exception,
                                        Analyze.AnalysisSummary analysisSummary,
                                        GeneratedTestSnippet snippet) {
        JSONObject retryContext = baseContext == null ? new JSONObject() : new JSONObject(baseContext.toString());
        appendRetryHint(retryContext);
        appendValidationRetryHints(retryContext, exception, analysisSummary, snippet);
        appendInvalidSnippetFeedback(retryContext, exception, snippet);
        return retryContext;
    }

    private void appendRetryHint(JSONObject contextJson) {
        final String hint = "Skip unreachable or undefined constructors.";
        JSONArray hints = contextJson.optJSONArray("hints");
        if (hints == null) {
            hints = new JSONArray();
            contextJson.put("hints", hints);
        }
        addRetryConstraint(hints, hint);
    }

    private void appendValidationRetryHints(JSONObject contextJson,
                                            InvalidLLMResponseException exception,
                                            Analyze.AnalysisSummary analysisSummary,
                                            GeneratedTestSnippet snippet) {
        if (contextJson == null || exception == null) {
            return;
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return;
        }
        Optional<GenerationValidationPattern> matchedPattern = generationPatternCatalog.matchValidationPattern(message);
        if (matchedPattern.isEmpty()) {
            return;
        }
        JSONArray retryConstraints = contextJson.optJSONArray("retryConstraints");
        if (retryConstraints == null) {
            retryConstraints = new JSONArray();
            contextJson.put("retryConstraints", retryConstraints);
        }
        for (String constraint : matchedPattern.get().retryConstraints()) {
            addRetryConstraint(retryConstraints, constraint);
        }
        for (String constraint : buildDynamicRetryConstraints(matchedPattern.get(), analysisSummary, message, snippet)) {
            addRetryConstraint(retryConstraints, constraint);
        }
        appendGenerationPatternHint(contextJson, matchedPattern.get());
        appendRetryStateModel(contextJson, matchedPattern.get().errorCode(), analysisSummary);
    }

    private void appendGenerationPatternHint(JSONObject contextJson, GenerationValidationPattern pattern) {
        if (contextJson == null || pattern == null || pattern.llmHeuristic() == null || pattern.llmHeuristic().isBlank()) {
            return;
        }
        JSONArray hints = contextJson.optJSONArray("hints");
        if (hints == null) {
            hints = new JSONArray();
            contextJson.put("hints", hints);
        }
        addRetryConstraint(hints, pattern.llmHeuristic());
    }

    private void appendRetryStateModel(JSONObject contextJson,
                                       String errorCode,
                                       Analyze.AnalysisSummary analysisSummary) {
        if (contextJson == null || errorCode == null || errorCode.isBlank()) {
            return;
        }
        JSONObject stateModel = stateModelCatalog.generationValidationState(errorCode);
        if (stateModel == null || stateModel.isEmpty()) {
            return;
        }
        if ("E107".equals(errorCode)) {
            Analyze.TestTargetContext targetContext = analysisSummary == null ? null : analysisSummary.testTargetContext();
            if (targetContext != null && targetContext.className() != null) {
                stateModel.put("targetClass", simpleName(targetContext.className()));
            }
        }
        contextJson.put("stateModel", stateModel);
    }

    private void appendInvalidSnippetFeedback(JSONObject contextJson,
                                              InvalidLLMResponseException exception,
                                              GeneratedTestSnippet snippet) {
        if (contextJson == null || exception == null || snippet == null) {
            return;
        }
        JSONObject retryValidation = new JSONObject();
        String reason = exception.getMessage();
        if (reason != null && !reason.isBlank()) {
            retryValidation.put("reason", reason.trim());
            if (reason.contains("E_PARSE")) {
                JSONArray retryConstraints = contextJson.optJSONArray("retryConstraints");
                if (retryConstraints == null) {
                    retryConstraints = new JSONArray();
                    contextJson.put("retryConstraints", retryConstraints);
                }
                addRetryConstraint(retryConstraints, "Return only valid Java test code, without prose or explanatory comments outside Java comments.");
                addRetryConstraint(retryConstraints, "Every import must be a complete Java import, for example import java.util.List; or import static org.mockito.Mockito.*;");
                addRetryConstraint(retryConstraints, "Do not emit comment-only imports like import // Corrected import; omit the import instead.");
            }
        }
        String source = snippet.fullClassSource();
        if (source == null || source.isBlank()) {
            source = snippet.methodBody();
        }
        if (source != null && !source.isBlank()) {
            retryValidation.put("invalidSnippetExcerpt", abbreviate(source.replaceAll("\\s+", " ").trim(), 320));
        }
        if (!retryValidation.isEmpty()) {
            contextJson.put("retryValidation", retryValidation);
        }
    }

    private List<String> buildDynamicRetryConstraints(GenerationValidationPattern pattern,
                                                      Analyze.AnalysisSummary analysisSummary,
                                                      String validationMessage,
                                                      GeneratedTestSnippet snippet) {
        if (pattern == null || pattern.dynamicConstraintBuilders() == null || pattern.dynamicConstraintBuilders().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> constraints = new LinkedHashSet<>();
        for (String builder : pattern.dynamicConstraintBuilders()) {
            if (builder == null || builder.isBlank()) {
                continue;
            }
            switch (builder) {
                case "CONSTRUCTOR_LOCAL_RETRY_CONSTRAINTS" ->
                        constraints.addAll(buildConstructorLocalRetryConstraints(analysisSummary));
                case "TARGET_CONSTRUCTION_RETRY_CONSTRAINTS" ->
                        constraints.addAll(targetConstructionConstraintProvider.apply(analysisSummary));
                case "SOURCE_DERIVED_AVAILABLE_METHOD_RETRY_CONSTRAINTS" ->
                        constraints.addAll(buildAvailableMethodRetryConstraints(analysisSummary, validationMessage));
                case "SOURCE_DERIVED_SNIPPET_TYPE_RETRY_CONSTRAINTS" ->
                        constraints.addAll(buildSnippetTypeRetryConstraints(analysisSummary, snippet));
                case "SOURCE_DERIVED_CONSTRUCTOR_PATH_RETRY_CONSTRAINTS" ->
                        constraints.addAll(buildSnippetConstructorPathRetryConstraints(analysisSummary, snippet));
                default -> logger.warn("Unknown generation pattern dynamic constraint builder: " + builder);
            }
        }
        return List.copyOf(constraints);
    }

    private List<String> buildConstructorLocalRetryConstraints(Analyze.AnalysisSummary analysisSummary) {
        if (analysisSummary == null || analysisSummary.methodAnalysis() == null) {
            return List.of();
        }
        LinkedHashSet<String> constraints = new LinkedHashSet<>();
        for (DependencyInfo dependency : analysisSummary.methodAnalysis().dependencies()) {
            if (dependency == null || dependency.mockType() != MockType.CONSTRUCTOR) {
                continue;
            }
            String variableName = normalise(dependency.variableName());
            String className = simpleName(dependency.className());
            String constructorContext = normalise(dependency.context());
            List<String> collaboratorNames = extractConstructorArgumentNames(constructorContext);
            if (!variableName.isBlank()) {
                constraints.add("The constructor-created local object \"" + variableName
                        + "\" must never appear inside Mockito.when(...), doReturn(...).when(...), spy(...), or verify(...).");
            }
            if (!className.isBlank()) {
                constraints.add("Do not write when(new " + className + "(... ).method()), doReturn(...).when(new "
                        + className + "(...)), or spy(new " + className + "(...)) in the generated test.");
            }
            if (!variableName.isBlank() && !collaboratorNames.isEmpty()) {
                constraints.add("For constructor-created local object \"" + variableName
                        + "\" built from " + constructorContext
                        + ", drive behaviour only through constructor-argument collaborators: "
                        + String.join(", ", collaboratorNames) + '.');
            }
        }
        return List.copyOf(constraints);
    }

    private void addRetryConstraint(JSONArray retryConstraints, String constraint) {
        if (retryConstraints == null || constraint == null) {
            return;
        }
        String trimmed = constraint.trim();
        if (trimmed.isBlank()) {
            return;
        }
        for (int index = 0; index < retryConstraints.length(); index++) {
            if (trimmed.equalsIgnoreCase(retryConstraints.optString(index))) {
                return;
            }
        }
        retryConstraints.put(trimmed);
    }

    private List<String> extractConstructorArgumentNames(String constructorContext) {
        String context = normalise(constructorContext);
        int openParen = context.indexOf('(');
        int closeParen = context.lastIndexOf(')');
        if (openParen < 0 || closeParen <= openParen) {
            return List.of();
        }
        String argumentsSection = context.substring(openParen + 1, closeParen).trim();
        if (argumentsSection.isEmpty()) {
            return List.of();
        }
        List<String> identifiers = new java.util.ArrayList<>();
        for (String rawArgument : argumentsSection.split(",")) {
            String candidate = normalise(rawArgument).replace("this.", "").trim();
            if (candidate.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                identifiers.add(candidate);
            }
        }
        return List.copyOf(identifiers);
    }

    private List<String> buildAvailableMethodRetryConstraints(Analyze.AnalysisSummary analysisSummary,
                                                              String validationMessage) {
        if (analysisSummary == null || analysisSummary.availableMethods() == null || analysisSummary.availableMethods().isEmpty()) {
            return List.of();
        }
        return buildAvailableMethodRetryConstraintsForTypes(analysisSummary, extractInventedTargetTypes(validationMessage));
    }

    private List<String> buildSnippetTypeRetryConstraints(Analyze.AnalysisSummary analysisSummary,
                                                          GeneratedTestSnippet snippet) {
        if (analysisSummary == null || snippet == null) {
            return List.of();
        }
        String source = snippet.fullClassSource();
        if (source == null || source.isBlank()) {
            source = snippet.methodBody();
        }
        String normalizedSource = normalise(source);
        if (normalizedSource.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> constraints = new LinkedHashSet<>();
        LinkedHashSet<String> referencedTypes = new LinkedHashSet<>();
        LinkedHashSet<String> candidateTypes = new LinkedHashSet<>();
        if (analysisSummary.availableMethods() != null) {
            candidateTypes.addAll(analysisSummary.availableMethods().keySet());
        }
        if (analysisSummary.availableConstructors() != null) {
            candidateTypes.addAll(analysisSummary.availableConstructors().keySet());
        }
        for (String candidateType : candidateTypes) {
            String typeName = simpleName(candidateType);
            if (typeName.isBlank()) {
                continue;
            }
            if (normalizedSource.matches("(?s).*\\b" + java.util.regex.Pattern.quote(typeName) + "\\b.*")) {
                referencedTypes.add(typeName);
            }
        }
        return buildAvailableMethodRetryConstraintsForTypes(analysisSummary, referencedTypes);
    }

    private List<String> buildSnippetConstructorPathRetryConstraints(Analyze.AnalysisSummary analysisSummary,
                                                                     GeneratedTestSnippet snippet) {
        if (analysisSummary == null || snippet == null || analysisSummary.availableConstructors() == null || analysisSummary.availableConstructors().isEmpty()) {
            return List.of();
        }
        String source = snippet.fullClassSource();
        if (source == null || source.isBlank()) {
            source = snippet.methodBody();
        }
        String normalizedSource = normalise(source);
        if (normalizedSource.isBlank()) {
            return List.of();
        }
        Map<String, List<String>> mockIdentifiersByType = buildMockIdentifiersByType(analysisSummary);
        LinkedHashSet<String> fallbackMockCandidates = resolveFallbackMockCandidates(analysisSummary);
        LinkedHashSet<String> constraints = new LinkedHashSet<>();
        for (String candidateType : analysisSummary.availableConstructors().keySet()) {
            String typeName = simpleName(candidateType);
            if (typeName.isBlank() || !normalizedSource.matches("(?s).*\\b" + java.util.regex.Pattern.quote(typeName) + "\\b.*")) {
                continue;
            }
            List<ConstructorMetadata> constructors = analysisSummary.availableConstructors().getOrDefault(candidateType, List.of());
            String constructorSummary = constructors.stream()
                    .filter(Objects::nonNull)
                    .map(ConstructorMetadata::signature)
                    .filter(signature -> signature != null && !signature.isBlank())
                    .distinct()
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            if (containsMissingNoArgConstruction(normalizedSource, typeName) && constructors.stream().noneMatch(this::isZeroArgConstructor)) {
                constraints.add("Do not call " + typeName + "() because that constructor is not listed in availableConstructors."
                        + (constructorSummary.isBlank() ? "" : " Use only: " + constructorSummary + '.'));
            }
            if (containsAnyConstruction(normalizedSource, typeName)) {
                List<String> mockIdentifiers = mockIdentifiersByType.getOrDefault(typeName, List.of());
                if (!mockIdentifiers.isEmpty()) {
                    constraints.add("Type \"" + typeName + "\" is a planned Mockito mock for this test; reuse mock identifier(s) "
                            + String.join(", ", mockIdentifiers) + " and inject them instead of constructing a real " + typeName + '.');
                }
                if (containsConstructorNullLiteral(normalizedSource, typeName)) {
                    if (fallbackMockCandidates.contains(typeName)) {
                        constraints.add("Type \"" + typeName + "\" has no legal real construction path from availableConstructors in the current context; promote it to a Mockito mock instead of using null-based construction.");
                    } else {
                        constraints.add("Do not pass null literals to constructor arguments of \"" + typeName
                                + "\"; use a listed non-null constructor path instead."
                                + (constructorSummary.isBlank() ? "" : " Allowed constructors: " + constructorSummary + '.'));
                    }
                } else if (fallbackMockCandidates.contains(typeName) && mockIdentifiers.isEmpty()) {
                    constraints.add("If \"" + typeName + "\" is required for SUT construction and no legal real construction path exists in availableConstructors, declare it as a Mockito mock and inject it instead of inventing a real collaborator graph.");
                }
            }
        }
        return List.copyOf(constraints);
    }

    private List<String> buildAvailableMethodRetryConstraintsForTypes(Analyze.AnalysisSummary analysisSummary,
                                                                      java.util.Collection<String> typeNames) {
        if (analysisSummary == null || typeNames == null || typeNames.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> constraints = new LinkedHashSet<>();
        for (String typeName : typeNames) {
            List<String> methods = analysisSummary.availableMethods().getOrDefault(typeName, List.of());
            List<ConstructorMetadata> constructors = analysisSummary.availableConstructors().getOrDefault(typeName, List.of());
            if (methods.isEmpty() && constructors.isEmpty()) {
                continue;
            }
            List<String> stateDrivers = methods.stream()
                    .filter(Objects::nonNull)
                    .map(this::signatureToMethodName)
                    .filter(name -> !name.isBlank())
                    .filter(name -> !name.startsWith("get") && !name.startsWith("is"))
                    .distinct()
                    .toList();
            if (!constructors.isEmpty()) {
                String constructorSummary = constructors.stream()
                        .filter(Objects::nonNull)
                        .map(ConstructorMetadata::signature)
                        .filter(signature -> signature != null && !signature.isBlank())
                        .distinct()
                        .reduce((left, right) -> left + ", " + right)
                        .orElse("");
                if (!constructorSummary.isBlank()) {
                    constraints.add("For type \"" + typeName + "\" use only listed constructors from availableConstructors: "
                            + constructorSummary + '.');
                }
            }
            if (!methods.isEmpty()) {
                constraints.add("For type \"" + typeName + "\" use only listed public methods from availableMethods: "
                        + String.join(", ", methods) + '.');
            }
            if (!stateDrivers.isEmpty()) {
                constraints.add("When driving \"" + typeName + "\" state for this fixture, use only these public state methods: "
                        + String.join(", ", stateDrivers) + '.');
            }
        }
        return List.copyOf(constraints);
    }

    private List<String> extractInventedTargetTypes(String validationMessage) {
        if (validationMessage == null || validationMessage.isBlank()) {
            return List.of();
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("Invented method\\s+([A-Za-z0-9_$.]+)\\.").matcher(validationMessage);
        LinkedHashSet<String> types = new LinkedHashSet<>();
        while (matcher.find()) {
            String typeName = matcher.group(1);
            if (typeName != null && !typeName.isBlank()) {
                types.add(simpleName(typeName));
            }
        }
        matcher = java.util.regex.Pattern.compile("Missing constructor metadata for\\s+([A-Za-z0-9_$.]+)\\(").matcher(validationMessage);
        while (matcher.find()) {
            String typeName = matcher.group(1);
            if (typeName != null && !typeName.isBlank()) {
                types.add(simpleName(typeName));
            }
        }
        return List.copyOf(types);
    }

    private String signatureToMethodName(String signature) {
        String normalized = normalise(signature);
        int openParen = normalized.indexOf('(');
        if (openParen < 0) {
            return normalized;
        }
        int separator = normalized.lastIndexOf(' ', openParen);
        if (separator < 0 || separator + 1 >= openParen) {
            return normalized.substring(0, openParen).trim();
        }
        return normalized.substring(separator + 1, openParen).trim();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, Math.max(0, maxLength));
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private String normalise(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String simpleName(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String candidate = type.trim();
        int genericStart = candidate.indexOf('<');
        if (genericStart >= 0) {
            candidate = candidate.substring(0, genericStart);
        }
        int bracketIndex = candidate.indexOf('[');
        if (bracketIndex >= 0) {
            candidate = candidate.substring(0, bracketIndex);
        }
        int dot = candidate.lastIndexOf('.');
        if (dot >= 0 && dot < candidate.length() - 1) {
            candidate = candidate.substring(dot + 1);
        }
        return candidate.trim();
    }

    private Map<String, List<String>> buildMockIdentifiersByType(Analyze.AnalysisSummary analysisSummary) {
        if (analysisSummary == null || analysisSummary.mockPlan() == null) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, List<String>> identifiersByType = new java.util.LinkedHashMap<>();
        if (analysisSummary.mockPlan().targets() != null) {
            for (com.gigachat.unit.tests.generator.dto.MockTarget target : analysisSummary.mockPlan().targets()) {
                if (target == null) {
                    continue;
                }
                String type = simpleName(target.qualifiedType());
                String identifier = normalise(target.identifier());
                if (type.isBlank() || identifier.isBlank()) {
                    continue;
                }
                identifiersByType.computeIfAbsent(type, key -> new java.util.ArrayList<>()).add(identifier);
            }
        }
        if (analysisSummary.mockPlan().shouldMock() != null) {
            for (String identifier : analysisSummary.mockPlan().shouldMock()) {
                String normalizedIdentifier = normalise(identifier);
                if (normalizedIdentifier.isBlank()) {
                    continue;
                }
                String inferredType = upperCamel(normalizedIdentifier);
                identifiersByType.computeIfAbsent(inferredType, key -> new java.util.ArrayList<>()).add(normalizedIdentifier);
            }
        }
        return Map.copyOf(identifiersByType);
    }

    private LinkedHashSet<String> resolveFallbackMockCandidates(Analyze.AnalysisSummary analysisSummary) {
        LinkedHashSet<String> fallbackMockCandidates = new LinkedHashSet<>();
        if (analysisSummary == null
                || analysisSummary.testTargetContext() == null
                || analysisSummary.mockPlan() == null
                || analysisSummary.availableConstructors() == null
                || analysisSummary.availableConstructors().isEmpty()) {
            return fallbackMockCandidates;
        }
        String targetClass = simpleName(analysisSummary.testTargetContext().className());
        if (targetClass.isBlank()) {
            return fallbackMockCandidates;
        }
        LinkedHashSet<String> shouldMock = new LinkedHashSet<>(analysisSummary.mockPlan().shouldMock());
        for (Map.Entry<String, List<ConstructorMetadata>> entry : analysisSummary.availableConstructors().entrySet()) {
            if (!targetClass.equals(simpleName(entry.getKey()))) {
                continue;
            }
            for (ConstructorMetadata metadata : entry.getValue()) {
                if (metadata == null || metadata.parameters() == null || metadata.parameters().isEmpty()) {
                    continue;
                }
                boolean constructorUsesMocks = metadata.parameters().stream()
                        .filter(Objects::nonNull)
                        .anyMatch(parameter -> shouldMock.contains(normalise(parameter.name()))
                                || shouldMock.contains(lowerCamel(simpleName(parameter.type()))));
                if (!constructorUsesMocks) {
                    continue;
                }
                for (ParameterMetadata parameter : metadata.parameters()) {
                    if (parameter == null) {
                        continue;
                    }
                    String parameterName = normalise(parameter.name());
                    String parameterType = simpleName(parameter.type());
                    if (shouldMock.contains(parameterName) || shouldMock.contains(lowerCamel(parameterType))) {
                        continue;
                    }
                    if (!isConstructibleFromAvailableConstructors(parameterType, analysisSummary.availableConstructors(), new LinkedHashSet<>())) {
                        fallbackMockCandidates.add(parameterType);
                    }
                }
            }
        }
        return fallbackMockCandidates;
    }

    private boolean isConstructibleFromAvailableConstructors(String type,
                                                             Map<String, List<ConstructorMetadata>> availableConstructors,
                                                             java.util.Set<String> visiting) {
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
                if (isZeroArgConstructor(constructor)) {
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

    private boolean isZeroArgConstructor(ConstructorMetadata constructor) {
        return constructor != null && (constructor.parameters() == null || constructor.parameters().isEmpty());
    }

    private boolean containsAnyConstruction(String source, String typeName) {
        return source.matches("(?s).*new\\s+" + java.util.regex.Pattern.quote(typeName) + "\\s*\\(.*");
    }

    private boolean containsMissingNoArgConstruction(String source, String typeName) {
        return source.matches("(?s).*new\\s+" + java.util.regex.Pattern.quote(typeName) + "\\s*\\(\\s*\\).*");
    }

    private boolean containsConstructorNullLiteral(String source, String typeName) {
        return source.matches("(?s).*new\\s+" + java.util.regex.Pattern.quote(typeName) + "\\s*\\([^)]*\\bnull\\b[^)]*\\).*");
    }

    private String lowerCamel(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() == 1) {
            return trimmed.toLowerCase(java.util.Locale.ROOT);
        }
        return trimmed.substring(0, 1).toLowerCase(java.util.Locale.ROOT) + trimmed.substring(1);
    }

    private String upperCamel(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() == 1) {
            return trimmed.toUpperCase(java.util.Locale.ROOT);
        }
        return trimmed.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + trimmed.substring(1);
    }
}
