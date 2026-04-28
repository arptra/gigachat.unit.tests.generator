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
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
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

/**
 * Builds deterministic baselines for constructor-local workflows where generation repeatedly
 * tries to mock the local object or stub void side effects. The builder is intentionally
 * pattern-driven: it reads constructorLocalContexts, finds an early feature-flag branch in the
 * constructor-created local method, and verifies observable collaborator side effects.
 */
public final class ConstructorLocalSideEffectFallbackBuilder {

    private final PipelineLogger logger;

    public ConstructorLocalSideEffectFallbackBuilder(PipelineLogger logger) {
        this.logger = logger;
    }

    public GeneratedTestSnippet buildFastPath(TestClassInfo classInfo,
                                              TestMethodInfo methodInfo,
                                              Analyze.AnalysisSummary analysisSummary,
                                              JSONObject contextJson) {
        FallbackPlan plan = buildPlan(classInfo, methodInfo, analysisSummary, contextJson, null);
        return plan == null ? null : render(plan, classInfo, null);
    }

    public GeneratedTestSnippet build(TestClassInfo classInfo,
                                      TestMethodInfo methodInfo,
                                      Analyze.AnalysisSummary analysisSummary,
                                      GeneratedTestSnippet snippet,
                                      JSONObject contextJson,
                                      String validationMessage) {
        if (!isConstructorLocalValidationFailure(validationMessage)) {
            return null;
        }
        FallbackPlan plan = buildPlan(classInfo, methodInfo, analysisSummary, contextJson, validationMessage);
        return plan == null ? null : render(plan, classInfo, snippet);
    }

    private FallbackPlan buildPlan(TestClassInfo classInfo,
                                   TestMethodInfo methodInfo,
                                   Analyze.AnalysisSummary analysisSummary,
                                   JSONObject contextJson,
                                   String validationMessage) {
        if (classInfo == null || methodInfo == null || analysisSummary == null || contextJson == null) {
            return null;
        }
        JSONArray constructorLocalContexts = contextJson.optJSONArray("constructorLocalContexts");
        if (constructorLocalContexts == null || constructorLocalContexts.isEmpty()) {
            return null;
        }
        MethodDeclaration targetMethod = resolveDeclaration(methodInfo);
        if (targetMethod == null || !targetMethod.getType().asString().equals("boolean")) {
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
            setupLines.add(typeName + " " + variableName + " = mock(" + typeName + ".class);");
            constructorArguments.add(variableName);
            mockNames.add(variableName);
        }

        List<ValueBinding> methodArguments = buildMethodArguments(targetMethod, analysisSummary, importLookup, imports);
        if (methodArguments == null || methodArguments.isEmpty() != targetMethod.getParameters().isEmpty()) {
            return null;
        }
        Set<String> availableNames = new LinkedHashSet<>(mockNames);
        methodArguments.forEach(binding -> availableNames.add(binding.name()));
        ConstructorLocalScenario scenario = findScenario(constructorLocalContexts, availableNames, mockNames);
        if (scenario == null) {
            return null;
        }
        if (validationMessage != null
                && !validationMessage.isBlank()
                && !validationMessage.contains("E106")
                && !validationMessage.contains("E109")
                && !mentionsLocalContext(validationMessage, constructorLocalContexts)) {
            return null;
        }

        List<String> verifyLines = new ArrayList<>(scenario.verifyLines());
        verifyLines.addAll(extractOuterBranchVerifications(targetMethod,
                constructorLocalContexts,
                scenario.expectedResult(),
                mockNames,
                availableNames));
        verifyLines = dedupe(verifyLines);
        if (verifyLines.isEmpty()) {
            return null;
        }

        String targetClass = renderType(resolveTargetClassName(classInfo, analysisSummary, contextJson), importLookup);
        String instanceName = targetContext.instanceName() == null || targetContext.instanceName().isBlank()
                ? lowerCamel(simpleName(targetClass))
                : targetContext.instanceName();
        setupLines.add(targetClass + " " + instanceName + " = new " + targetClass + "(" + String.join(", ", constructorArguments) + ");");
        imports.add("static org.mockito.Mockito.mock");
        imports.add("static org.mockito.Mockito.verify");
        imports.add("static org.mockito.Mockito.when");
        imports.add(scenario.expectedResult()
                ? "static org.junit.jupiter.api.Assertions.assertTrue"
                : "static org.junit.jupiter.api.Assertions.assertFalse");

        return new FallbackPlan(
                resolveClassName(classInfo, null),
                methodName(targetMethod.getNameAsString(), scenario.featureName(), scenario.stubValue()),
                targetMethod.getNameAsString(),
                instanceName,
                setupLines,
                methodArguments,
                scenario.stubLine(),
                scenario.expectedResult(),
                verifyLines,
                List.copyOf(imports));
    }

