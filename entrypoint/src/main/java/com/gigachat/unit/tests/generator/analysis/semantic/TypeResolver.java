package com.gigachat.unit.tests.generator.analysis.semantic;

import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves expressions to semantic types using the collected {@link SemanticTypeContext}.
 */
public class TypeResolver {
    public static final String UNKNOWN_TYPE = "unknown";

    private final SemanticTypeContext context;
    private final MethodSignatureRegistry registry;
    private final String enclosingType;

    public TypeResolver(String enclosingType,
                        SemanticTypeContext context,
                        MethodSignatureRegistry registry) {
        this.enclosingType = enclosingType == null ? "" : enclosingType;
        this.context = context;
        this.registry = registry;
    }

    public Optional<ResolvedType> resolve(Expression expression) {
        if (expression == null) {
            return Optional.empty();
        }
        Optional<ResolvedType> cached = context.getExpressionType(expression);
        if (cached.isPresent()) {
            return cached;
        }
        if (expression instanceof NameExpr nameExpr) {
            return context.resolveSymbol(nameExpr.getNameAsString());
        }
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            if (fieldAccessExpr.getScope() instanceof ThisExpr) {
                return context.resolveField(fieldAccessExpr.getNameAsString());
            }
            return resolveOwnerType(fieldAccessExpr.getScope());
        }
        if (expression instanceof MethodCallExpr callExpr) {
            return resolveMethod(callExpr);
        }
        if (expression instanceof ObjectCreationExpr creationExpr) {
            return Optional.of(ResolvedType.of(creationExpr.getType().asString()));
        }
        if (expression instanceof ThisExpr || expression instanceof SuperExpr) {
            return enclosingType.isEmpty() ? Optional.empty() : Optional.of(ResolvedType.of(enclosingType));
        }
        if (expression instanceof CastExpr castExpr) {
            return Optional.of(ResolvedType.of(castExpr.getType().asString()));
        }
        if (expression instanceof EnclosedExpr enclosedExpr) {
            return resolve(enclosedExpr.getInner());
        }
        if (expression instanceof ArrayAccessExpr arrayAccessExpr) {
            return resolve(arrayAccessExpr.getName());
        }
        if (expression instanceof AssignExpr assignExpr) {
            return resolve(assignExpr.getValue());
        }
        if (expression instanceof LambdaExpr) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    public Optional<ResolvedType> resolveOwnerType(Expression scope) {
        if (scope == null) {
            return enclosingType.isEmpty() ? Optional.empty() : Optional.of(ResolvedType.of(enclosingType));
        }
        if (scope instanceof NameExpr) {
            return resolve(scope);
        }
        if (scope instanceof FieldAccessExpr fieldAccessExpr) {
            if (fieldAccessExpr.getScope() instanceof ThisExpr) {
                return context.resolveField(fieldAccessExpr.getNameAsString());
            }
            return resolve(fieldAccessExpr.getScope());
        }
        if (scope instanceof MethodCallExpr methodCallExpr) {
            return resolveMethod(methodCallExpr);
        }
        if (scope instanceof ObjectCreationExpr creationExpr) {
            return Optional.of(ResolvedType.of(creationExpr.getType().asString()));
        }
        if (scope instanceof ArrayAccessExpr arrayAccessExpr) {
            return resolve(arrayAccessExpr.getName());
        }
        if (scope instanceof ThisExpr || scope instanceof SuperExpr) {
            return enclosingType.isEmpty() ? Optional.empty() : Optional.of(ResolvedType.of(enclosingType));
        }
        return resolve(scope);
    }

    public Optional<ResolvedType> resolveMethod(MethodCallExpr expr) {
        if (expr == null) {
            return Optional.empty();
        }
        Optional<ResolvedType> cached = context.getExpressionType(expr);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<ResolvedType> ownerType = expr.getScope()
                .flatMap(this::resolveOwnerType)
                .or(() -> enclosingType.isEmpty() ? Optional.empty() : Optional.of(ResolvedType.of(enclosingType)));
        if (ownerType.isEmpty()) {
            return Optional.empty();
        }
        ResolvedType resolved = resolveFromRegistry(ownerType.get(), expr);
        if (resolved == null || resolved.isUnknown()) {
            return Optional.empty();
        }
        context.registerExpressionType(expr, resolved);
        return Optional.of(resolved);
    }

    private ResolvedType resolveFromRegistry(ResolvedType ownerType, MethodCallExpr expr) {
        List<String> signatures = registry.getMethods(ownerType.getName());
        if (signatures.isEmpty()) {
            return ResolvedType.unknown();
        }
        for (String signature : signatures) {
            MethodSignature parsed = parseMethodSignature(ownerType.getName(), signature);
            if (parsed == null) {
                continue;
            }
            if (!parsed.getMethodName().equals(expr.getNameAsString())) {
                continue;
            }
            if (parsed.getParameterTypes().size() != expr.getArguments().size()) {
                continue;
            }
            return ResolvedType.of(parsed.getReturnType());
        }
        return ResolvedType.unknown();
    }

    private MethodSignature parseMethodSignature(String ownerType, String signature) {
        if (signature == null || signature.isBlank()) {
            return null;
        }
        int start = signature.indexOf('(');
        int end = signature.lastIndexOf(')');
        if (start < 0 || end < start) {
            return null;
        }
        String before = signature.substring(0, start).trim();
        String inside = signature.substring(start + 1, end);
        String methodName = before;
        String returnType = UNKNOWN_TYPE;
        int lastSpace = before.lastIndexOf(' ');
        if (lastSpace >= 0 && lastSpace + 1 < before.length()) {
            methodName = before.substring(lastSpace + 1);
            returnType = before.substring(0, lastSpace);
        }
        List<String> params = parseParameterTypes(inside);
        return new MethodSignature(ResolvedType.normalise(ownerType), methodName, params, ResolvedType.normalise(returnType));
    }

    private List<String> parseParameterTypes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> params = new LinkedHashSet<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                params.add(ResolvedType.normalise(current.toString()));
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        if (current.length() > 0) {
            params.add(ResolvedType.normalise(current.toString()));
        }
        return List.copyOf(params);
    }

    public Optional<ResolvedType> resolve(String symbol) {
        return context.resolveSymbol(symbol);
    }

    public ResolvedType inferLiteralType(Expression expression) {
        if (expression == null) {
            return ResolvedType.unknown();
        }
        if (expression.isBooleanLiteralExpr()) {
            return ResolvedType.of("boolean");
        }
        if (expression.isCharLiteralExpr()) {
            return ResolvedType.of("char");
        }
        if (expression.isDoubleLiteralExpr()) {
            return ResolvedType.of("double");
        }
        if (expression.isIntegerLiteralExpr()) {
            return ResolvedType.of("int");
        }
        if (expression.isLongLiteralExpr()) {
            return ResolvedType.of("long");
        }
        if (expression.isNullLiteralExpr()) {
            return ResolvedType.unknown();
        }
        if (expression.isStringLiteralExpr()) {
            return ResolvedType.of("String");
        }
        if (expression instanceof ObjectCreationExpr creationExpr) {
            return ResolvedType.of(creationExpr.getType().asString());
        }
        return ResolvedType.unknown();
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
