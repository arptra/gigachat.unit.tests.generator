package com.gigachat.unit.tests.generator.analysis.semantic;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Walks a method AST and records semantic artefacts.
 */
public class SemanticMethodVisitor extends VoidVisitorAdapter<SemanticTypeContext> {
    private final TypeResolver resolver;
    private final SemanticTypeContext context;
    private final Map<String, List<RawMethodUsage>> rawMethodUsages = new LinkedHashMap<>();
    private final List<RawStaticCall> rawStaticCalls = new ArrayList<>();
    private final Set<ResolvedType> rawDomainTypes = new LinkedHashSet<>();

    public SemanticMethodVisitor(TypeResolver resolver, SemanticTypeContext context) {
        this.resolver = resolver;
        this.context = context;
    }

    @Override
    public void visit(VariableDeclarationExpr expr, SemanticTypeContext arg) {
        for (VariableDeclarator variable : expr.getVariables()) {
            ResolvedType declaredType = ResolvedType.of(variable.getType().asString());
            context.registerLocal(variable.getNameAsString(), declaredType);
            addDomainType(declaredType);
        }
        super.visit(expr, arg);
        for (VariableDeclarator variable : expr.getVariables()) {
            variable.getInitializer().ifPresent(initializer -> {
                ResolvedType declaredType = ResolvedType.of(variable.getType().asString());
                context.registerExpressionType(initializer, declaredType);
                addDomainType(declaredType);
            });
        }
    }

    @Override
    public void visit(MethodCallExpr expr, SemanticTypeContext arg) {
        expr.getScope().ifPresent(scope -> scope.accept(this, arg));
        expr.getTypeArguments().ifPresent(arguments -> arguments.forEach(type -> type.accept(this, arg)));
        Optional<ResolvedType> ownerType = expr.getScope()
                .flatMap(resolver::resolveOwnerType)
                .or(() -> resolver.resolveOwnerType(null));
        for (int i = 0; i < expr.getArguments().size(); i++) {
            final int argIndex = i;
            Expression argument = expr.getArgument(i);
            if (argument instanceof LambdaExpr lambdaExpr) {
                ResolvedType initialTarget = ownerType.flatMap(owner ->
                        resolver.resolveLambdaTargetType(owner, expr, argIndex, lambdaExpr)).orElse(null);
                Map<String, ResolvedType> lambdaAssignments = inferLambdaParameters(lambdaExpr, initialTarget);
                withLambdaParameters(lambdaAssignments, () -> lambdaExpr.accept(this, arg));
                ResolvedType finalTarget = ownerType.flatMap(owner ->
                        resolver.resolveLambdaTargetType(owner, expr, argIndex, lambdaExpr)).orElse(initialTarget);
                if (finalTarget != null && !finalTarget.isUnknown()) {
                    context.registerExpressionType(lambdaExpr, finalTarget);
                    addDomainType(finalTarget);
                }
            } else {
                argument.accept(this, arg);
            }
        }
        ownerType.ifPresent(type -> expr.getScope().ifPresent(scope -> context.registerExpressionType(scope, type)));
        Optional<ResolvedType> returnType = resolver.resolve(expr);
        returnType.ifPresent(resolved -> {
            context.registerExpressionType(expr, resolved);
            addDomainType(resolved);
        });
        boolean staticCall = isStaticCall(expr, ownerType);
        if (!staticCall) {
            ownerType.ifPresent(this::addDomainType);
        }
        List<ResolvedType> parameterTypes = resolveParameterTypes(expr);
        parameterTypes.forEach(this::addDomainType);
        if (staticCall) {
            String owner = extractStaticOwner(expr);
            if (!owner.isBlank()) {
                rawStaticCalls.add(new RawStaticCall(owner, expr.getNameAsString()));
            }
            return;
        }
        ownerType.ifPresent(type -> recordMethod(type, expr, parameterTypes, returnType.orElse(ResolvedType.unknown())));
    }

    private Map<String, ResolvedType> inferLambdaParameters(LambdaExpr lambdaExpr,
                                                            ResolvedType lambdaTarget) {
        Map<String, ResolvedType> assignments = new LinkedHashMap<>();
        if (lambdaExpr == null) {
            return assignments;
        }
        String parameterType = "Object";
        if (lambdaTarget != null && !lambdaTarget.getGenericArguments().isEmpty()) {
            parameterType = lambdaTarget.getGenericArguments().get(0);
        }
        final String inferredType = parameterType;
        lambdaExpr.getParameters().forEach(parameter -> {
            if (!parameter.getType().isUnknownType()) {
                ResolvedType type = ResolvedType.of(parameter.getType().asString());
                assignments.put(parameter.getNameAsString(), type);
                addDomainType(type);
            } else {
                ResolvedType inferred = ResolvedType.of(inferredType);
                assignments.put(parameter.getNameAsString(), inferred);
                addDomainType(inferred);
            }
        });
        return assignments;
    }

