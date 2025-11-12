package com.testagent.entrypoint.pipeline.helpers.analyze;

import com.gigachat.unit.tests.generator.config.AnalysisConfig;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.expr.ThisExpr;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Extracts dependency information from a method body.
 */
public class DependencyAnalyzer {
    private final MockStrategyResolver strategyResolver;

    public DependencyAnalyzer(MockStrategyResolver strategyResolver) {
        this.strategyResolver = strategyResolver;
    }

    public List<DependencyInfo> analyze(TestClassInfo classInfo,
                                        TestMethodInfo methodInfo,
                                        BlockStmt body,
                                        AnalysisConfig options,
                                        boolean excludeInternalCollections) {
        if (body == null) {
            return List.of();
        }
        Map<String, DependencyInfo> dependencies = new LinkedHashMap<>();
        DependencyClassifier classifier = new DependencyClassifier(classInfo, methodInfo, body, excludeInternalCollections);
        body.findAll(ObjectCreationExpr.class)
                .forEach(expr -> handleObjectCreation(expr, dependencies, options, classifier));
        body.findAll(FieldAccessExpr.class)
                .forEach(expr -> handleFieldAccess(expr, dependencies, options, classifier));
        body.findAll(MethodCallExpr.class)
                .forEach(expr -> handleMethodCall(expr, dependencies, options, classifier));
        return List.copyOf(dependencies.values());
    }

    private void handleObjectCreation(ObjectCreationExpr expression,
                                      Map<String, DependencyInfo> dependencies,
                                      AnalysisConfig options,
                                      DependencyClassifier classifier) {
        String type = expression.getType().asString();
        if (options.isExcluded(type)) {
            return;
        }
        String variable = resolveAssignedVariable(expression).orElse(expression.getType().getName().getIdentifier());
        MockType mockType = strategyResolver.resolve(expression);
        addDependency(dependencies,
                classifier.createDependency(type,
                        variable,
                        mockType,
                        expression.toString(),
                        DependencyOrigin.CONSTRUCTOR,
                        null,
                        expression.getArguments().size()));
    }

    private void handleFieldAccess(FieldAccessExpr expression,
                                   Map<String, DependencyInfo> dependencies,
                                   AnalysisConfig options,
                                   DependencyClassifier classifier) {
        Expression scope = expression.getScope();
        String target = scope.toString();
        if (options.isExcluded(target)) {
            return;
        }
        MockType mockType = strategyResolver.resolve(expression);
        addDependency(dependencies,
                classifier.createDependency(target,
                        expression.getNameAsString(),
                        mockType,
                        expression.toString(),
                        DependencyOrigin.FIELD_ACCESS,
                        scope,
                        0));
    }