    private GeneratedTestSnippet render(FallbackPlan plan, TestClassInfo classInfo, GeneratedTestSnippet snippet) {
        List<String> lines = new ArrayList<>();
        lines.add("@Test");
        lines.add("void " + plan.methodName() + "() {");
        for (String line : plan.setupLines()) {
            lines.add("    " + line);
        }
        for (ValueBinding binding : plan.methodArguments()) {
            lines.add("    " + binding.declarationLine());
        }
        lines.add("    " + plan.stubLine());
        lines.add("");
        String invocation = plan.instanceName()
                + "."
                + plan.targetMethodName()
                + "("
                + String.join(", ", plan.methodArguments().stream().map(ValueBinding::name).toList())
                + ")";
        lines.add("    boolean result = " + invocation + ";");
        lines.add("");
        lines.add("    " + (plan.expectedResult() ? "assertTrue(result);" : "assertFalse(result);"));
        for (String verification : plan.verifyLines()) {
            lines.add("    " + verification);
        }
        lines.add("}");
        logger.info("Built deterministic constructor-local side-effect fallback for " + plan.targetMethodName());
        return new GeneratedTestSnippet(resolveClassName(classInfo, snippet),
                plan.methodName(),
                String.join(System.lineSeparator(), lines),
                plan.imports(),
                List.of(),
                List.of(),
                List.of(),
                "");
    }

    private ConstructorLocalScenario findScenario(JSONArray constructorLocalContexts,
                                                  Set<String> availableNames,
                                                  Set<String> mockNames) {
        for (int contextIndex = 0; contextIndex < constructorLocalContexts.length(); contextIndex++) {
            JSONObject context = constructorLocalContexts.optJSONObject(contextIndex);
            if (context == null) {
                continue;
            }
            LinkedHashSet<String> constructorCollaborators = jsonStringSet(context.optJSONArray("constructorArgumentCollaborators"));
            JSONArray invokedMethods = context.optJSONArray("invokedMethods");
            if (invokedMethods == null || invokedMethods.isEmpty()) {
                continue;
            }
            for (int methodIndex = 0; methodIndex < invokedMethods.length(); methodIndex++) {
                JSONObject invokedMethod = invokedMethods.optJSONObject(methodIndex);
                if (invokedMethod == null || invokedMethod.optString("sourceSnippet").isBlank()) {
                    continue;
                }
                MethodDeclaration localMethod = parseMethodDeclaration(invokedMethod.optString("sourceSnippet"));
                if (localMethod == null || !localMethod.getType().asString().equals("boolean")) {
                    continue;
                }
                ConstructorLocalScenario scenario = findFeatureFlagReturnBranch(localMethod,
                        constructorCollaborators,
                        availableNames,
                        mockNames);
                if (scenario != null) {
                    return scenario;
                }
            }
        }
        return null;
    }

    private ConstructorLocalScenario findFeatureFlagReturnBranch(MethodDeclaration localMethod,
                                                                 Set<String> constructorCollaborators,
                                                                 Set<String> availableNames,
                                                                 Set<String> mockNames) {
        for (IfStmt ifStmt : localMethod.findAll(IfStmt.class)) {
            Optional<MethodCallExpr> driverCall = ifStmt.getCondition().findAll(MethodCallExpr.class).stream()
                    .filter(call -> "isEnabled".equals(call.getNameAsString()))
                    .filter(call -> call.getArguments().size() == 1)
                    .filter(call -> call.getArgument(0).isStringLiteralExpr())
                    .findFirst();
            if (driverCall.isEmpty()) {
                continue;
            }
            String driverScope = resolveRootScopeIdentifier(driverCall.get());
            if (driverScope.isBlank() || (!constructorCollaborators.contains(driverScope) && !mockNames.contains(driverScope))) {
                continue;
            }
            Optional<Boolean> returnValue = findBooleanReturn(unwrapBlock(ifStmt.getThenStmt()));
            if (returnValue.isEmpty()) {
                continue;
            }
            boolean stubValue = !isNegatedFeatureFlag(ifStmt.getCondition(), driverCall.get());
            String featureName = driverCall.get().getArgument(0).asStringLiteralExpr().getValue();
            List<String> verifyLines = extractVerificationLines(unwrapBlock(ifStmt.getThenStmt()),
                    availableNames,
                    mockNames);
            String stubLine = "when(" + driverScope + ".isEnabled(\"" + escapeJava(featureName) + "\")).thenReturn(" + stubValue + ");";
            return new ConstructorLocalScenario(featureName, stubValue, returnValue.get(), stubLine, verifyLines);
        }
        return null;
    }

