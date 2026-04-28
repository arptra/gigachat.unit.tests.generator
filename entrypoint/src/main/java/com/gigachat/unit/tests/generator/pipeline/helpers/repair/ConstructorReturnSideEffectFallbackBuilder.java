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
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
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
 * Builds deterministic tests for methods that construct a domain object, emit collaborator
 * side effects with that object or method arguments, and return the constructed object.
 */
public final class ConstructorReturnSideEffectFallbackBuilder {

    private final PipelineLogger logger;

    public ConstructorReturnSideEffectFallbackBuilder(PipelineLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public GeneratedTestSnippet buildFastPath(TestClassInfo classInfo,
                                              TestMethodInfo methodInfo,
                                              Analyze.AnalysisSummary analysisSummary,
                                              JSONObject contextJson) {
        FallbackPlan plan = buildPlan(classInfo, methodInfo, analysisSummary, contextJson);
        return plan == null ? null : render(plan, classInfo);
    }

    private FallbackPlan buildPlan(TestClassInfo classInfo,
                                   TestMethodInfo methodInfo,
                                   Analyze.AnalysisSummary analysisSummary,
                                   JSONObject contextJson) {
        if (classInfo == null || methodInfo == null || analysisSummary == null) {
            return null;
        }
        MethodDeclaration targetMethod = resolveDeclaration(methodInfo);
        if (targetMethod == null || "void".equals(targetMethod.getType().asString())) {
            return null;
        }
        Set<String> shouldMock = analysisSummary.mockPlan() == null
                ? Set.of()
                : new LinkedHashSet<>(analysisSummary.mockPlan().shouldMock());
        ConstructorReturnScenario scenario = findScenario(targetMethod, shouldMock);
        if (scenario == null) {
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

        List<ValueBinding> methodArguments = buildMethodArguments(targetMethod, importLookup);
        if (methodArguments == null || methodArguments.isEmpty() != targetMethod.getParameters().isEmpty()) {
            return null;
        }
        LinkedHashMap<String, String> availableNames = new LinkedHashMap<>();
        for (ValueBinding binding : methodArguments) {
            availableNames.put(binding.name(), binding.name());
        }

        List<String> setupLines = new ArrayList<>();
        List<String> constructorArguments = new ArrayList<>();
        for (ParameterMetadata parameter : targetConstructor.parameters()) {
            String variableName = variableName(parameter.name(), simpleName(parameter.type()));
            String typeName = renderType(parameter.type());
            if (typeName.isBlank()) {
                return null;
            }
            setupLines.add(typeName + " " + variableName + " = mock(" + typeName + ".class);");
            constructorArguments.add(variableName);
            availableNames.put(variableName, variableName);
        }

        String targetClass = renderType(resolveTargetClassName(classInfo, analysisSummary, contextJson));
        String instanceName = targetContext.instanceName() == null || targetContext.instanceName().isBlank()
                ? lowerCamel(simpleName(targetClass))
                : targetContext.instanceName();
        setupLines.add(targetClass + " " + instanceName + " = new " + targetClass + "(" + String.join(", ", constructorArguments) + ");");

        List<String> assertions = buildReturnAssertions(scenario, analysisSummary, methodArguments);
        List<String> verifications = buildVerificationLines(scenario, shouldMock, availableNames.keySet());
        if (verifications.isEmpty()) {
            return null;
        }
        imports.add("static org.junit.jupiter.api.Assertions.assertEquals");
        imports.add("static org.mockito.Mockito.mock");
        imports.add("static org.mockito.Mockito.verify");

        return new FallbackPlan(
                methodName(targetMethod.getNameAsString()),
                targetMethod.getNameAsString(),
                renderType(targetMethod.getType().asString()),
                instanceName,
                methodArguments,
                setupLines,
                assertions,
                verifications,
                List.copyOf(imports));
    }

    private ConstructorReturnScenario findScenario(MethodDeclaration method, Set<String> shouldMock) {
        for (VariableDeclarator variable : method.findAll(VariableDeclarator.class)) {
            if (variable.getInitializer().isEmpty() || !variable.getInitializer().get().isObjectCreationExpr()) {
                continue;
            }
            String variableName = variable.getNameAsString();
            if (!returnsVariable(method, variableName)) {
                continue;
            }
            ObjectCreationExpr creation = variable.getInitializer().get().asObjectCreationExpr();
            List<MethodCallExpr> sideEffects = method.findAll(ExpressionStmt.class).stream()
                    .map(ExpressionStmt::getExpression)
                    .filter(Expression::isMethodCallExpr)
                    .map(Expression::asMethodCallExpr)
                    .filter(call -> shouldMock.contains(resolveRootScopeIdentifier(call)))
                    .toList();
            if (sideEffects.isEmpty()) {
                continue;
            }
            return new ConstructorReturnScenario(variableName,
                    renderType(variable.getType().asString()),
                    creation.getArguments().stream().map(Expression::toString).toList(),
                    sideEffects);
        }
        return null;
    }

    private boolean returnsVariable(MethodDeclaration method, String variableName) {
        return method.findAll(ReturnStmt.class).stream()
                .map(ReturnStmt::getExpression)
                .flatMap(Optional::stream)
                .filter(Expression::isNameExpr)
                .map(expression -> expression.asNameExpr().getNameAsString())
                .anyMatch(variableName::equals);
    }

    private List<ValueBinding> buildMethodArguments(MethodDeclaration targetMethod, Map<String, String> importLookup) {
        List<ValueBinding> bindings = new ArrayList<>();
        for (Parameter parameter : targetMethod.getParameters()) {
            String name = parameter.getNameAsString();
            String type = renderType(parameter.getType().asString());
            String literal = literalFor(simpleName(type), name);
            if (literal == null) {
                return null;
            }
            bindings.add(new ValueBinding(name, type, type + " " + name + " = " + literal + ";"));
        }
        return bindings;
    }

    private List<String> buildReturnAssertions(ConstructorReturnScenario scenario,
                                               Analyze.AnalysisSummary analysisSummary,
                                               List<ValueBinding> methodArguments) {
        Set<String> availableMethods = availableMethodsForType(analysisSummary, scenario.typeName());
        LinkedHashSet<String> assertions = new LinkedHashSet<>();
        for (ValueBinding binding : methodArguments) {
            String getter = getterFor(binding.name(), binding.type(), availableMethods);
            if (!getter.isBlank()) {
                assertions.add("assertEquals(" + binding.name() + ", result." + getter + "());");
            }
        }
        return List.copyOf(assertions);
    }

    private List<String> buildVerificationLines(ConstructorReturnScenario scenario,
                                                Set<String> shouldMock,
                                                Set<String> availableNames) {
        LinkedHashSet<String> verifications = new LinkedHashSet<>();
        for (MethodCallExpr call : scenario.sideEffects()) {
            String root = resolveRootScopeIdentifier(call);
            if (root.isBlank() || !shouldMock.contains(root)) {
                continue;
            }
            List<String> arguments = new ArrayList<>();
            boolean supported = true;
            for (Expression argument : call.getArguments()) {
                String rendered = renderArgument(argument, scenario.variableName(), availableNames);
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

    private String renderArgument(Expression argument, String constructedVariable, Set<String> availableNames) {
        if (argument == null) {
            return "";
        }
        if (argument.isNameExpr()) {
            String name = argument.asNameExpr().getNameAsString();
            if (constructedVariable.equals(name)) {
                return "result";
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
            boolean referencesConstructedVariable = false;
            for (NameExpr nameExpr : argument.findAll(NameExpr.class)) {
                String name = nameExpr.getNameAsString();
                if (!availableNames.contains(name) && !constructedVariable.equals(name)) {
                    return "";
                }
                if (constructedVariable.equals(name)) {
                    referencesConstructedVariable = true;
                }
            }
            return referencesConstructedVariable
                    ? replaceNameOutsideStringLiterals(argument.toString(), constructedVariable, "result")
                    : argument.toString();
        }
        return "";
    }

    private GeneratedTestSnippet render(FallbackPlan plan, TestClassInfo classInfo) {
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
        lines.add("    " + plan.returnType() + " result = " + plan.instanceName() + "." + plan.targetMethodName()
                + "(" + String.join(", ", plan.methodArguments().stream().map(ValueBinding::name).toList()) + ");");
        lines.add("");
        for (String assertion : plan.assertions()) {
            lines.add("    " + assertion);
        }
        for (String verification : plan.verifications()) {
            lines.add("    " + verification);
        }
        lines.add("}");
        logger.info("Built deterministic constructor-return side-effect fallback for " + plan.targetMethodName());
        return new GeneratedTestSnippet(resolveClassName(classInfo),
                plan.methodName(),
                String.join(System.lineSeparator(), lines),
                plan.imports(),
                List.of(),
                List.of(),
                List.of(),
                "");
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

    private MethodDeclaration resolveDeclaration(TestMethodInfo methodInfo) {
        if (methodInfo.getDeclaration() != null) {
            return methodInfo.getDeclaration();
        }
        try {
            String body = methodInfo.getBody() == null ? "{}" : methodInfo.getBody().trim();
            return StaticJavaParser.parseMethodDeclaration(methodInfo.getSignature() + " " + (body.startsWith("{") ? body : "{ " + body + " }"));
        } catch (RuntimeException ignored) {
            return null;
        }
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

    private String getterFor(String parameterName, String parameterType, Set<String> availableMethods) {
        String suffix = toPascalCase(parameterName);
        String expected = simpleName(parameterType) + " get" + suffix + "()";
        if (availableMethods.contains(expected)) {
            return "get" + suffix;
        }
        String booleanGetter = "boolean is" + suffix + "()";
        if (availableMethods.contains(booleanGetter)) {
            return "is" + suffix;
        }
        return "";
    }

    private String resolveRootScopeIdentifier(MethodCallExpr methodCallExpr) {
        if (methodCallExpr == null || methodCallExpr.getScope().isEmpty()) {
            return "";
        }
        Expression scope = methodCallExpr.getScope().get();
        while (scope instanceof MethodCallExpr scopedCall && scopedCall.getScope().isPresent()) {
            scope = scopedCall.getScope().get();
        }
        return scope.isNameExpr() ? scope.asNameExpr().getNameAsString() : "";
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
        JSONArray imports = methodContext == null ? null : methodContext.optJSONArray("imports");
        if (imports != null) {
            List<String> rawImports = new ArrayList<>();
            for (int index = 0; index < imports.length(); index++) {
                rawImports.add(imports.optString(index));
            }
            addImportsToLookup(lookup, rawImports);
        }
        return lookup;
    }

    private void addContextImports(Set<String> imports, TestClassInfo classInfo, JSONObject contextJson) {
        if (classInfo != null) {
            classInfo.getImports().stream().map(this::normaliseImport).filter(value -> !value.isBlank()).forEach(imports::add);
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
        imports.stream()
                .map(this::normaliseImport)
                .filter(value -> !value.isBlank() && !value.endsWith(".*"))
                .forEach(value -> lookup.putIfAbsent(simpleName(value), value));
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
        if (analysisSummary.testTargetContext() != null && !analysisSummary.testTargetContext().className().isBlank()) {
            return analysisSummary.testTargetContext().className();
        }
        return classInfo == null ? "" : classInfo.getClassName();
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
        return lastSlash <= 0 ? "" : remainder.substring(0, lastSlash).replace('/', '.');
    }

    private String replaceNameOutsideStringLiterals(String value, String from, String to) {
        if (value == null || value.isBlank() || from == null || from.isBlank()) {
            return value == null ? "" : value;
        }
        StringBuilder builder = new StringBuilder(value.length());
        StringBuilder token = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (inString) {
                flushToken(builder, token, from, to);
                builder.append(current);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                flushToken(builder, token, from, to);
                inString = true;
                builder.append(current);
                continue;
            }
            if (Character.isJavaIdentifierPart(current)) {
                token.append(current);
            } else {
                flushToken(builder, token, from, to);
                builder.append(current);
            }
        }
        flushToken(builder, token, from, to);
        return builder.toString();
    }

    private void flushToken(StringBuilder builder, StringBuilder token, String from, String to) {
        if (token.isEmpty()) {
            return;
        }
        String text = token.toString();
        builder.append(text.equals(from) ? to : text);
        token.setLength(0);
    }

    private String methodName(String targetMethodName) {
        return "should" + capitalise(targetMethodName) + "ReturnConstructedValueAndEmitSideEffects";
    }

    private String renderType(String type) {
        return simpleName(type);
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
        return simple.isBlank() ? "target" : simple.substring(0, 1).toLowerCase(Locale.ROOT) + simple.substring(1);
    }

    private String variableName(String candidate, String type) {
        return candidate != null && !candidate.isBlank() ? candidate.trim() : lowerCamel(type);
    }

    private String capitalise(String value) {
        return value == null || value.isBlank() ? "TargetMethod" : value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private String toPascalCase(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String part : value.split("[^A-Za-z0-9]+")) {
            if (!part.isBlank()) {
                builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
                if (part.length() > 1) {
                    builder.append(part.substring(1));
                }
            }
        }
        return builder.toString();
    }

    private String resolveClassName(TestClassInfo classInfo) {
        return classInfo == null || classInfo.getTestClassName().isBlank()
                ? "GeneratedConstructorReturnSideEffectTest"
                : classInfo.getTestClassName();
    }

    private record ConstructorReturnScenario(String variableName,
                                             String typeName,
                                             List<String> constructorArguments,
                                             List<MethodCallExpr> sideEffects) {
    }

    private record ValueBinding(String name, String type, String declarationLine) {
    }

    private record FallbackPlan(String methodName,
                                String targetMethodName,
                                String returnType,
                                String instanceName,
                                List<ValueBinding> methodArguments,
                                List<String> setupLines,
                                List<String> assertions,
                                List<String> verifications,
                                List<String> imports) {
    }
}
