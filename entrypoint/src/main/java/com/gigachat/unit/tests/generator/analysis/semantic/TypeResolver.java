package com.gigachat.unit.tests.generator.analysis.semantic;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves variable names to their declared types within a method.
 */
public class TypeResolver {
    public static final String UNKNOWN_TYPE = "unknown";

    private final Map<String, String> symbolToType = new LinkedHashMap<>();
    private final String enclosingType;

    private TypeResolver(String enclosingType) {
        this.enclosingType = enclosingType == null ? "" : enclosingType.trim();
    }

    public static TypeResolver forMethod(MethodDeclaration declaration) {
        String className = declaration.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(ClassOrInterfaceDeclaration::getNameAsString)
                .orElse("");
        TypeResolver resolver = new TypeResolver(className);
        if (declaration != null) {
            for (Parameter parameter : declaration.getParameters()) {
                resolver.registerType(parameter.getNameAsString(), parameter.getType().asString());
            }
            declaration.findAll(VariableDeclarator.class)
                    .forEach(variable -> resolver.registerType(variable.getNameAsString(), variable.getType().asString()));
            declaration.findAncestor(ClassOrInterfaceDeclaration.class)
                    .ifPresent(clazz -> clazz.getFields()
                            .forEach(field -> registerField(resolver, field)));
        }
        return resolver;
    }

    private static void registerField(TypeResolver resolver, FieldDeclaration field) {
        field.getVariables().forEach(variable ->
                resolver.registerType(variable.getNameAsString(), field.getElementType().asString()));
    }

    public void registerType(String symbol, String rawType) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }
        String typeName = simpleName(rawType);
        if (!typeName.isEmpty()) {
            symbolToType.put(symbol, typeName);
        }
    }

    public Optional<String> resolve(Expression expression) {
        if (expression == null) {
            return Optional.empty();
        }
        if (expression instanceof NameExpr nameExpr) {
            return Optional.ofNullable(symbolToType.get(nameExpr.getNameAsString()));
        }
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            if (fieldAccessExpr.getScope() instanceof ThisExpr) {
                return Optional.ofNullable(symbolToType.get(fieldAccessExpr.getNameAsString()));
            }
            return resolve(fieldAccessExpr.getScope());
        }
        if (expression instanceof ObjectCreationExpr creationExpr) {
            return Optional.of(simpleName(creationExpr.getType().asString()));
        }
        if (expression instanceof ThisExpr) {
            return enclosingType.isEmpty() ? Optional.empty() : Optional.of(enclosingType);
        }
        if (expression instanceof SuperExpr) {
            return enclosingType.isEmpty() ? Optional.empty() : Optional.of(enclosingType);
        }
        if (expression instanceof CastExpr castExpr) {
            return Optional.of(simpleName(castExpr.getType().asString()));
        }
        if (expression instanceof EnclosedExpr enclosedExpr) {
            return resolve(enclosedExpr.getInner());
        }
        if (expression instanceof ArrayAccessExpr arrayAccessExpr) {
            return resolve(arrayAccessExpr.getName());
        }
        if (expression instanceof MethodCallExpr methodCallExpr) {
            return methodCallExpr.getScope().flatMap(this::resolve);
        }
        return Optional.empty();
    }

    public Optional<String> resolve(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(symbolToType.get(symbol));
    }

    public String inferLiteralType(Expression expression) {
        if (expression == null) {
            return UNKNOWN_TYPE;
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
            return UNKNOWN_TYPE;
        }
        if (expression.isStringLiteralExpr()) {
            return "String";
        }
        if (expression instanceof ObjectCreationExpr creationExpr) {
            return simpleName(creationExpr.getType().asString());
        }
        return UNKNOWN_TYPE;
    }

    public static List<String> explodeTypes(String rawType) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (rawType == null || rawType.isBlank()) {
            return List.of();
        }
        String cleaned = rawType.replace("[]", "");
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < cleaned.length(); i++) {
            char ch = cleaned.charAt(i);
            if (ch == '<' || ch == '>' || ch == ',' || Character.isWhitespace(ch)) {
                flushToken(current, tokens);
                continue;
            }
            if (ch == '?') {
                flushToken(current, tokens);
                continue;
            }
            current.append(ch);
        }
        flushToken(current, tokens);
        if (tokens.isEmpty()) {
            String simple = simpleName(cleaned);
            return simple.isEmpty() ? List.of() : List.of(simple);
        }
        return List.copyOf(tokens);
    }

    private static void flushToken(StringBuilder current, Set<String> accumulator) {
        if (current.length() == 0) {
            return;
        }
        String token = current.toString();
        current.setLength(0);
        token = token.replace("extends", "").replace("super", "");
        String simple = simpleName(token);
        if (!simple.isEmpty()) {
            accumulator.add(simple);
        }
    }

    public static String simpleName(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.endsWith("[]")) {
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        }
        int generics = trimmed.indexOf('<');
        if (generics >= 0) {
            trimmed = trimmed.substring(0, generics);
        }
        int space = trimmed.lastIndexOf(' ');
        if (space >= 0 && space + 1 < trimmed.length()) {
            trimmed = trimmed.substring(space + 1);
        }
        int dot = trimmed.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < trimmed.length()) {
            trimmed = trimmed.substring(dot + 1);
        }
        return trimmed;
    }
}
