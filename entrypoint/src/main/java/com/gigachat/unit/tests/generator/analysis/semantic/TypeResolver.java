package com.gigachat.unit.tests.generator.analysis.semantic;

import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Resolves expressions to semantic types using the collected {@link SemanticTypeContext}.
 */
public class TypeResolver {
    public static final String UNKNOWN_TYPE = "unknown";
    private static final Set<String> COLLECTION_TYPES = Set.of(
            "Collection",
            "List",
            "Set",
            "Iterable",
            "Queue",
            "Deque"
    );
    private static final Set<String> STREAM_TYPES = Set.of("Stream");

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
        if (isLiteral(expression)) {
            ResolvedType literal = inferLiteralType(expression);
            if (!literal.isUnknown()) {
                context.registerExpressionType(expression, literal);
                return Optional.of(literal);
            }
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
            Optional<ResolvedType> resolved = resolve(scope);
            if (resolved.isPresent()) {
                return resolved;
            }
            NameExpr nameExpr = (NameExpr) scope;
            if (looksLikeType(nameExpr.getNameAsString())) {
                return Optional.of(ResolvedType.of(nameExpr.getNameAsString()));
            }
            return resolved;
        }
        if (scope instanceof FieldAccessExpr fieldAccessExpr) {
            if (fieldAccessExpr.getScope() instanceof ThisExpr) {
                return context.resolveField(fieldAccessExpr.getNameAsString());
            }
            Optional<ResolvedType> resolvedScope = resolve(fieldAccessExpr.getScope());
            if (resolvedScope.isPresent()) {
                return resolvedScope;
            }
            if (fieldAccessExpr.getScope() instanceof NameExpr nameExpr && looksLikeType(nameExpr.getNameAsString())) {
                return Optional.of(ResolvedType.of(nameExpr.getNameAsString()));
            }
            return resolvedScope;
        }
        if (scope instanceof MethodCallExpr methodCallExpr) {
            return resolveMethod(methodCallExpr);
        }
        if (scope instanceof ObjectCreationExpr creationExpr) {
            return Optional.of(ResolvedType.of(creationExpr.getType().asString()));
        }
        if (isLiteral(scope)) {
            ResolvedType literal = inferLiteralType(scope);
            if (!literal.isUnknown()) {
                return Optional.of(literal);
            }
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
        List<ResolvedType> argumentTypes = resolveArgumentTypes(expr, ownerType.get());
        Optional<ResolvedType> resolved = inferMethodReturn(ownerType.get(), expr, argumentTypes)
                .or(() -> resolveFromRegistry(ownerType.get(), expr, argumentTypes));
        if (resolved.isEmpty() || resolved.get().isUnknown()) {
            return Optional.empty();
        }
        context.registerExpressionType(expr, resolved.get());
        return resolved;
    }

    private Optional<ResolvedType> resolveFromRegistry(ResolvedType ownerType,
                                                       MethodCallExpr expr,
                                                       List<ResolvedType> argumentTypes) {
        for (String candidate : ownerCandidates(ownerType)) {
            List<String> signatures = registry.getMethods(candidate);
            if (signatures.isEmpty()) {
                continue;
            }
            for (String signature : signatures) {
                MethodSignature parsed = parseMethodSignature(candidate, signature);
                if (parsed == null) {
                    continue;
                }
                if (!parsed.getMethodName().equals(expr.getNameAsString())) {
                    continue;
                }
                if (parsed.getParameterTypes().size() != argumentTypes.size()) {
                    continue;
                }
                if (parsed.getReturnType().isBlank()) {
                    continue;
                }
                return Optional.of(ResolvedType.of(parsed.getReturnType()));
            }
        }
        return Optional.empty();
    }