    private void withLambdaParameters(Map<String, ResolvedType> assignments, Runnable action) {
        Map<String, Optional<ResolvedType>> previous = new LinkedHashMap<>();
        assignments.forEach((name, type) -> {
            previous.put(name, context.getLambdaParameter(name));
            context.registerLambdaParameter(name, type);
        });
        try {
            action.run();
        } finally {
            assignments.forEach((name, ignored) -> {
                Optional<ResolvedType> prior = previous.get(name);
                if (prior != null && prior.isPresent()) {
                    context.registerLambdaParameter(name, prior.get());
                } else {
                    context.removeLambdaParameter(name);
                }
            });
        }
    }

    private boolean isStaticCall(MethodCallExpr expr, Optional<ResolvedType> ownerType) {
        return expr.getScope().map(scope -> {
            if (scope.isTypeExpr()) {
                return true;
            }
            if (scope instanceof NameExpr nameExpr) {
                return context.resolveSymbol(nameExpr.getNameAsString()).isEmpty()
                        && looksLikeType(nameExpr.getNameAsString());
            }
            if (scope instanceof FieldAccessExpr fieldAccessExpr
                    && fieldAccessExpr.getScope() instanceof NameExpr nameExpr) {
                return context.resolveSymbol(nameExpr.getNameAsString()).isEmpty()
                        && looksLikeType(nameExpr.getNameAsString());
            }
            return ownerType.isEmpty() && scope.toString().contains(".") && looksLikeType(scope.toString());
        }).orElse(false);
    }

    private boolean looksLikeType(String value) {
        return value != null && !value.isBlank() && Character.isUpperCase(value.charAt(0));
    }

    @Override
    public void visit(ReturnStmt stmt, SemanticTypeContext arg) {
        super.visit(stmt, arg);
        stmt.getExpression().ifPresent(expression -> resolver.resolve(expression).ifPresent(this::addDomainType));
    }

    @Override
    public void visit(ObjectCreationExpr expr, SemanticTypeContext arg) {
        super.visit(expr, arg);
        for (Expression argument : expr.getArguments()) {
            ResolvedType resolved = resolver.resolve(argument)
                    .orElseGet(() -> resolver.inferLiteralType(argument));
            addDomainType(resolved);
        }
        ResolvedType createdType = ResolvedType.of(expr.getType().asString());
        context.registerExpressionType(expr, createdType);
        addDomainType(createdType);
    }

    @Override
    public void visit(FieldAccessExpr expr, SemanticTypeContext arg) {
        super.visit(expr, arg);
        resolver.resolve(expr).ifPresent(type -> {
            context.registerExpressionType(expr, type);
            addDomainType(type);
        });
    }

    @Override
    public void visit(CastExpr expr, SemanticTypeContext arg) {
        super.visit(expr, arg);
        ResolvedType castType = ResolvedType.of(expr.getType().asString());
        context.registerExpressionType(expr, castType);
        addDomainType(castType);
    }

    @Override
    public void visit(AssignExpr expr, SemanticTypeContext arg) {
        expr.getTarget().accept(this, arg);
        expr.getValue().accept(this, arg);
        resolver.resolve(expr.getValue()).ifPresent(type -> {
            if (expr.getTarget() instanceof NameExpr nameExpr) {
                context.registerLocal(nameExpr.getNameAsString(), type);
            } else if (expr.getTarget() instanceof FieldAccessExpr fieldAccessExpr
                    && fieldAccessExpr.getScope() instanceof ThisExpr) {
                context.registerField(fieldAccessExpr.getNameAsString(), type);
            }
            context.registerExpressionType(expr, type);
            addDomainType(type);
        });
    }

    @Override
    public void visit(ArrayAccessExpr expr, SemanticTypeContext arg) {
        super.visit(expr, arg);
        resolver.resolve(expr.getName()).ifPresent(type -> {
            context.registerExpressionType(expr, type);
            addDomainType(type);
        });
    }

    @Override
    public void visit(LambdaExpr expr, SemanticTypeContext arg) {
        Map<String, Optional<ResolvedType>> previous = new LinkedHashMap<>();
        expr.getParameters().forEach(parameter -> {
            if (!parameter.getType().isUnknownType()) {
                ResolvedType type = ResolvedType.of(parameter.getType().asString());
                previous.put(parameter.getNameAsString(), context.getLambdaParameter(parameter.getNameAsString()));
                context.registerLambdaParameter(parameter.getNameAsString(), type);
                addDomainType(type);
            }
        });
        if (expr.getBody().isExpressionStmt()) {
            expr.getBody().asExpressionStmt().getExpression().accept(this, arg);
        } else {
            expr.getBody().accept(this, arg);
        }
        resolveLambdaReturn(expr).ifPresent(type -> {
            context.registerLambdaReturn(expr, type);
            addDomainType(type);
        });
        expr.getParameters().forEach(parameter -> {
            Optional<ResolvedType> prior = previous.get(parameter.getNameAsString());
            if (prior != null && prior.isPresent()) {
                context.registerLambdaParameter(parameter.getNameAsString(), prior.get());
            } else {
                context.removeLambdaParameter(parameter.getNameAsString());
            }
        });
    }

