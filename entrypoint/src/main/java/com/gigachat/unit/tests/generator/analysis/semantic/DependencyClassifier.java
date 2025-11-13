package com.gigachat.unit.tests.generator.analysis.semantic;

import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class DependencyClassifier {
    public ClassifiedDependencies classify(SemanticNodes nodes, TestMethodInfo info) {
        if (nodes == null || info == null) {
            return new ClassifiedDependencies(Map.of(), Map.of(), Set.of(), Set.of(), List.of());
        }

        MethodDeclaration declaration = info.getDeclaration();
        Map<String, String> localTypes = collectLocalTypes(declaration);
        Map<DependencyType, List<MethodCallExpr>> methodCalls = new EnumMap<>(DependencyType.class);
        Set<String> collaboratorTypes = new LinkedHashSet<>();
        Set<String> domainTypes = new LinkedHashSet<>();

        for (MethodCallExpr call : nodes.getMethodCalls()) {
            DependencyType type = classifyCall(call, localTypes);
            if (isReturnExpression(call)) {
                type = DependencyType.RETURN_OBJECT;
            }
            methodCalls.computeIfAbsent(type, t -> new ArrayList<>()).add(call);
            if (type == DependencyType.COLLABORATOR) {
                resolveCollaboratorType(call, localTypes).ifPresent(collaboratorTypes::add);
            }
        }

        for (Expression expression : nodes.getReturns()) {
            resolveExpressionType(expression, localTypes, info.getReturnType()).ifPresent(domainTypes::add);
        }
        if (domainTypes.isEmpty() && info.getReturnType() != null && !info.getReturnType().isBlank()) {
            domainTypes.add(info.getReturnType());
        }

        return new ClassifiedDependencies(methodCalls, localTypes, collaboratorTypes, domainTypes, nodes.getReturns());
    }

    private Map<String, String> collectLocalTypes(MethodDeclaration declaration) {
        Map<String, String> types = new LinkedHashMap<>();
        if (declaration == null) {
            return types;
        }
        for (Parameter parameter : declaration.getParameters()) {
            types.put(parameter.getNameAsString(), parameter.getType().asString());
        }
        declaration.findAll(VariableDeclarationExpr.class).forEach(expr ->
                expr.getVariables().forEach(variable ->
                        types.put(variable.getNameAsString(), variable.getType().asString())));
        return types;
    }

    private DependencyType classifyCall(MethodCallExpr call, Map<String, String> localTypes) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return DependencyType.COLLABORATOR;
        }
        Expression expression = scope.get();
        if (expression instanceof ThisExpr) {
            return DependencyType.COLLABORATOR;
        }
        if (expression instanceof FieldAccessExpr fieldAccess) {
            if (fieldAccess.getScope() instanceof ThisExpr) {
                return DependencyType.COLLABORATOR;
            }
            if (fieldAccess.getScope() instanceof NameExpr nameExpr && localTypes.containsKey(nameExpr.getNameAsString())) {
                return DependencyType.TEMP_OBJECT;
            }
            return DependencyType.COLLABORATOR;
        }
        if (expression instanceof NameExpr nameExpr) {
            return localTypes.containsKey(nameExpr.getNameAsString())
                    ? DependencyType.TEMP_OBJECT
                    : DependencyType.COLLABORATOR;
        }
        if (expression instanceof ObjectCreationExpr) {
            return DependencyType.TEMP_OBJECT;
        }
        return DependencyType.TEMP_OBJECT;
    }

    private Optional<String> resolveCollaboratorType(MethodCallExpr call, Map<String, String> localTypes) {
        return call.getScope().flatMap(scope -> {
            if (scope instanceof NameExpr nameExpr) {
                String name = nameExpr.getNameAsString();
                if (localTypes.containsKey(name)) {
                    return Optional.ofNullable(localTypes.get(name));
                }
                return Optional.of(name);
            }
            if (scope instanceof FieldAccessExpr fieldAccess) {
                return Optional.of(fieldAccess.getNameAsString());
            }
            return Optional.of(scope.toString());
        });
    }

    private boolean isReturnExpression(MethodCallExpr call) {
        return call.findAncestor(ReturnStmt.class).isPresent();
    }

    private Optional<String> resolveExpressionType(Expression expression,
                                                   Map<String, String> localTypes,
                                                   String fallback) {
        if (expression == null) {
            return Optional.empty();
        }
        if (expression instanceof ObjectCreationExpr creationExpr) {
            return Optional.of(creationExpr.getType().asString());
        }
        if (expression instanceof MethodCallExpr methodCallExpr) {
            return resolveCollaboratorType(methodCallExpr, localTypes);
        }
        if (expression instanceof NameExpr nameExpr) {
            String name = nameExpr.getNameAsString();
            return Optional.ofNullable(localTypes.getOrDefault(name, fallback));
        }
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            String fieldName = fieldAccessExpr.getNameAsString();
            return Optional.ofNullable(localTypes.getOrDefault(fieldName, fallback));
        }
        if (expression instanceof ThisExpr) {
            return Optional.ofNullable(fallback);
        }
        return Optional.ofNullable(fallback);
    }
}