    private List<String> ownerCandidates(ResolvedType ownerType) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (ownerType != null) {
            if (!ownerType.describe().isBlank()) {
                candidates.add(ownerType.describe());
            }
            if (!ownerType.getName().isBlank()) {
                candidates.add(ownerType.getName());
            }
        }
        return List.copyOf(candidates);
    }

    private List<ResolvedType> resolveArgumentTypes(MethodCallExpr expr, ResolvedType ownerType) {
        if (expr == null) {
            return List.of();
        }
        List<ResolvedType> resolved = new ArrayList<>(expr.getArguments().size());
        for (int i = 0; i < expr.getArguments().size(); i++) {
            Expression argument = expr.getArgument(i);
            Optional<ResolvedType> type;
            if (argument instanceof LambdaExpr lambdaExpr) {
                type = context.getExpressionType(lambdaExpr);
                if (type.isEmpty()) {
                    type = resolveLambdaTargetType(ownerType, expr, i, lambdaExpr);
                    type.ifPresent(resolvedType -> context.registerExpressionType(lambdaExpr, resolvedType));
                }
            } else if (argument instanceof MethodReferenceExpr referenceExpr) {
                type = context.getExpressionType(referenceExpr);
                if (type.isEmpty()) {
                    type = resolveMethodReferenceTarget(ownerType, expr, i, referenceExpr);
                    type.ifPresent(resolvedType -> context.registerExpressionType(referenceExpr, resolvedType));
                }
            } else {
                type = resolve(argument);
                if (type.isEmpty()) {
                    ResolvedType literal = inferLiteralType(argument);
                    type = literal.isUnknown() ? Optional.empty() : Optional.of(literal);
                }
            }
            resolved.add(type.orElse(ResolvedType.unknown()));
        }
        return resolved;
    }

    public Optional<ResolvedType> resolveLambdaTargetType(ResolvedType ownerType,
                                                          MethodCallExpr call,
                                                          int argIndex,
                                                          LambdaExpr lambdaExpr) {
        if (ownerType == null || ownerType.isUnknown() || call == null) {
            return Optional.empty();
        }
        String elementType = firstGeneric(ownerType);
        return determineFunctionalTarget(ownerType.getName(), call.getNameAsString(), argIndex,
                () -> context.getLambdaReturn(lambdaExpr)
                        .map(ResolvedType::describe)
                        .filter(value -> !value.isBlank())
                        .orElse("Object"),
                elementType);
    }

    private Optional<ResolvedType> resolveMethodReferenceTarget(ResolvedType ownerType,
                                                                MethodCallExpr call,
                                                                int argIndex,
                                                                MethodReferenceExpr referenceExpr) {
        if (ownerType == null || ownerType.isUnknown() || call == null) {
            return Optional.empty();
        }
        String elementType = firstGeneric(ownerType);
        return determineFunctionalTarget(ownerType.getName(), call.getNameAsString(), argIndex,
                () -> resolveMethodReferenceReturnType(referenceExpr).orElse("Object"),
                elementType);
    }

    private Optional<ResolvedType> determineFunctionalTarget(String ownerBase,
                                                             String methodName,
                                                             int argIndex,
                                                             Supplier<String> outputSupplier,
                                                             String elementType) {
        if (ownerBase == null || ownerBase.isBlank()) {
            return Optional.empty();
        }
        if (isCollectionType(ownerBase) && argIndex == 0 && "removeIf".equals(methodName)) {
            return Optional.of(ResolvedType.of("Predicate<" + elementType + '>'));
        }
        if (isStreamType(ownerBase)) {
            if ("filter".equals(methodName) && argIndex == 0) {
                return Optional.of(ResolvedType.of("Predicate<" + elementType + '>'));
            }
            if ("map".equals(methodName) && argIndex == 0) {
                String lambdaReturn = outputSupplier.get();
                return Optional.of(ResolvedType.of("Function<" + elementType + ", " + lambdaReturn + '>'));
            }
        }
        return Optional.empty();
    }

    private Optional<ResolvedType> inferMethodReturn(ResolvedType ownerType,
                                                     MethodCallExpr expr,
                                                     List<ResolvedType> argumentTypes) {
        if (ownerType == null || ownerType.isUnknown() || expr == null) {
            return Optional.empty();
        }
        String ownerBase = ownerType.getName();
        String methodName = expr.getNameAsString();
        if (isCollectionType(ownerBase)) {
            String elementType = firstGeneric(ownerType);
            if ("stream".equals(methodName)) {
                return Optional.of(ResolvedType.of("Stream<" + elementType + '>'));
            }
            if ("get".equals(methodName) || "iterator".equals(methodName)) {
                return Optional.of(ResolvedType.of(elementType));
            }
            if ("add".equals(methodName) || "removeIf".equals(methodName)) {
                return Optional.of(ResolvedType.of("boolean"));
            }
        }
        if (isStreamType(ownerBase)) {
            if ("filter".equals(methodName)) {
                return Optional.of(ownerType);
            }
            if ("map".equals(methodName)) {
                String output = null;
                if (!expr.getArguments().isEmpty()) {
                    Expression firstArgument = expr.getArgument(0);
                    if (firstArgument instanceof LambdaExpr lambdaExpr) {
                        output = context.getLambdaReturn(lambdaExpr)
                                .map(ResolvedType::describe)
                                .filter(value -> !value.isBlank())
                                .orElse(null);
                    } else if (firstArgument instanceof MethodReferenceExpr referenceExpr) {
                        output = resolveMethodReferenceReturnType(referenceExpr).orElse(null);
                    }
                }
                if ((output == null || output.isBlank()) && !argumentTypes.isEmpty()) {
                    output = functionalOutput(argumentTypes.get(0));
                }
                if (output == null || output.isBlank()) {
                    output = "Object";
                }
                return Optional.of(ResolvedType.of("Stream<" + output + '>'));
            }
        }
        return Optional.empty();
    }

    private String firstGeneric(ResolvedType type) {
        if (type == null || type.getGenericArguments().isEmpty()) {
            return "Object";
        }
        String generic = type.getGenericArguments().get(0);
        return generic.isBlank() ? "Object" : generic;
    }

    private String secondGeneric(ResolvedType type) {
        if (type == null || type.getGenericArguments().size() < 2) {
            return "";
        }
        return type.getGenericArguments().get(1);
    }

    private String functionalOutput(ResolvedType type) {
        String second = secondGeneric(type);
        if (second != null && !second.isBlank()) {
            return second;
        }
        if (type == null) {
            return "";
        }
        String description = type.describe();
        int lt = description.indexOf('<');
        int gt = description.lastIndexOf('>');
        int comma = description.lastIndexOf(',');
        if (lt >= 0 && gt > comma && comma > lt) {
            return description.substring(comma + 1, gt).trim();
        }
        return "";
    }

    private Optional<String> resolveMethodReferenceReturnType(MethodReferenceExpr referenceExpr) {
        if (referenceExpr == null) {
            return Optional.empty();
        }
        Optional<String> owner = resolveMethodReferenceOwner(referenceExpr);
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        ResolvedType ownerType = ResolvedType.of(owner.get());
        for (String candidate : ownerCandidates(ownerType)) {
            List<String> signatures = registry.getMethods(candidate);
            if (signatures.isEmpty()) {
                continue;
            }
            for (String signature : signatures) {
                MethodSignature parsed = parseMethodSignature(candidate, signature);
                if (parsed == null) {
                    continue;
                }
                if (!parsed.getMethodName().equals(referenceExpr.getIdentifier())) {
                    continue;
                }
                if (parsed.getReturnType().isBlank()) {
                    continue;
                }
                return Optional.of(parsed.getReturnType());
            }
        }
        return Optional.empty();
    }

    private Optional<String> resolveMethodReferenceOwner(MethodReferenceExpr referenceExpr) {
        if (referenceExpr == null || referenceExpr.getScope() == null) {
            return Optional.empty();
        }
        Expression scope = referenceExpr.getScope();
        if (scope instanceof TypeExpr typeExpr) {
            return Optional.of(typeExpr.getType().asString());
        }
        Optional<ResolvedType> resolved = resolve(scope);
        if (resolved.isPresent()) {
            return Optional.of(resolved.get().describe());
        }
        if (scope instanceof NameExpr nameExpr && looksLikeType(nameExpr.getNameAsString())) {
            return Optional.of(nameExpr.getNameAsString());
        }
        return Optional.empty();
    }

    private boolean isCollectionType(String ownerBase) {
        return ownerBase != null && COLLECTION_TYPES.contains(ownerBase);
    }

    private boolean isStreamType(String ownerBase) {
        return ownerBase != null && STREAM_TYPES.contains(ownerBase);
    }

    private boolean isLiteral(Expression expression) {
        return expression instanceof StringLiteralExpr
                || expression instanceof BooleanLiteralExpr
                || expression instanceof IntegerLiteralExpr
                || expression instanceof DoubleLiteralExpr
                || expression instanceof LongLiteralExpr
                || expression instanceof CharLiteralExpr
                || expression instanceof NullLiteralExpr;
    }

    private boolean looksLikeType(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        char first = value.charAt(0);
        return Character.isUpperCase(first);
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