    @Override
    public void visit(NameExpr expr, SemanticTypeContext arg) {
        super.visit(expr, arg);
        context.resolveSymbol(expr.getNameAsString()).ifPresent(type -> {
            context.registerExpressionType(expr, type);
            addDomainType(type);
        });
    }

    public Map<String, List<RawMethodUsage>> getRawMethodUsages() {
        return copy(rawMethodUsages);
    }

    public List<RawStaticCall> getRawStaticCalls() {
        return List.copyOf(rawStaticCalls);
    }

    public Set<ResolvedType> getRawDomainTypes() {
        return Set.copyOf(rawDomainTypes);
    }

    private void addDomainType(ResolvedType type) {
        if (type == null || type.isUnknown()) {
            return;
        }
        rawDomainTypes.add(type);
        for (String flattened : type.flatten()) {
            ResolvedType generic = ResolvedType.of(flattened);
            if (!generic.isUnknown()) {
                rawDomainTypes.add(generic);
            }
        }
    }

    private Optional<ResolvedType> resolveLambdaReturn(LambdaExpr expr) {
        if (expr == null) {
            return Optional.empty();
        }
        if (expr.getBody().isExpressionStmt()) {
            return resolver.resolve(expr.getBody().asExpressionStmt().getExpression());
        }
        if (expr.getBody().isBlockStmt()) {
            return expr.getBody().asBlockStmt().findAll(ReturnStmt.class).stream()
                    .map(ReturnStmt::getExpression)
                    .flatMap(Optional::stream)
                    .map(resolver::resolve)
                    .flatMap(Optional::stream)
                    .findFirst();
        }
        return Optional.empty();
    }

    private List<ResolvedType> resolveParameterTypes(MethodCallExpr expr) {
        List<ResolvedType> params = new ArrayList<>();
        for (Expression argument : expr.getArguments()) {
            ResolvedType resolved = resolver.resolve(argument)
                    .orElseGet(() -> resolver.inferLiteralType(argument));
            params.add(resolved);
        }
        return params;
    }

    private void recordMethod(ResolvedType ownerType,
                              MethodCallExpr expr,
                              List<ResolvedType> parameterTypes,
                              ResolvedType returnType) {
        if (ownerType == null || ownerType.isUnknown() || expr == null) {
            return;
        }
        String ownerKey = ownerType.describe();
        if (ownerKey.isBlank()) {
            return;
        }
        List<ResolvedType> params = new ArrayList<>();
        for (ResolvedType parameterType : parameterTypes) {
            params.add(parameterType == null ? ResolvedType.unknown() : parameterType);
        }
        RawMethodUsage usage = new RawMethodUsage(ownerType, expr.getNameAsString(), params, returnType);
        rawMethodUsages.computeIfAbsent(ownerKey, ignored -> new ArrayList<>());
        List<RawMethodUsage> usages = rawMethodUsages.get(ownerKey);
        if (!usages.contains(usage)) {
            usages.add(usage);
        }
    }

    private String extractStaticOwner(MethodCallExpr expr) {
        return expr.getScope()
                .map(scope -> {
                    if (scope.isTypeExpr()) {
                        TypeExpr typeExpr = scope.asTypeExpr();
                        return ResolvedType.normalise(typeExpr.getTypeAsString());
                    }
                    if (scope instanceof NameExpr nameExpr) {
                        return ResolvedType.normalise(nameExpr.getNameAsString());
                    }
                    if (scope instanceof FieldAccessExpr fieldAccessExpr) {
                        if (fieldAccessExpr.getScope() instanceof NameExpr nameExpr) {
                            return ResolvedType.normalise(nameExpr.getNameAsString());
                        }
                        return ResolvedType.normalise(fieldAccessExpr.getScope().toString());
                    }
                    return ResolvedType.normalise(scope.toString());
                })
                .orElse("");
    }

    private <T> Map<String, List<T>> copy(Map<String, List<T>> source) {
        if (source.isEmpty()) {
            return Map.of();
        }
        Map<String, List<T>> snapshot = new LinkedHashMap<>();
        source.forEach((key, value) -> snapshot.put(key, value == null ? List.of() : List.copyOf(value)));
        return Map.copyOf(snapshot);
    }
}
