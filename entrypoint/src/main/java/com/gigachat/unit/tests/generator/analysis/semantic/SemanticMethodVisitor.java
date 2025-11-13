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
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Walks a method AST and records semantic artefacts.
 */
public class SemanticMethodVisitor extends VoidVisitorAdapter<SemanticTypeContext> {
    private final TypeResolver resolver;
    private final SemanticTypeContext context;
    private final List<MethodCallExpr> methodCalls = new ArrayList<>();
    private final List<MethodCallExpr> staticCalls = new ArrayList<>();
    private final List<Expression> returnExpressions = new ArrayList<>();
    private final List<ConstructorSignature> semanticConstructors = new ArrayList<>();
    private final Set<String> domainTypes = new LinkedHashSet<>();

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
        Optional<ResolvedType> ownerType = expr.getScope()
                .flatMap(resolver::resolveOwnerType)
                .or(() -> resolver.resolveOwnerType(null));
        expr.getTypeArguments().ifPresent(arguments -> arguments.forEach(type -> type.accept(this, arg)));
        for (Expression argument : expr.getArguments()) {
            if (argument instanceof LambdaExpr lambdaExpr && ownerType.isPresent()) {
                Map<String, ResolvedType> lambdaAssignments = inferLambdaParameters(expr, ownerType.get(), lambdaExpr);
                withLambdaParameters(lambdaAssignments, () -> lambdaExpr.accept(this, arg));
            } else {
                argument.accept(this, arg);
            }
        }
        ownerType.ifPresent(this::addDomainType);
        expr.getScope().ifPresent(scope -> ownerType.ifPresent(type -> {
            context.registerExpressionType(scope, type);
            addDomainType(type);
        }));
        if (isStaticCall(expr)) {
            staticCalls.add(expr);
        } else {
            methodCalls.add(expr);
        }
        resolver.resolve(expr).ifPresent(type -> {
            context.registerExpressionType(expr, type);
            addDomainType(type);
        });
    }

    private Map<String, ResolvedType> inferLambdaParameters(MethodCallExpr call,
                                                            ResolvedType ownerType,
                                                            LambdaExpr lambdaExpr) {
        Map<String, ResolvedType> assignments = new LinkedHashMap<>();
        if (ownerType == null || ownerType.getGenericArguments().isEmpty()) {
            return assignments;
        }
        List<String> generics = ownerType.getGenericArguments();
        String inferredType = generics.get(0);
        lambdaExpr.getParameters().forEach(parameter -> {
            if (!parameter.getType().isUnknownType()) {
                assignments.put(parameter.getNameAsString(), ResolvedType.of(parameter.getType().asString()));
            } else {
                assignments.put(parameter.getNameAsString(), ResolvedType.of(inferredType));
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

    private boolean isStaticCall(MethodCallExpr expr) {
        return expr.getScope().map(scope -> {
            if (scope.isTypeExpr()) {
                return true;
            }
            if (scope instanceof NameExpr nameExpr) {
                return resolver.resolve(scope).isEmpty() && looksLikeType(nameExpr.getNameAsString());
            }
            if (scope instanceof FieldAccessExpr fieldAccessExpr) {
                return resolver.resolve(scope).isEmpty() && looksLikeType(fieldAccessExpr.getNameAsString());
            }
            return false;
        }).orElse(false);
    }

    private boolean looksLikeType(String value) {
        return value != null && !value.isBlank() && Character.isUpperCase(value.charAt(0));
    }

    @Override
    public void visit(ReturnStmt stmt, SemanticTypeContext arg) {
        stmt.getExpression().ifPresent(expression -> {
            returnExpressions.add(expression);
            resolver.resolve(expression).ifPresent(this::addDomainType);
        });
        super.visit(stmt, arg);
    }

    @Override
    public void visit(ObjectCreationExpr expr, SemanticTypeContext arg) {
        super.visit(expr, arg);
        List<String> parameterTypes = new ArrayList<>();
        for (Expression argument : expr.getArguments()) {
            parameterTypes.add(resolver.resolve(argument)
                    .orElseGet(() -> resolver.inferLiteralType(argument))
                    .getName());
        }
        semanticConstructors.add(new ConstructorSignature(expr.getType().asString(), parameterTypes));
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

    public List<MethodCallExpr> getMethodCalls() {
        return methodCalls;
    }

    public List<MethodCallExpr> getStaticCalls() {
        return staticCalls;
    }

    public List<Expression> getReturnExpressions() {
        return returnExpressions;
    }

    public List<ConstructorSignature> getSemanticConstructors() {
        return semanticConstructors;
    }

    public Set<String> getDomainTypes() {
        return Set.copyOf(domainTypes);
    }

    private void addDomainType(ResolvedType type) {
        if (type == null || type.isUnknown()) {
            return;
        }
        domainTypes.add(type.describe());
        domainTypes.addAll(type.flatten());
    }
}
