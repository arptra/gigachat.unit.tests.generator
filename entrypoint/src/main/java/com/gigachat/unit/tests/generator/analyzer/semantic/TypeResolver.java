package com.gigachat.unit.tests.generator.analyzer.semantic;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LiteralStringValueExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class TypeResolver {
    private static final List<String> GENERIC_OWNER_NAMES = List.of("existing", "it", "x", "item", "var");

    private final TypeName declaringType;
    private final Deque<Map<String, TypeName>> scopes = new ArrayDeque<>();
    private final Map<Expression, TypeName> expressionTypes = new IdentityHashMap<>();
    private final Map<LambdaExpr, LambdaContext> lambdaContexts = new IdentityHashMap<>();

    TypeResolver(TypeName declaringType) {
        this.declaringType = declaringType == null ? TypeName.unknown() : declaringType;
        scopes.push(new HashMap<>());
    }

    void registerLocal(String name, TypeName type) {
        if (name == null || name.isBlank()) {
            return;
        }
        scopes.peek().put(name, type);
    }

    void enterScope() {
        scopes.push(new HashMap<>());
    }

    void exitScope() {
        if (scopes.size() > 1) {
            scopes.pop();
        }
    }

    void recordExpressionType(Expression expression, TypeName type) {
        if (expression == null || type == null) {
            return;
        }
        expressionTypes.put(expression, type);
    }

    TypeName resolveExpressionType(Expression expression) {
        if (expression == null) {
            return TypeName.unknown();
        }
        TypeName recorded = expressionTypes.get(expression);
        if (recorded != null) {
            return recorded;
        }
        if (expression instanceof NameExpr nameExpr) {
            TypeName type = lookupLocal(nameExpr.getNameAsString());
            return type == null ? TypeName.unknown() : type;
        }
        if (expression instanceof ThisExpr || expression instanceof SuperExpr) {
            return declaringType;
        }
        if (expression instanceof ObjectCreationExpr objectCreationExpr) {
            TypeName type = resolveType(objectCreationExpr.getType());
            recordExpressionType(expression, type);
            return type;
        }
        if (expression instanceof LiteralStringValueExpr) {
            return TypeName.of("String");
        }
        return TypeName.unknown();
    }

    TypeName resolveOwnerType(MethodCallExpr call) {
        if (call == null) {
            return TypeName.unknown();
        }
        Optional<Expression> scope = call.getScope();
        if (scope.isPresent()) {
            Expression expression = scope.get();
            TypeName resolved = resolveExpressionType(expression);
            if (!resolved.isUnknown()) {
                return resolved;
            }
            if (expression.isNameExpr()) {
                String identifier = expression.asNameExpr().getNameAsString();
                TypeName fallback = lookupLocal(identifier);
                if (fallback != null) {
                    return fallback;
                }
                if (isPotentialTypeName(identifier)) {
                    return TypeName.of(identifier);
                }
            }
            String textual = expression.toString();
            if (GENERIC_OWNER_NAMES.contains(textual)) {
                TypeName fallback = lookupLocal(textual);
                if (fallback != null) {
                    return fallback;
                }
            }
            if (isPotentialTypeName(textual)) {
                return TypeName.of(textual);
            }
        } else {
            return declaringType;
        }
        return TypeName.unknown();
    }

    void registerLambdaContext(LambdaExpr expr, TypeName ownerType, String methodName) {
        if (expr == null) {
            return;
        }
        lambdaContexts.put(expr, new LambdaContext(ownerType, methodName));
    }

    TypeName resolveLambdaType(LambdaExpr expr) {
        if (expr == null) {
            return TypeName.unknown();
        }
        LambdaContext context = lambdaContexts.get(expr);
        if (context == null) {
            return TypeName.unknown();
        }
        TypeName owner = context.ownerType == null ? TypeName.unknown() : context.ownerType;
        String methodName = context.methodName == null ? "" : context.methodName;
        TypeName elementType = owner.typeArguments().isEmpty()
                ? TypeName.of("java.lang.Object")
                : owner.typeArguments().get(0);
        if ("Map".equals(owner.simpleName()) && owner.typeArguments().size() > 1) {
            elementType = owner.typeArguments().get(1);
        }
        switch (methodName) {
            case "removeIf", "filter" -> {
                return TypeName.of("Predicate<" + elementType.name() + ">");
            }
            case "map" -> {
                return TypeName.of("Function<" + elementType.name() + ", Object>");
            }
            default -> {
                return TypeName.of("Function<" + elementType.name() + ", Object>");
            }
        }
    }

    TypeName resolveType(Type type) {
        if (type == null) {
            return TypeName.unknown();
        }
        return TypeName.of(type.toString());
    }

    private TypeName lookupLocal(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        for (Map<String, TypeName> scope : scopes) {
            TypeName type = scope.get(normalized);
            if (type != null) {
                return type;
            }
        }
        return null;
    }

    private boolean isPotentialTypeName(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return false;
        }
        char first = identifier.charAt(0);
        return Character.isUpperCase(first) || identifier.contains(".");
    }

    private record LambdaContext(TypeName ownerType, String methodName) {
    }
}