    private List<String> extractOuterBranchVerifications(MethodDeclaration targetMethod,
                                                         JSONArray constructorLocalContexts,
                                                         boolean expectedResult,
                                                         Set<String> mockNames,
                                                         Set<String> availableNames) {
        LinkedHashSet<String> localVariables = constructorLocalVariables(constructorLocalContexts);
        LinkedHashSet<String> localResultVariables = new LinkedHashSet<>();
        for (VariableDeclarator variable : targetMethod.findAll(VariableDeclarator.class)) {
            if (variable.getInitializer().isEmpty() || !variable.getInitializer().get().isMethodCallExpr()) {
                continue;
            }
            MethodCallExpr call = variable.getInitializer().get().asMethodCallExpr();
            String root = resolveRootScopeIdentifier(call);
            if (!localVariables.contains(root)) {
                continue;
            }
            localResultVariables.add(variable.getNameAsString());
        }
        if (localResultVariables.isEmpty()) {
            return List.of();
        }
        List<String> verifications = new ArrayList<>();
        for (IfStmt ifStmt : targetMethod.findAll(IfStmt.class)) {
            BranchSelection selection = selectBranchForExpectedResult(ifStmt, localResultVariables, expectedResult);
            if (selection == null) {
                continue;
            }
            verifications.addAll(extractVerificationLines(selection.block(), availableNames, mockNames));
        }
        return verifications;
    }

    private BranchSelection selectBranchForExpectedResult(IfStmt ifStmt,
                                                          Set<String> localResultVariables,
                                                          boolean expectedResult) {
        Expression condition = ifStmt.getCondition();
        for (String variable : localResultVariables) {
            if (condition.isNameExpr() && variable.equals(condition.asNameExpr().getNameAsString())) {
                return new BranchSelection(unwrapBlock(expectedResult
                        ? ifStmt.getThenStmt()
                        : ifStmt.getElseStmt().orElse(null)));
            }
            if (condition.isUnaryExpr()
                    && condition.asUnaryExpr().getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
                    && condition.asUnaryExpr().getExpression().isNameExpr()
                    && variable.equals(condition.asUnaryExpr().getExpression().asNameExpr().getNameAsString())) {
                return new BranchSelection(unwrapBlock(expectedResult
                        ? ifStmt.getElseStmt().orElse(null)
                        : ifStmt.getThenStmt()));
            }
        }
        return null;
    }

    private List<String> extractVerificationLines(BlockStmt block,
                                                  Set<String> availableNames,
                                                  Set<String> mockNames) {
        if (block == null) {
            return List.of();
        }
        List<String> verifications = new ArrayList<>();
        for (ExpressionStmt expressionStmt : block.findAll(ExpressionStmt.class)) {
            Expression expression = expressionStmt.getExpression();
            if (!expression.isMethodCallExpr()) {
                continue;
            }
            MethodCallExpr call = expression.asMethodCallExpr();
            String root = resolveRootScopeIdentifier(call);
            if (root.isBlank() || !mockNames.contains(root) || containsUnavailableName(call, availableNames)) {
                continue;
            }
            verifications.add("verify(" + root + ")." + call.getNameAsString() + "(" + renderArguments(call) + ");");
        }
        return verifications;
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
            bindings.add(new ValueBinding(name, declaration));
        }
        return bindings;
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
                .filter(candidate -> candidate != null)
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

