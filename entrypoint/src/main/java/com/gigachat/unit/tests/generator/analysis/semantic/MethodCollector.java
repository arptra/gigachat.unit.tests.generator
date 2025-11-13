package com.gigachat.unit.tests.generator.analysis.semantic;

import com.gigachat.unit.tests.generator.analysis.api.MethodInfo;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class MethodCollector {
    public Map<String, List<MethodInfo>> collect(ClassifiedDependencies dependencies) {
        if (dependencies == null) {
            return Map.of();
        }
        Map<String, List<MethodInfo>> result = new LinkedHashMap<>();
        Map<String, String> symbolTypes = dependencies.getSymbolTypes();
        for (DependencyType type : new DependencyType[]{DependencyType.COLLABORATOR,
                DependencyType.TEMP_OBJECT,
                DependencyType.RETURN_OBJECT}) {
            List<MethodCallExpr> calls = dependencies.getCalls(type);
            for (MethodCallExpr call : calls) {
                String key = determineKey(call, type, symbolTypes);
                MethodInfo info = new MethodInfo(call.getNameAsString(),
                        call.getArguments().stream()
                                .map(arg -> inferType(arg, symbolTypes))
                                .collect(Collectors.toCollection(ArrayList::new)),
                        "unknown");
                result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(info);
            }
        }
        return result.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    private String determineKey(MethodCallExpr call,
                                DependencyType type,
                                Map<String, String> symbolTypes) {
        return call.getScope()
                .map(scope -> {
                    if (scope instanceof NameExpr nameExpr) {
                        return symbolTypes.getOrDefault(nameExpr.getNameAsString(), nameExpr.getNameAsString());
                    }
                    if (scope instanceof FieldAccessExpr fieldAccessExpr) {
                        String name = fieldAccessExpr.getNameAsString();
                        return symbolTypes.getOrDefault(name, name);
                    }
                    return scope.toString();
                })
                .orElse(type.name());
    }

    private String inferType(Expression expression, Map<String, String> symbolTypes) {
        if (expression == null) {
            return "unknown";
        }
        if (expression.isBooleanLiteralExpr()) {
            return "boolean";
        }
        if (expression.isCharLiteralExpr()) {
            return "char";
        }
        if (expression.isDoubleLiteralExpr()) {
            return "double";
        }
        if (expression.isIntegerLiteralExpr()) {
            return "int";
        }
        if (expression.isLongLiteralExpr()) {
            return "long";
        }
        if (expression.isNullLiteralExpr()) {
            return "null";
        }
        if (expression.isStringLiteralExpr()) {
            return "String";
        }
        if (expression.isObjectCreationExpr()) {
            return expression.asObjectCreationExpr().getType().asString();
        }
        if (expression instanceof NameExpr nameExpr) {
            return symbolTypes.getOrDefault(nameExpr.getNameAsString(), nameExpr.getNameAsString());
        }
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            return symbolTypes.getOrDefault(fieldAccessExpr.getNameAsString(), fieldAccessExpr.getNameAsString());
        }
        String metaType = expression.getMetaModel().getTypeName();
        if (metaType == null || metaType.isBlank()) {
            return "unknown";
        }
        return metaType.toLowerCase(Locale.ROOT);
    }
}