    private void handleMethodCall(MethodCallExpr expression,
                                  Map<String, DependencyInfo> dependencies,
                                  AnalysisConfig options,
                                  DependencyClassifier classifier) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return;
        }
        Expression scopeExpression = scope.get();
        if (scopeExpression instanceof NameExpr nameExpr) {
            String identifier = nameExpr.getNameAsString();
            if (options.isExcluded(identifier)) {
                return;
            }
            MockType mockType = strategyResolver.resolve(expression);
            if (mockType == MockType.STATIC && options.includeStatic()) {
                addDependency(dependencies,
                        classifier.createDependency(identifier,
                                expression.getNameAsString(),
                                mockType,
                                expression.toString(),
                                DependencyOrigin.STATIC_CALL,
                                scopeExpression,
                                expression.getArguments().size()));
            }
        }
    }

    private Optional<String> resolveAssignedVariable(ObjectCreationExpr expression) {
        return expression.getParentNode()
                .flatMap(parent -> {
                    if (parent instanceof VariableDeclarator declarator) {
                        return Optional.ofNullable(declarator.getName()).map(SimpleName::getIdentifier);
                    }
                    if (parent instanceof AssignExpr assignExpr) {
                        Expression target = assignExpr.getTarget();
                        if (target instanceof NameExpr nameExpr) {
                            return Optional.of(nameExpr.getNameAsString());
                        }
                    }
                    return Optional.empty();
                });
    }

    private void addDependency(Map<String, DependencyInfo> dependencies, DependencyInfo info) {
        dependencies.putIfAbsent(info.className() + "#" + info.variableName(), info);
    }

    private enum DependencyOrigin {
        CONSTRUCTOR,
        FIELD_ACCESS,
        STATIC_CALL
    }

    private static class DependencyClassifier {
        private static final Set<String> EXTERNAL_NAME_HINTS = Set.of(
                "service",
                "repository",
                "client",
                "gateway",
                "dao",
                "manager",
                "connector",
                "provider",
                "api"
        );

        private static final Set<String> COLLECTION_HINTS = Set.of(
                "collection",
                "list",
                "set",
                "map",
                "queue",
                "deque",
                "stream",
                "iterator",
                "optional",
                "cache",
                "buffer",
                "items",
                "values",
                "results"
        );

        private static final Set<String> PRIMITIVE_TYPES = Set.of(
                "byte", "short", "int", "long", "float", "double", "boolean", "char"
        );

        private static final Set<String> JDK_SIMPLE_TYPES = Set.of(
                "string",
                "integer",
                "long",
                "double",
                "bigdecimal",
                "biginteger",
                "optional",
                "list",
                "set",
                "map",
                "collection",
                "queue",
                "deque",
                "iterator",
                "stream",
                "localdate",
                "localdatetime",
                "instant"
        );

        private final String ownerClassName;
        private final boolean excludeInternalCollections;
        private final Map<String, String> variableTypes = new HashMap<>();
        private final Map<String, String> parameterTypes = new HashMap<>();

        private DependencyClassifier(TestClassInfo classInfo,
                                    TestMethodInfo methodInfo,
                                    BlockStmt body,
                                    boolean excludeInternalCollections) {
            this.ownerClassName = classInfo == null ? "" : defaultString(classInfo.getClassName());
            this.excludeInternalCollections = excludeInternalCollections;
            MethodDeclaration declaration = methodInfo == null ? null : methodInfo.getDeclaration();
            if (declaration != null) {
                for (Parameter parameter : declaration.getParameters()) {
                    String name = parameter.getNameAsString();
                    String type = parameter.getType().asString();
                    if (!name.isBlank()) {
                        variableTypes.put(name, type);
                        parameterTypes.put(name, type);
                    }
                }
            }
            if (body != null) {
                body.findAll(VariableDeclarator.class).forEach(declarator -> {
                    String name = declarator.getNameAsString();
                    String type = declarator.getType().asString();
                    if (!name.isBlank()) {
                        variableTypes.put(name, type);
                    }
                });
            }
        }

        private DependencyInfo createDependency(String rawType,
                                                String variableName,
                                                MockType mockType,
                                                String context,
                                                DependencyOrigin origin,
                                                Expression scope,
                                                int argumentCount) {
            String resolvedType = resolveType(rawType, variableName, scope, origin);
            boolean external = isExternalDependency(resolvedType, variableName, origin);
            boolean internal = isInternalStructure(resolvedType, variableName, origin, scope, external);
            if (internal) {
                external = false;
            }
            return new DependencyInfo(resolvedType,
                    variableName,
                    mockType,
                    context,
                    external,
                    internal,
                    argumentCount);
        }

        private String resolveType(String rawType,
                                   String variableName,
                                   Expression scope,
                                   DependencyOrigin origin) {
            String candidate = defaultString(rawType);
            if (origin == DependencyOrigin.CONSTRUCTOR) {
                return candidate;
            }
            if (scope instanceof ThisExpr) {
                return ownerClassName.isBlank() ? "this" : ownerClassName;
            }
            if (scope instanceof NameExpr nameExpr) {
                String identifier = nameExpr.getNameAsString();
                String known = variableTypes.get(identifier);
                if (known != null && !known.isBlank()) {
                    return known;
                }
                if (!candidate.isBlank()) {
                    return candidate;
                }
                return identifier;
            }
            if (scope instanceof FieldAccessExpr fieldAccessExpr) {
                Expression innerScope = fieldAccessExpr.getScope();
                return resolveType(innerScope.toString(), variableName, innerScope, origin);
            }
            if (!candidate.isBlank()) {
                return candidate;
            }
            if (scope != null) {
                String text = scope.toString();
                String known = variableTypes.get(text);
                if (known != null && !known.isBlank()) {
                    return known;
                }
            }
            if (!variableName.isBlank()) {
                String known = variableTypes.get(variableName);
                if (known != null && !known.isBlank()) {
                    return known;
                }
            }
            return candidate.isBlank() ? rawType : candidate;
        }

        private boolean isExternalDependency(String type,
                                             String variableName,
                                             DependencyOrigin origin) {
            if (origin == DependencyOrigin.CONSTRUCTOR) {
                return false;
            }
            String qualified = defaultString(type);
            if (matchesExternalPackage(qualified)) {
                return true;
            }
            String trimmedType = simpleName(qualified);
            if (trimmedType.isEmpty()) {
                return false;
            }
            if (isOwnerType(trimmedType) || isPrimitiveType(trimmedType) || isJdkType(trimmedType)) {
                return false;
            }
            if (matchesExternalSuffix(trimmedType)) {
                return true;
            }
            if (parameterTypes.containsKey(variableName)) {
                String parameterType = defaultString(parameterTypes.get(variableName));
                if (matchesExternalPackage(parameterType) || matchesExternalSuffix(simpleName(parameterType))) {
                    return true;
                }
            }
            if (looksExternalByName(variableName) && matchesExternalSuffix(trimmedType)) {
                return true;
            }
            return false;
        }

        private boolean isInternalStructure(String type,
                                            String variableName,
                                            DependencyOrigin origin,
                                            Expression scope,
                                            boolean externalAlreadyDetected) {
            if (externalAlreadyDetected) {
                return false;
            }
            if (origin == DependencyOrigin.CONSTRUCTOR) {
                return true;
            }
            if (scope instanceof ThisExpr) {
                return true;
            }
            if (isOwnerType(type)) {
                return true;
            }
            if (excludeInternalCollections && isCollectionLike(type, variableName)) {
                return true;
            }
            if (isPrimitiveType(type)) {
                return true;
            }
            return !externalAlreadyDetected;
        }

        private boolean isCollectionLike(String type, String variableName) {
            String simpleType = simpleName(type);
            if (COLLECTION_HINTS.contains(simpleType.toLowerCase())) {
                return true;
            }
            String lowerVariable = variableName == null ? "" : variableName.toLowerCase();
            for (String hint : COLLECTION_HINTS) {
                if (!hint.isEmpty() && lowerVariable.contains(hint)) {
                    return true;
                }
            }
            return false;
        }

        private boolean looksExternalByName(String name) {
            if (name == null || name.isBlank()) {
                return false;
            }
            String lower = name.toLowerCase();
            for (String hint : EXTERNAL_NAME_HINTS) {
                if (lower.contains(hint)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesExternalSuffix(String value) {
            if (value == null || value.isBlank()) {
                return false;
            }
            String lower = value.toLowerCase();
            return lower.endsWith("service")
                    || lower.endsWith("repository")
                    || lower.endsWith("client")
                    || lower.endsWith("gateway");
        }

        private boolean matchesExternalPackage(String type) {
            if (type == null || type.isBlank()) {
                return false;
            }
            String lower = type.toLowerCase();
            return lower.contains(".service.")
                    || lower.contains(".repository.")
                    || lower.contains(".gateway.")
                    || lower.contains(".client.");
        }

        private boolean isOwnerType(String type) {
            if (type == null || type.isBlank()) {
                return false;
            }
            String simple = simpleName(type);
            if (simple.isEmpty()) {
                return false;
            }
            String ownerSimple = simpleName(ownerClassName);
            return simple.equals(ownerSimple);
        }

        private boolean isPrimitiveType(String type) {
            if (type == null || type.isBlank()) {
                return false;
            }
            String lower = simpleName(type).toLowerCase();
            return PRIMITIVE_TYPES.contains(lower);
        }

        private boolean isJdkType(String type) {
            if (type == null || type.isBlank()) {
                return false;
            }
            String lower = simpleName(type).toLowerCase();
            return JDK_SIMPLE_TYPES.contains(lower);
        }

        private String simpleName(String type) {
            if (type == null) {
                return "";
            }
            String trimmed = type.trim();
            if (trimmed.isEmpty()) {
                return "";
            }
            int genericStart = trimmed.indexOf('<');
            if (genericStart > 0) {
                trimmed = trimmed.substring(0, genericStart);
            }
            int lastDot = trimmed.lastIndexOf('.');
            if (lastDot >= 0 && lastDot + 1 < trimmed.length()) {
                return trimmed.substring(lastDot + 1);
            }
            return trimmed;
        }

        private String defaultString(String value) {
            if (value == null) {
                return "";
            }
            return value.trim();
        }
    }
}