    private MethodDeclaration parseMethodDeclaration(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            return StaticJavaParser.parseMethodDeclaration(source);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Optional<Boolean> findBooleanReturn(BlockStmt block) {
        if (block == null) {
            return Optional.empty();
        }
        return block.findAll(ReturnStmt.class).stream()
                .map(ReturnStmt::getExpression)
                .flatMap(Optional::stream)
                .filter(Expression::isBooleanLiteralExpr)
                .map(expression -> expression.asBooleanLiteralExpr().getValue())
                .findFirst();
    }

    private BlockStmt unwrapBlock(Statement statement) {
        if (statement == null) {
            return null;
        }
        if (statement.isBlockStmt()) {
            return statement.asBlockStmt();
        }
        BlockStmt block = new BlockStmt();
        block.addStatement(statement.clone());
        return block;
    }

    private boolean isNegatedFeatureFlag(Expression condition, MethodCallExpr driverCall) {
        if (condition == null || driverCall == null) {
            return false;
        }
        if (condition.isUnaryExpr()
                && condition.asUnaryExpr().getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
                && condition.asUnaryExpr().getExpression().equals(driverCall)) {
            return true;
        }
        return condition.findAll(UnaryExpr.class).stream()
                .anyMatch(unary -> unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
                        && unary.getExpression().equals(driverCall));
    }

    private boolean containsUnavailableName(MethodCallExpr call, Set<String> availableNames) {
        for (NameExpr nameExpr : call.findAll(NameExpr.class)) {
            String name = nameExpr.getNameAsString();
            if (!availableNames.contains(name) && !name.equals(resolveRootScopeIdentifier(call))) {
                return true;
            }
        }
        return false;
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

    private String renderArguments(MethodCallExpr call) {
        return call.getArguments().stream()
                .map(Expression::toString)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private List<String> dedupe(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private LinkedHashSet<String> constructorLocalVariables(JSONArray constructorLocalContexts) {
        LinkedHashSet<String> variables = new LinkedHashSet<>();
        if (constructorLocalContexts == null) {
            return variables;
        }
        for (int index = 0; index < constructorLocalContexts.length(); index++) {
            JSONObject context = constructorLocalContexts.optJSONObject(index);
            if (context == null) {
                continue;
            }
            String variable = context.optString("variable");
            if (!variable.isBlank()) {
                variables.add(variable);
            }
        }
        return variables;
    }

    private LinkedHashSet<String> jsonStringSet(JSONArray array) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (array == null) {
            return values;
        }
        for (int index = 0; index < array.length(); index++) {
            String value = array.optString(index);
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private boolean isConstructorLocalValidationFailure(String validationMessage) {
        return validationMessage != null
                && (validationMessage.contains("E106") || validationMessage.contains("E109"));
    }

    private boolean mentionsLocalContext(String validationMessage, JSONArray constructorLocalContexts) {
        if (validationMessage == null || constructorLocalContexts == null) {
            return false;
        }
        for (int index = 0; index < constructorLocalContexts.length(); index++) {
            JSONObject context = constructorLocalContexts.optJSONObject(index);
            if (context == null) {
                continue;
            }
            if (validationMessage.contains(context.optString("variable"))
                    || validationMessage.contains(context.optString("className"))
                    || validationMessage.contains(context.optString("classFqcn"))) {
                return true;
            }
        }
        return false;
    }

    private String methodName(String targetMethodName, String featureName, boolean enabled) {
        String state = enabled ? "Enabled" : "Disabled";
        return "should"
                + capitalise(targetMethodName)
                + "When"
                + toPascalCase(featureName)
                + state;
    }

    private String resolveTargetClassName(TestClassInfo classInfo,
                                          Analyze.AnalysisSummary analysisSummary,
                                          JSONObject contextJson) {
        JSONObject methodContext = contextJson.optJSONObject("methodContext");
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

    private String toPascalCase(String value) {
        if (value == null || value.isBlank()) {
            return "Feature";
        }
        StringBuilder builder = new StringBuilder();
        for (String part : value.split("[^A-Za-z0-9]+")) {
            if (part.isBlank()) {
                continue;
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.isEmpty() ? "Feature" : builder.toString();
    }

    private String escapeJava(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String resolveClassName(TestClassInfo classInfo, GeneratedTestSnippet snippet) {
        if (classInfo != null && classInfo.getTestClassName() != null && !classInfo.getTestClassName().isBlank()) {
            return classInfo.getTestClassName();
        }
        if (snippet != null && snippet.className() != null && !snippet.className().isBlank()) {
            return snippet.className();
        }
        return "GeneratedConstructorLocalWorkflowTest";
    }

    private record ConstructorLocalScenario(String featureName,
                                            boolean stubValue,
                                            boolean expectedResult,
                                            String stubLine,
                                            List<String> verifyLines) {
    }

    private record BranchSelection(BlockStmt block) {
    }

    private record ValueBinding(String name, String declarationLine) {
    }

    private record FallbackPlan(String className,
                                String methodName,
                                String targetMethodName,
                                String instanceName,
                                List<String> setupLines,
                                List<ValueBinding> methodArguments,
                                String stubLine,
                                boolean expectedResult,
                                List<String> verifyLines,
                                List<String> imports) {
    }
}
