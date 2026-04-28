package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.testagent.entrypoint.pipeline.helpers.analyze.DependencyInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.InvocationInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.MockType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds prompt context for constructor-created local objects so generation can see the real
 * branch-driving source of those internal helper flows before it invents a mocking strategy.
 */
public class ConstructorLocalPromptContextBuilder {

    private final Path projectRoot;

    public ConstructorLocalPromptContextBuilder(Path projectRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    public List<Map<String, Object>> build(TestClassInfo classInfo, Analyze.AnalysisSummary summary) {
        if (projectRoot == null || classInfo == null || summary == null || summary.methodAnalysis() == null) {
            return List.of();
        }
        Map<String, String> importLookup = buildImportLookup(classInfo.getImports());
        List<Map<String, Object>> contexts = new ArrayList<>();
        for (DependencyInfo dependency : summary.methodAnalysis().dependencies()) {
            if (dependency == null || dependency.mockType() != MockType.CONSTRUCTOR) {
                continue;
            }
            String variableName = normalize(dependency.variableName());
            String className = simpleName(dependency.className());
            if (className.isBlank()) {
                continue;
            }
            String fqcn = resolveClassFqcn(className, importLookup);
            LinkedHashMap<String, Object> block = new LinkedHashMap<>();
            block.put("variable", variableName);
            block.put("className", className);
            if (fqcn != null && !fqcn.isBlank()) {
                block.put("classFqcn", fqcn);
            }
            if (!normalize(dependency.context()).isBlank()) {
                block.put("constructorContext", dependency.context().trim());
            }
            List<String> collaborators = extractConstructorArgumentNames(dependency.context());
            if (!collaborators.isEmpty()) {
                block.put("constructorArgumentCollaborators", collaborators);
            }
            List<Map<String, Object>> invokedMethods = buildInvokedMethodContexts(
                    variableName,
                    fqcn,
                    collaborators,
                    summary,
                    summary.methodAnalysis().invocations());
            if (!invokedMethods.isEmpty()) {
                block.put("invokedMethods", invokedMethods);
            }
            contexts.add(Map.copyOf(block));
        }
        return List.copyOf(contexts);
    }

    private List<Map<String, Object>> buildInvokedMethodContexts(String variableName,
                                                                 String fqcn,
                                                                 List<String> constructorArgumentCollaborators,
                                                                 Analyze.AnalysisSummary summary,
                                                                 List<InvocationInfo> invocations) {
        if (variableName.isBlank() || invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> contexts = new ArrayList<>();
        for (InvocationInfo invocation : invocations) {
            if (invocation == null || !variableName.equals(normalize(invocation.target()))) {
                continue;
            }
            LinkedHashMap<String, Object> block = new LinkedHashMap<>();
            block.put("name", normalize(invocation.methodName()));
            block.put("arity", invocation.argTypes() == null ? 0 : invocation.argTypes().size());
            String sourceSnippet = resolveMethodSourceSnippet(fqcn, normalize(invocation.methodName()), invocation.argTypes() == null ? 0 : invocation.argTypes().size());
            if (!sourceSnippet.isBlank()) {
                block.put("sourceSnippet", sourceSnippet);
                MethodDeclaration methodDeclaration = parseMethodDeclaration(sourceSnippet);
                List<String> branchDrivers = extractBranchDrivers(methodDeclaration);
                if (!branchDrivers.isEmpty()) {
                    block.put("branchDrivers", branchDrivers);
                }
                List<String> voidSideEffects = extractVoidSideEffects(methodDeclaration, constructorArgumentCollaborators);
                if (!voidSideEffects.isEmpty()) {
                    block.put("voidSideEffects", voidSideEffects);
                }
                List<String> publicStateMutators = extractPublicStateMutators(methodDeclaration, branchDrivers, summary);
                if (!publicStateMutators.isEmpty()) {
                    block.put("publicStateMutators", publicStateMutators);
                }
            }
            contexts.add(Map.copyOf(block));
        }
        return List.copyOf(contexts);
    }

    private MethodDeclaration parseMethodDeclaration(String sourceSnippet) {
        if (sourceSnippet == null || sourceSnippet.isBlank()) {
            return null;
        }
        try {
            return StaticJavaParser.parseMethodDeclaration(sourceSnippet);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private List<String> extractBranchDrivers(MethodDeclaration methodDeclaration) {
        if (methodDeclaration == null) {
            return List.of();
        }
        Map<String, String> initializerByVariable = buildInitializerByVariable(methodDeclaration);
        LinkedHashSet<String> drivers = new LinkedHashSet<>();
        for (IfStmt ifStmt : methodDeclaration.findAll(IfStmt.class)) {
            Expression condition = ifStmt.getCondition();
            addMethodCalls(condition, drivers);
            for (NameExpr nameExpr : condition.findAll(NameExpr.class)) {
                String variableName = normalize(nameExpr.getNameAsString());
                String initializer = initializerByVariable.get(variableName);
                if (initializer == null || initializer.isBlank()) {
                    continue;
                }
                drivers.add(variableName + " <= " + initializer);
                try {
                    addMethodCalls(StaticJavaParser.parseExpression(initializer), drivers);
                } catch (RuntimeException ignored) {
                    // Best-effort enrichment only.
                }
            }
        }
        return List.copyOf(drivers);
    }

    private Map<String, String> buildInitializerByVariable(MethodDeclaration methodDeclaration) {
        LinkedHashMap<String, String> initializers = new LinkedHashMap<>();
        if (methodDeclaration == null) {
            return initializers;
        }
        for (VariableDeclarator variable : methodDeclaration.findAll(VariableDeclarator.class)) {
            variable.getInitializer()
                    .map(Expression::toString)
                    .map(String::trim)
                    .filter(text -> !text.isBlank())
                    .ifPresent(text -> initializers.put(normalize(variable.getNameAsString()), text));
        }
        for (AssignExpr assignExpr : methodDeclaration.findAll(AssignExpr.class)) {
            if (!assignExpr.getTarget().isNameExpr()) {
                continue;
            }
            String name = normalize(assignExpr.getTarget().asNameExpr().getNameAsString());
            String value = assignExpr.getValue().toString().trim();
            if (!name.isBlank() && !value.isBlank()) {
                initializers.put(name, value);
            }
        }
        return initializers;
    }

    private void addMethodCalls(Expression expression, LinkedHashSet<String> drivers) {
        if (expression == null || drivers == null) {
            return;
        }
        for (MethodCallExpr methodCallExpr : expression.findAll(MethodCallExpr.class)) {
            String call = methodCallExpr.toString().trim();
            if (!call.isBlank()) {
                drivers.add(call);
            }
        }
    }

    private List<String> extractVoidSideEffects(MethodDeclaration methodDeclaration,
                                                List<String> constructorArgumentCollaborators) {
        if (methodDeclaration == null || constructorArgumentCollaborators == null || constructorArgumentCollaborators.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> collaboratorNames = new LinkedHashSet<>();
        for (String collaborator : constructorArgumentCollaborators) {
            String normalized = normalize(collaborator);
            if (!normalized.isBlank()) {
                collaboratorNames.add(normalized);
            }
        }
        if (collaboratorNames.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> sideEffects = new LinkedHashSet<>();
        for (ExpressionStmt expressionStmt : methodDeclaration.findAll(ExpressionStmt.class)) {
            Expression expression = expressionStmt.getExpression();
            if (!expression.isMethodCallExpr()) {
                continue;
            }
            MethodCallExpr methodCallExpr = expression.asMethodCallExpr();
            String rootIdentifier = resolveRootScopeIdentifier(methodCallExpr);
            if (!rootIdentifier.isBlank() && collaboratorNames.contains(rootIdentifier)) {
                sideEffects.add(methodCallExpr.toString().trim());
            }
        }
        return List.copyOf(sideEffects);
    }

    private List<String> extractPublicStateMutators(MethodDeclaration methodDeclaration,
                                                    List<String> branchDrivers,
                                                    Analyze.AnalysisSummary summary) {
        if (methodDeclaration == null
                || branchDrivers == null
                || branchDrivers.isEmpty()
                || summary == null
                || summary.availableMethods() == null
                || summary.availableMethods().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> publicStateMutators = new LinkedHashSet<>();
        methodDeclaration.getParameters().forEach(parameter -> {
            String parameterName = normalize(parameter.getNameAsString());
            if (parameterName.isBlank()) {
                return;
            }
            List<String> relevantAccessors = extractRelevantAccessors(parameterName, branchDrivers);
            if (relevantAccessors.isEmpty()) {
                return;
            }
            String parameterType = simpleName(parameter.getType().asString());
            List<String> availableMethods = summary.availableMethods().getOrDefault(parameterType, List.of());
            if (availableMethods.isEmpty()) {
                return;
            }
            List<String> relevantMutators = selectRelevantMutators(parameterName, relevantAccessors, availableMethods);
            publicStateMutators.addAll(relevantMutators);
        });
        return List.copyOf(publicStateMutators);
    }

    private List<String> extractRelevantAccessors(String parameterName, List<String> branchDrivers) {
        if (parameterName == null || parameterName.isBlank() || branchDrivers == null || branchDrivers.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> accessors = new LinkedHashSet<>();
        for (String branchDriver : branchDrivers) {
            String driver = normalize(branchDriver);
            if (driver.isBlank() || !driver.contains(parameterName + ".")) {
                continue;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile(java.util.regex.Pattern.quote(parameterName) + "\\.(\\w+)\\(")
                    .matcher(driver);
            while (matcher.find()) {
                accessors.add(matcher.group(1));
            }
        }
        return List.copyOf(accessors);
    }

    private List<String> selectRelevantMutators(String parameterName,
                                                List<String> accessors,
                                                List<String> availableMethods) {
        if (parameterName == null || parameterName.isBlank() || availableMethods == null || availableMethods.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> relevantMutators = new LinkedHashSet<>();
        LinkedHashSet<String> desiredTokens = new LinkedHashSet<>();
        if (accessors != null) {
            for (String accessor : accessors) {
                desiredTokens.addAll(accessorPropertyTokens(accessor));
            }
        }
        for (String signature : availableMethods) {
            String methodName = extractMethodName(signature);
            if (methodName.isBlank() || !isStateMutationMethod(methodName)) {
                continue;
            }
            if (desiredTokens.isEmpty() || matchesMutationTokens(methodName, desiredTokens)) {
                relevantMutators.add(parameterName + "." + methodName + "()");
            }
        }
        if (!relevantMutators.isEmpty()) {
            return List.copyOf(relevantMutators);
        }
        LinkedHashSet<String> fallbackMutators = new LinkedHashSet<>();
        for (String signature : availableMethods) {
            String methodName = extractMethodName(signature);
            if (!methodName.isBlank() && isStateMutationMethod(methodName)) {
                fallbackMutators.add(parameterName + "." + methodName + "()");
            }
        }
        return List.copyOf(fallbackMutators);
    }

    private List<String> accessorPropertyTokens(String accessorName) {
        String accessor = normalize(accessorName);
        if (accessor.isBlank()) {
            return List.of();
        }
        String property = accessor;
        if (property.startsWith("get") && property.length() > 3) {
            property = property.substring(3);
        } else if (property.startsWith("is") && property.length() > 2) {
            property = property.substring(2);
        }
        return splitCamelCaseTokens(property);
    }

    private boolean matchesMutationTokens(String methodName, LinkedHashSet<String> desiredTokens) {
        if (methodName == null || methodName.isBlank() || desiredTokens == null || desiredTokens.isEmpty()) {
            return false;
        }
        List<String> mutationTokens = splitCamelCaseTokens(methodName);
        String normalizedMethod = methodName.toLowerCase();
        for (String desiredToken : desiredTokens) {
            if (desiredToken == null || desiredToken.isBlank()) {
                continue;
            }
            String normalizedToken = desiredToken.toLowerCase();
            if (mutationTokens.contains(normalizedToken)
                    || normalizedMethod.contains(normalizedToken)
                    || ("active".equals(normalizedToken) && normalizedMethod.contains("activ"))) {
                return true;
            }
        }
        return false;
    }

    private List<String> splitCamelCaseTokens(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return List.of();
        }
        String spaced = normalized
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z])([A-Z][a-z])", "$1 $2")
                .toLowerCase();
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : spaced.split("[^a-z0-9]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    private boolean isStateMutationMethod(String methodName) {
        String normalized = normalize(methodName);
        if (normalized.isBlank()) {
            return false;
        }
        return !(normalized.startsWith("get")
                || normalized.startsWith("is")
                || normalized.startsWith("has")
                || normalized.equals("toString")
                || normalized.equals("hashCode")
                || normalized.equals("equals"));
    }

    private String extractMethodName(String signature) {
        String normalized = normalize(signature);
        if (normalized.isBlank()) {
            return "";
        }
        int openParen = normalized.indexOf('(');
        if (openParen <= 0) {
            return "";
        }
        String head = normalized.substring(0, openParen).trim();
        int separator = head.lastIndexOf(' ');
        return separator >= 0 ? head.substring(separator + 1).trim() : head;
    }

    private String resolveRootScopeIdentifier(MethodCallExpr methodCallExpr) {
        if (methodCallExpr == null || methodCallExpr.getScope().isEmpty()) {
            return "";
        }
        Expression scope = methodCallExpr.getScope().orElse(null);
        while (scope != null) {
            if (scope.isNameExpr()) {
                return normalize(scope.asNameExpr().getNameAsString());
            }
            if (scope.isFieldAccessExpr()) {
                FieldAccessExpr fieldAccessExpr = scope.asFieldAccessExpr();
                if (fieldAccessExpr.getScope().isThisExpr()) {
                    return normalize(fieldAccessExpr.getNameAsString());
                }
                scope = fieldAccessExpr.getScope();
                continue;
            }
            if (scope.isMethodCallExpr()) {
                scope = scope.asMethodCallExpr().getScope().orElse(null);
                continue;
            }
            if (scope.isThisExpr()) {
                return normalize(((ThisExpr) scope).toString());
            }
            return "";
        }
        return "";
    }

    private String resolveMethodSourceSnippet(String fqcn, String methodName, int arity) {
        if (fqcn == null || fqcn.isBlank() || methodName.isBlank()) {
            return "";
        }
        Path sourceFile = resolveSourceFile(fqcn);
        if (sourceFile == null || !Files.exists(sourceFile)) {
            return "";
        }
        try {
            CompilationUnit unit = StaticJavaParser.parse(Files.readString(sourceFile, StandardCharsets.UTF_8));
            Optional<MethodDeclaration> exact = unit.findAll(MethodDeclaration.class).stream()
                    .filter(method -> method.getNameAsString().equals(methodName))
                    .filter(method -> method.getParameters().size() == arity)
                    .findFirst();
            if (exact.isPresent()) {
                return exact.get().toString();
            }
            return unit.findAll(MethodDeclaration.class).stream()
                    .filter(method -> method.getNameAsString().equals(methodName))
                    .findFirst()
                    .map(MethodDeclaration::toString)
                    .orElse("");
        } catch (IOException exception) {
            return "";
        }
    }

    private Path resolveSourceFile(String fqcn) {
        Path direct = projectRoot.resolve("src/main/java").resolve(fqcn.replace('.', '/') + ".java");
        if (Files.exists(direct)) {
            return direct;
        }
        try (var paths = Files.walk(projectRoot.resolve("src/main/java"))) {
            return paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equals(simpleName(fqcn) + ".java"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException exception) {
            return null;
        }
    }

    private Map<String, String> buildImportLookup(List<String> imports) {
        LinkedHashMap<String, String> lookup = new LinkedHashMap<>();
        if (imports == null || imports.isEmpty()) {
            return lookup;
        }
        for (String rawImport : imports) {
            String normalized = normalizeImport(rawImport);
            if (normalized.isBlank() || normalized.endsWith(".*")) {
                continue;
            }
            lookup.put(simpleName(normalized), normalized);
        }
        return lookup;
    }

    private String resolveClassFqcn(String className, Map<String, String> importLookup) {
        if (className == null || className.isBlank()) {
            return "";
        }
        if (importLookup != null) {
            String direct = importLookup.get(className);
            if (direct != null && !direct.isBlank()) {
                return direct;
            }
        }
        return "";
    }

    private List<String> extractConstructorArgumentNames(String constructorContext) {
        String context = normalize(constructorContext);
        int openParen = context.indexOf('(');
        int closeParen = context.lastIndexOf(')');
        if (openParen < 0 || closeParen <= openParen) {
            return List.of();
        }
        String argumentsSection = context.substring(openParen + 1, closeParen).trim();
        if (argumentsSection.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        for (String rawArgument : argumentsSection.split(",")) {
            String candidate = normalize(rawArgument).replace("this.", "");
            if (candidate.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                identifiers.add(candidate);
            }
        }
        return List.copyOf(identifiers);
    }

    private String normalizeImport(String rawImport) {
        String normalized = normalize(rawImport);
        if (normalized.startsWith("import static ")) {
            normalized = normalized.substring("import static ".length()).trim();
        } else if (normalized.startsWith("import ")) {
            normalized = normalized.substring("import ".length()).trim();
        }
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private String simpleName(String typeName) {
        String normalized = normalize(typeName);
        int separator = normalized.lastIndexOf('.');
        if (separator >= 0 && separator + 1 < normalized.length()) {
            return normalized.substring(separator + 1);
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
