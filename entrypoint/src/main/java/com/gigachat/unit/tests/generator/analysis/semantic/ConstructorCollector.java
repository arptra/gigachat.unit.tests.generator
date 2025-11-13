package com.gigachat.unit.tests.generator.analysis.semantic;

import com.gigachat.unit.tests.generator.analysis.api.ConstructorInfo;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ConstructorCollector {
    public List<ConstructorInfo> collect(List<ObjectCreationExpr> creations) {
        if (creations == null || creations.isEmpty()) {
            return List.of();
        }
        List<ConstructorInfo> infos = new ArrayList<>();
        for (ObjectCreationExpr creation : creations) {
            String className = creation.getType().asString();
            List<String> parameterTypes = new ArrayList<>();
            for (Expression argument : creation.getArguments()) {
                parameterTypes.add(inferType(argument));
            }
            String signature = className + "(" + String.join(", ", parameterTypes) + ")";
            infos.add(new ConstructorInfo(className, parameterTypes, signature));
        }
        return List.copyOf(infos);
    }

    private String inferType(Expression expression) {
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
        if (expression instanceof MethodReferenceExpr methodReferenceExpr) {
            return methodReferenceExpr.getScope().toString();
        }
        if (expression instanceof NameExpr nameExpr) {
            return nameExpr.getNameAsString();
        }
        String metaType = expression.getMetaModel().getTypeName();
        if (metaType == null || metaType.isBlank()) {
            return "unknown";
        }
        return metaType.toLowerCase(Locale.ROOT);
    }
}
