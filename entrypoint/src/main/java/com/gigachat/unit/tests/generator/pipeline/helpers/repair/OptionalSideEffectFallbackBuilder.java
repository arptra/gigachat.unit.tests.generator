package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds a deterministic present-branch test for methods that load an Optional domain object,
 * mutate it inside ifPresent(...), emit collaborator side effects, and return isPresent().
 */
public final class OptionalSideEffectFallbackBuilder {

    private final PipelineLogger logger;

    public OptionalSideEffectFallbackBuilder(PipelineLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public GeneratedTestSnippet buildFastPath(TestClassInfo classInfo,
                                              TestMethodInfo methodInfo,
                                              Analyze.AnalysisSummary analysisSummary,
                                              JSONObject contextJson) {
        FallbackPlan plan = buildPlan(classInfo, methodInfo, analysisSummary, contextJson);
        return plan == null ? null : render(plan, classInfo, null);
    }

    private FallbackPlan buildPlan(TestClassInfo classInfo,
                                   TestMethodInfo methodInfo,
                                   Analyze.AnalysisSummary analysisSummary,
                                   JSONObject contextJson) {
        if (classInfo == null || methodInfo == null || analysisSummary == null) {
            return null;
        }
        MethodDeclaration targetMethod = resolveDeclaration(methodInfo);
        if (targetMethod == null || !"boolean".equals(targetMethod.getType().asString())) {
            return null;
        }
        Optional<OptionalScenario> scenario = findOptionalScenario(targetMethod, analysisSummary);
        if (scenario.isEmpty()) {
            return null;
        }
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        if (targetContext == null || targetContext.isStatic() || !targetContext.requiresInstance()) {
            return null;
        }
        ConstructorMetadata targetConstructor = selectTargetConstructor(analysisSummary, targetContext.className());
        if (targetConstructor == null || targetConstructor.parameters().isEmpty()) {
            return null;
        }
        Map<String, String> importLookup = buildImportLookup(classInfo, contextJson);
        LinkedHashSet<String> imports = new LinkedHashSet<>();
        imports.add("org.junit.jupiter.api.Test");
        addContextImports(imports, classInfo, contextJson);

        LinkedHashSet<String> mockNames = new LinkedHashSet<>();
        List<String> setupLines = new ArrayList<>();
        List<String> constructorArguments = new ArrayList<>();
        for (ParameterMetadata parameter : targetConstructor.parameters()) {
            String variableName = variableName(parameter.name(), simpleName(parameter.type()));
            String typeName = renderType(parameter.type(), importLookup);
            if (typeName.isBlank()) {
                return null;
            }
            setupLines.add(typeName + " " + variableName + " = mock(" + typeName + ".class);");
            constructorArguments.add(variableName);
            mockNames.add(variableName);
        }
        Set<String> shouldMock = analysisSummary.mockPlan() == null
                ? Set.of()
                : new LinkedHashSet<>(analysisSummary.mockPlan().shouldMock());
        if (!shouldMock.contains(scenario.get().providerTarget())) {
            return null;
        }
        if (!mockNames.contains(scenario.get().providerTarget())) {
            return null;
        }

        List<ValueBinding> methodArguments = buildMethodArguments(targetMethod, analysisSummary, importLookup, imports);
        if (methodArguments == null || methodArguments.isEmpty() != targetMethod.getParameters().isEmpty()) {
            return null;
        }
        Map<String, String> availableNames = new LinkedHashMap<>();
        for (String mockName : mockNames) {
            availableNames.put(mockName, mockName);
        }
        for (ValueBinding binding : methodArguments) {
            availableNames.put(binding.name(), binding.name());
        }

        String domainType = renderType(scenario.get().domainType(), importLookup);
        ConstructorMetadata domainConstructor = selectConstructorForType(analysisSummary, scenario.get().domainType());
        if (domainType.isBlank() || domainConstructor == null) {
            return null;
        }
        String domainVariable = uniqueName(lowerCamel(domainType), availableNames.keySet());
        DomainBinding domainBinding = buildDomainBinding(domainType,
                domainVariable,
                domainConstructor,
                methodArguments,
                importLookup,
                imports);
        if (domainBinding == null) {
            return null;
        }
        availableNames.put(domainVariable, domainVariable);

        List<String> verificationLines = buildVerificationLines(scenario.get(), shouldMock, availableNames, domainVariable);
        if (verificationLines.isEmpty()) {
            return null;
        }
        List<String> stateAssertions = buildStateAssertions(scenario.get(), analysisSummary, domainVariable);
        String targetClass = renderType(resolveTargetClassName(classInfo, analysisSummary, contextJson), importLookup);
        String instanceName = targetContext.instanceName() == null || targetContext.instanceName().isBlank()
                ? lowerCamel(simpleName(targetClass))
                : targetContext.instanceName();
        setupLines.add(targetClass + " " + instanceName + " = new " + targetClass + "(" + String.join(", ", constructorArguments) + ");");
        setupLines.add(domainBinding.declarationLine());
        setupLines.add(buildOptionalStubLine(scenario.get(), domainVariable));

        imports.add("java.util.Optional");
        imports.add("static org.mockito.Mockito.mock");
        imports.add("static org.mockito.Mockito.verify");
        imports.add("static org.mockito.Mockito.when");
        imports.add("static org.junit.jupiter.api.Assertions.assertEquals");

        return new FallbackPlan(
                resolveClassName(classInfo, null),
                methodName(targetMethod.getNameAsString(), scenario.get().providerMethod()),
                targetMethod.getNameAsString(),
                instanceName,
                setupLines,
                methodArguments,
                stateAssertions,
                verificationLines,
                List.copyOf(imports));
    }

    private Optional<OptionalScenario> findOptionalScenario(MethodDeclaration targetMethod,
                                                            Analyze.AnalysisSummary analysisSummary) {
        Set<String> shouldMock = analysisSummary.mockPlan() == null
                ? Set.of()
                : new LinkedHashSet<>(analysisSummary.mockPlan().shouldMock());
        for (VariableDeclarator variable : targetMethod.findAll(VariableDeclarator.class)) {
            String optionalType = variable.getType().asString();
            if (!optionalType.startsWith("Optional<") || variable.getInitializer().isEmpty()) {
                continue;
            }
            Expression initializer = variable.getInitializer().get();
            if (!initializer.isMethodCallExpr()) {
                continue;
            }
            MethodCallExpr providerCall = initializer.asMethodCallExpr();
            String providerTarget = resolveRootScopeIdentifier(providerCall);
            if (providerTarget.isBlank() || !shouldMock.contains(providerTarget)) {
                continue;
            }
            String optionalVariable = variable.getNameAsString();
            Optional<LambdaExpr> ifPresentLambda = findIfPresentLambda(targetMethod, optionalVariable);
            if (ifPresentLambda.isEmpty()) {
                continue;
            }
            if (!returnsIsPresent(targetMethod, optionalVariable)) {
                continue;
            }
            String lambdaParameter = ifPresentLambda.get().getParameter(0).getNameAsString();
            BlockStmt lambdaBlock = unwrapLambdaBody(ifPresentLambda.get());
            if (lambdaBlock == null) {
                continue;
            }
            String domainType = optionalType.substring("Optional<".length(), optionalType.length() - 1).trim();
            List<MethodCallExpr> domainMutations = lambdaBlock.findAll(MethodCallExpr.class).stream()
                    .filter(call -> lambdaParameter.equals(resolveRootScopeIdentifier(call)))
                    .filter(call -> call.getArguments().isEmpty())
                    .toList();
            if (domainMutations.isEmpty()) {
                continue;
            }
            List<MethodCallExpr> sideEffects = lambdaBlock.findAll(MethodCallExpr.class).stream()
                    .filter(call -> shouldMock.contains(resolveRootScopeIdentifier(call)))
                    .toList();
            if (sideEffects.isEmpty()) {
                continue;
            }
            return Optional.of(new OptionalScenario(optionalVariable,
                    domainType,
                    providerTarget,
                    providerCall.getNameAsString(),
                    providerCall.getArguments().stream().map(Expression::toString).toList(),
                    lambdaParameter,
                    domainMutations,
                    sideEffects));
        }
        return Optional.empty();
    }

    private Optional<LambdaExpr> findIfPresentLambda(MethodDeclaration targetMethod, String optionalVariable) {
        return targetMethod.findAll(MethodCallExpr.class).stream()
                .filter(call -> "ifPresent".equals(call.getNameAsString()))
                .filter(call -> optionalVariable.equals(resolveRootScopeIdentifier(call)))
                .filter(call -> call.getArguments().size() == 1)
                .map(call -> call.getArgument(0))
                .filter(Expression::isLambdaExpr)
                .map(Expression::asLambdaExpr)
                .filter(lambda -> lambda.getParameters().size() == 1)
                .findFirst();
    }

    private boolean returnsIsPresent(MethodDeclaration targetMethod, String optionalVariable) {
        return targetMethod.findAll(ReturnStmt.class).stream()
                .map(ReturnStmt::getExpression)
                .flatMap(Optional::stream)
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .anyMatch(call -> "isPresent".equals(call.getNameAsString())
                        && optionalVariable.equals(resolveRootScopeIdentifier(call)));
    }

    private BlockStmt unwrapLambdaBody(LambdaExpr lambdaExpr) {
        if (lambdaExpr == null) {
            return null;
        }
        Statement body = lambdaExpr.getBody();
        if (body.isBlockStmt()) {
            return body.asBlockStmt();
        }
        if (body.isExpressionStmt()) {
            BlockStmt block = new BlockStmt();
            block.addStatement(body.clone());
            return block;
        }
        return null;
    }

    private List<ValueBinding> buildMethodArguments(MethodDeclaration targetMethod,
                                                    Analyze.AnalysisSummary analysisSummary,
                                                    Map<String, String> importLookup,
                                                    Set<String> imports) {
        List<ValueBinding> bindings = new ArrayList<>();
        for (Parameter parameter : targetMethod.getParameters()) {
            String name = parameter.getNameAsString();
            String type = parameter.getType().asString();
            String declaration = declarationForParameter(name, type, analysisSummary, importLookup, imports);
            if (declaration == null || declaration.isBlank()) {
                return null;
            }
            bindings.add(new ValueBinding(name, type, declaration));
        }
        return bindings;
    }

    private DomainBinding buildDomainBinding(String domainType,
                                             String domainVariable,
                                             ConstructorMetadata constructor,
                                             List<ValueBinding> methodArguments,
                                             Map<String, String> importLookup,
                                             Set<String> imports) {
        List<String> arguments = new ArrayList<>();
        for (ParameterMetadata parameter : constructor.parameters()) {
            String argument = matchingArgument(parameter, methodArguments);
            if (argument == null) {
                argument = literalFor(simpleName(parameter.type()), parameter.name());
            }
            if (argument == null) {
                return null;
            }
            arguments.add(argument);
        }
        String fqcn = importLookup.get(simpleName(domainType));
        if (fqcn != null && !fqcn.isBlank()) {
            imports.add(fqcn);
        }
        return new DomainBinding(domainVariable,
                domainType + " " + domainVariable + " = new " + domainType + "(" + String.join(", ", arguments) + ");");
    }

    private String matchingArgument(ParameterMetadata constructorParameter, List<ValueBinding> methodArguments) {
        if (constructorParameter == null || methodArguments == null) {
            return null;
        }
        String parameterName = constructorParameter.name() == null ? "" : constructorParameter.name().toLowerCase(Locale.ROOT);
        String parameterType = simpleName(constructorParameter.type());
        for (ValueBinding binding : methodArguments) {
            if (!parameterType.equals(simpleName(binding.type()))) {
                continue;
            }
            String methodName = binding.name().toLowerCase(Locale.ROOT);
            if (methodName.equals(parameterName) || parameterName.contains(methodName) || methodName.contains(parameterName)) {
                return binding.name();
            }
        }
        return null;
    }

    private List<String> buildVerificationLines(OptionalScenario scenario,
                                                Set<String> shouldMock,
                                                Map<String, String> availableNames,
                                                String domainVariable) {
        LinkedHashSet<String> verifications = new LinkedHashSet<>();
        for (MethodCallExpr call : scenario.sideEffectCalls()) {
            String root = resolveRootScopeIdentifier(call);
            if (root.isBlank() || !shouldMock.contains(root)) {
                continue;
            }
            List<String> arguments = new ArrayList<>();
            boolean supported = true;
            for (Expression argument : call.getArguments()) {
                String rendered = renderArgument(argument, scenario.lambdaParameter(), domainVariable, availableNames.keySet());
                if (rendered.isBlank()) {
                    supported = false;
                    break;
                }
                arguments.add(rendered);
            }
            if (supported) {
                verifications.add("verify(" + root + ")." + call.getNameAsString() + "(" + String.join(", ", arguments) + ");");
            }
        }
        return List.copyOf(verifications);
    }

    private List<String> buildStateAssertions(OptionalScenario scenario,
                                              Analyze.AnalysisSummary analysisSummary,
                                              String domainVariable) {
        LinkedHashSet<String> assertions = new LinkedHashSet<>();
        Set<String> availableMethods = availableMethodsForType(analysisSummary, scenario.domainType());
        for (MethodCallExpr mutation : scenario.domainMutations()) {
            String mutationName = mutation.getNameAsString();
            if (isNegativeActivationMutation(mutationName) && availableMethods.contains("boolean isActive()")) {
                assertions.add("assertEquals(false, " + domainVariable + ".isActive());");
            } else if (isPositiveActivationMutation(mutationName) && availableMethods.contains("boolean isActive()")) {
                assertions.add("assertEquals(true, " + domainVariable + ".isActive());");
            }
        }
        return List.copyOf(assertions);
    }

    private String buildOptionalStubLine(OptionalScenario scenario, String domainVariable) {
        return "when(" + scenario.providerTarget() + "." + scenario.providerMethod()
                + "(" + String.join(", ", scenario.providerArguments()) + "))"
                + ".thenReturn(Optional.of(" + domainVariable + "));";
    }

    private GeneratedTestSnippet render(FallbackPlan plan, TestClassInfo classInfo, GeneratedTestSnippet snippet) {
        List<String> lines = new ArrayList<>();
        lines.add("@Test");
        lines.add("void " + plan.methodName() + "() {");
        for (ValueBinding binding : plan.methodArguments()) {
            lines.add("    " + binding.declarationLine());
        }
        for (String line : plan.setupLines()) {
            lines.add("    " + line);
        }
        lines.add("");
        String invocation = plan.instanceName()
                + "."
                + plan.targetMethodName()
                + "("
                + String.join(", ", plan.methodArguments().stream().map(ValueBinding::name).toList())
                + ")";
        lines.add("    boolean result = " + invocation + ";");
        lines.add("");
        lines.add("    assertEquals(true, result);");
        for (String assertion : plan.stateAssertions()) {
            lines.add("    " + assertion);
        }
        for (String verification : plan.verifyLines()) {
            lines.add("    " + verification);
        }
        lines.add("}");
        logger.info("Built deterministic optional side-effect fallback for " + plan.targetMethodName());
        return new GeneratedTestSnippet(resolveClassName(classInfo, snippet),
                plan.methodName(),
                String.join(System.lineSeparator(), lines),
                plan.imports(),
                List.of(),
                List.of(),
                List.of(),
                "");
    }

    private String renderArgument(Expression argument,
                                  String lambdaParameter,
                                  String domainVariable,
                                  Set<String> availableNames) {
        if (argument == null) {
            return "";
        }
        if (argument.isNameExpr()) {
            String name = argument.asNameExpr().getNameAsString();
            if (lambdaParameter.equals(name)) {
                return domainVariable;
            }
            return availableNames.contains(name) ? name : "";
        }
        if (argument.isStringLiteralExpr()
                || argument.isBooleanLiteralExpr()
                || argument.isIntegerLiteralExpr()
                || argument.isLongLiteralExpr()
                || argument.isDoubleLiteralExpr()
                || argument.isCharLiteralExpr()
                || argument.isNullLiteralExpr()) {
            return argument.toString();
        }
        if (argument.isBinaryExpr()) {
            List<NameExpr> names = argument.findAll(NameExpr.class);
            for (NameExpr name : names) {
                String value = name.getNameAsString();
                if (!availableNames.contains(value) && !lambdaParameter.equals(value)) {
                    return "";
                }
            }
            return replaceStandaloneName(argument.toString(), lambdaParameter, domainVariable);
        }
        if (argument.isMethodCallExpr()) {
            MethodCallExpr methodCall = argument.asMethodCallExpr();
            String root = resolveRootScopeIdentifier(methodCall);
            if (!availableNames.contains(root) && !lambdaParameter.equals(root)) {
                return "";
            }
            return replaceStandaloneName(argument.toString(), lambdaParameter, domainVariable);
        }
        if (argument instanceof EnclosedExpr enclosedExpr) {
            return renderArgument(enclosedExpr.getInner(), lambdaParameter, domainVariable, availableNames);
        }
        return "";
    }

    private String declarationForParameter(String name,
                                           String type,
                                           Analyze.AnalysisSummary analysisSummary,
                                           Map<String, String> importLookup,
                                           Set<String> imports) {
        String simpleType = simpleName(type);
        String renderedType = renderType(type, importLookup);
        String literal = literalFor(simpleType, name);
        if (literal != null) {
            return renderedType + " " + name + " = " + literal + ";";
        }
        Optional<ConstructorMetadata> constructor = constructorsForSimpleName(simpleType, analysisSummary.availableConstructors()).stream()
                .filter(Objects::nonNull)
                .min(Comparator.comparingInt(candidate -> candidate.parameters().size()));
        if (constructor.isEmpty()) {
            return null;
        }
        List<String> args = new ArrayList<>();
        for (ParameterMetadata parameter : constructor.get().parameters()) {
            String arg = literalFor(simpleName(parameter.type()), parameter.name());
            if (arg == null) {
                return null;
            }
            args.add(arg);
        }
        String fqcn = importLookup.get(simpleType);
        if (fqcn != null && !fqcn.isBlank()) {
            imports.add(fqcn);
        }
        return renderedType + " " + name + " = new " + renderedType + "(" + String.join(", ", args) + ");";
    }

    private ConstructorMetadata selectTargetConstructor(Analyze.AnalysisSummary analysisSummary, String targetClass) {
        if (analysisSummary.availableConstructors() == null || analysisSummary.availableConstructors().isEmpty()) {
            return null;
        }
        String targetSimple = simpleName(targetClass);
        return analysisSummary.availableConstructors().entrySet().stream()
                .filter(entry -> targetSimple.equals(simpleName(entry.getKey())))
                .flatMap(entry -> entry.getValue().stream())
                .filter(Objects::nonNull)
                .filter(constructor -> constructor.parameters() != null && !constructor.parameters().isEmpty())
                .max(Comparator.comparingInt(constructor -> constructor.parameters().size()))
                .orElse(null);
    }

    private ConstructorMetadata selectConstructorForType(Analyze.AnalysisSummary analysisSummary, String typeName) {
        if (analysisSummary.availableConstructors() == null || analysisSummary.availableConstructors().isEmpty()) {
            return null;
        }
        String simpleType = simpleName(typeName);
        return constructorsForSimpleName(simpleType, analysisSummary.availableConstructors()).stream()
                .filter(Objects::nonNull)
                .min(Comparator.comparingInt(constructor -> constructor.parameters().size()))
                .orElse(null);
    }

    private Set<String> availableMethodsForType(Analyze.AnalysisSummary analysisSummary, String typeName) {
        if (analysisSummary.availableMethods() == null || analysisSummary.availableMethods().isEmpty()) {
            return Set.of();
        }
        String simpleType = simpleName(typeName);
        List<String> methods = analysisSummary.availableMethods().get(simpleType);
        if (methods != null) {
            return new LinkedHashSet<>(methods);
        }
        return analysisSummary.availableMethods().entrySet().stream()
                .filter(entry -> simpleType.equals(simpleName(entry.getKey())))
                .findFirst()
                .map(entry -> new LinkedHashSet<>(entry.getValue()))
                .orElseGet(LinkedHashSet::new);
    }

    private MethodDeclaration resolveDeclaration(TestMethodInfo methodInfo) {
        if (methodInfo == null) {
            return null;
        }
        if (methodInfo.getDeclaration() != null) {
            return methodInfo.getDeclaration();
        }
        try {
            String body = methodInfo.getBody() == null ? "{}" : methodInfo.getBody().trim();
            String source = body.startsWith("{")
                    ? methodInfo.getSignature() + " " + body
                    : methodInfo.getSignature() + " { " + body + " }";
            return StaticJavaParser.parseMethodDeclaration(source);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String resolveRootScopeIdentifier(MethodCallExpr methodCallExpr) {
        if (methodCallExpr == null || methodCallExpr.getScope().isEmpty()) {
            return "";
        }
        Expression scope = methodCallExpr.getScope().get();
        while (scope instanceof MethodCallExpr scopedCall && scopedCall.getScope().isPresent()) {
            scope = scopedCall.getScope().get();
        }
        if (scope.isNameExpr()) {
            return scope.asNameExpr().getNameAsString();
        }
        return "";
    }

    private Map<String, String> buildImportLookup(TestClassInfo classInfo, JSONObject contextJson) {
        LinkedHashMap<String, String> lookup = new LinkedHashMap<>();
        if (classInfo != null) {
            addImportsToLookup(lookup, classInfo.getImports());
            String packageName = packageNameFromPath(classInfo.getTargetPath());
            if (!packageName.isBlank()) {
                lookup.putIfAbsent(classInfo.getClassName(), packageName + "." + classInfo.getClassName());
            }
        }
        JSONObject methodContext = contextJson == null ? null : contextJson.optJSONObject("methodContext");
        if (methodContext != null) {
            JSONArray imports = methodContext.optJSONArray("imports");
            if (imports != null) {
                List<String> rawImports = new ArrayList<>();
                for (int index = 0; index < imports.length(); index++) {
                    rawImports.add(imports.optString(index));
                }
                addImportsToLookup(lookup, rawImports);
            }
            String originalClassFqcn = methodContext.optString("originalClassFqcn");
            if (!originalClassFqcn.isBlank()) {
                lookup.putIfAbsent(simpleName(originalClassFqcn), originalClassFqcn);
            }
        }
        return lookup;
    }

    private void addContextImports(Set<String> imports, TestClassInfo classInfo, JSONObject contextJson) {
        if (classInfo != null) {
            for (String importLine : classInfo.getImports()) {
                String fqcn = normaliseImport(importLine);
                if (!fqcn.isBlank()) {
                    imports.add(fqcn);
                }
            }
        }
        JSONObject methodContext = contextJson == null ? null : contextJson.optJSONObject("methodContext");
        JSONArray contextImports = methodContext == null ? null : methodContext.optJSONArray("imports");
        if (contextImports == null) {
            return;
        }
        for (int index = 0; index < contextImports.length(); index++) {
            String fqcn = normaliseImport(contextImports.optString(index));
            if (!fqcn.isBlank()) {
                imports.add(fqcn);
            }
        }
    }

    private void addImportsToLookup(Map<String, String> lookup, List<String> imports) {
        if (imports == null) {
            return;
        }
        for (String importLine : imports) {
            String fqcn = normaliseImport(importLine);
            if (!fqcn.isBlank() && !fqcn.endsWith(".*")) {
                lookup.putIfAbsent(simpleName(fqcn), fqcn);
            }
        }
    }

    private String normaliseImport(String importLine) {
        if (importLine == null) {
            return "";
        }
        String trimmed = importLine.trim();
        if (trimmed.startsWith("import ")) {
            trimmed = trimmed.substring("import ".length()).trim();
        }
        if (trimmed.startsWith("static ")) {
            trimmed = trimmed.substring("static ".length()).trim();
        }
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private String resolveTargetClassName(TestClassInfo classInfo,
                                          Analyze.AnalysisSummary analysisSummary,
                                          JSONObject contextJson) {
        JSONObject methodContext = contextJson == null ? null : contextJson.optJSONObject("methodContext");
        if (methodContext != null && !methodContext.optString("originalClassFqcn").isBlank()) {
            return methodContext.optString("originalClassFqcn");
        }
        if (analysisSummary.testTargetContext() != null
                && analysisSummary.testTargetContext().className() != null
                && !analysisSummary.testTargetContext().className().isBlank()) {
            return analysisSummary.testTargetContext().className();
        }
        return classInfo == null ? "" : classInfo.getClassName();
    }

    private String renderType(String type, Map<String, String> importLookup) {
        String simple = simpleName(type);
        return simple.isBlank() ? type : simple;
    }

    private String literalFor(String simpleType, String parameterName) {
        return switch (simpleType) {
            case "boolean", "Boolean" -> "true";
            case "byte", "Byte" -> "(byte) 1";
            case "short", "Short" -> "(short) 1";
            case "int", "Integer" -> "10";
            case "long", "Long" -> "10L";
            case "float", "Float" -> "1.0f";
            case "double", "Double" -> "1.0d";
            case "char", "Character" -> "'a'";
            case "String", "java.lang.String" -> stringLiteralFor(parameterName);
            default -> null;
        };
    }

    private String stringLiteralFor(String parameterName) {
        String lower = parameterName == null ? "" : parameterName.toLowerCase(Locale.ROOT);
        if (lower.contains("email")) {
            return "\"john.doe@example.com\"";
        }
        if (lower.contains("user") || lower.contains("name")) {
            return "\"John Doe\"";
        }
        return "\"test\"";
    }

    private List<ConstructorMetadata> constructorsForSimpleName(String simpleType,
                                                                Map<String, List<ConstructorMetadata>> constructors) {
        if (constructors == null || constructors.isEmpty()) {
            return List.of();
        }
        List<ConstructorMetadata> direct = constructors.get(simpleType);
        if (direct != null) {
            return direct;
        }
        return constructors.entrySet().stream()
                .filter(entry -> simpleType.equals(simpleName(entry.getKey())))
                .findFirst()
                .map(Map.Entry::getValue)
                .orElse(List.of());
    }

    private String packageNameFromPath(Path path) {
        if (path == null) {
            return "";
        }
        String normalised = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        String marker = "/src/test/java/";
        int index = normalised.indexOf(marker);
        if (index < 0) {
            return "";
        }
        String remainder = normalised.substring(index + marker.length());
        int lastSlash = remainder.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "";
        }
        return remainder.substring(0, lastSlash).replace('/', '.');
    }

    private boolean isNegativeActivationMutation(String methodName) {
        String lower = methodName == null ? "" : methodName.toLowerCase(Locale.ROOT);
        return lower.contains("deactivate") || lower.contains("disable");
    }

    private boolean isPositiveActivationMutation(String methodName) {
        String lower = methodName == null ? "" : methodName.toLowerCase(Locale.ROOT);
        return lower.contains("activate") || lower.contains("enable");
    }

    private String replaceStandaloneName(String value, String from, String to) {
        if (value == null || value.isBlank() || from == null || from.isBlank()) {
            return value == null ? "" : value;
        }
        return Pattern.compile("\\b" + Pattern.quote(from) + "\\b").matcher(value).replaceAll(to);
    }

    private String uniqueName(String baseName, Set<String> unavailableNames) {
        String base = baseName == null || baseName.isBlank() ? "value" : baseName;
        if (unavailableNames == null || !unavailableNames.contains(base)) {
            return base;
        }
        int suffix = 2;
        while (unavailableNames.contains(base + suffix)) {
            suffix++;
        }
        return base + suffix;
    }

    private String methodName(String targetMethodName, String providerMethodName) {
        return "should"
                + capitalise(targetMethodName)
                + "When"
                + capitalise(providerMethodName)
                + "ReturnsPresent";
    }

    private String simpleName(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String trimmed = type.trim();
        int genericStart = trimmed.indexOf('<');
        if (genericStart >= 0) {
            trimmed = trimmed.substring(0, genericStart);
        }
        int arrayStart = trimmed.indexOf('[');
        if (arrayStart >= 0) {
            trimmed = trimmed.substring(0, arrayStart);
        }
        int dot = trimmed.lastIndexOf('.');
        return dot >= 0 ? trimmed.substring(dot + 1) : trimmed;
    }

    private String lowerCamel(String value) {
        String simple = simpleName(value);
        if (simple.isBlank()) {
            return "target";
        }
        return simple.substring(0, 1).toLowerCase(Locale.ROOT) + simple.substring(1);
    }

    private String variableName(String candidate, String type) {
        if (candidate != null && !candidate.isBlank()) {
            return candidate.trim();
        }
        return lowerCamel(type);
    }

    private String capitalise(String value) {
        if (value == null || value.isBlank()) {
            return "TargetMethod";
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private String resolveClassName(TestClassInfo classInfo, GeneratedTestSnippet snippet) {
        if (classInfo != null && classInfo.getTestClassName() != null && !classInfo.getTestClassName().isBlank()) {
            return classInfo.getTestClassName();
        }
        if (snippet != null && snippet.className() != null && !snippet.className().isBlank()) {
            return snippet.className();
        }
        return "GeneratedOptionalSideEffectTest";
    }

    private record OptionalScenario(String optionalVariable,
                                    String domainType,
                                    String providerTarget,
                                    String providerMethod,
                                    List<String> providerArguments,
                                    String lambdaParameter,
                                    List<MethodCallExpr> domainMutations,
                                    List<MethodCallExpr> sideEffectCalls) {
    }

    private record DomainBinding(String name, String declarationLine) {
    }

    private record ValueBinding(String name, String type, String declarationLine) {
    }

    private record FallbackPlan(String className,
                                String methodName,
                                String targetMethodName,
                                String instanceName,
                                List<String> setupLines,
                                List<ValueBinding> methodArguments,
                                List<String> stateAssertions,
                                List<String> verifyLines,
                                List<String> imports) {
    }
}
