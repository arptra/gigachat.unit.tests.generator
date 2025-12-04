package com.gigachat.unit.tests.generator.analyzer.semantic;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

class MethodUsageCollector extends VoidVisitorAdapter<Void> {
    private final SignatureRegistry registry;
    private final TypeResolver typeResolver;
    private final SemanticMetadataBuilder metadataBuilder;
    private final Set<String> internalFieldNames;

    MethodUsageCollector(SignatureRegistry registry,
                         TypeResolver typeResolver,
                         SemanticMetadataBuilder metadataBuilder,
                         Set<String> internalFieldNames) {
        this.registry = registry;
        this.typeResolver = typeResolver;
        this.metadataBuilder = metadataBuilder;
        this.internalFieldNames = internalFieldNames == null ? Set.of() : Set.copyOf(internalFieldNames);
    }

    void collect(MethodDeclaration declaration) {
        if (declaration == null || declaration.getBody().isEmpty()) {
            return;
        }
        declaration.getBody().get().accept(this, null);
    }

    @Override
    public void visit(VariableDeclarationExpr expr, Void arg) {
        TypeName declaredType = typeResolver.resolveType(expr.getElementType());
        metadataBuilder.registerContainerType(declaredType);
        expr.getVariables().forEach(variable ->
                typeResolver.registerLocal(variable.getNameAsString(), declaredType));
        super.visit(expr, arg);
    }

    @Override
    public void visit(FieldAccessExpr expr, Void arg) {
        TypeName ownerType = typeResolver.resolveExpressionType(expr.getScope());
        TypeName fieldType = registry.resolveFieldType(ownerType, expr.getNameAsString());
        if (fieldType == null) {
            fieldType = TypeName.unknown();
        }
        metadataBuilder.registerContainerType(fieldType);
        typeResolver.recordExpressionType(expr, fieldType);
        super.visit(expr, arg);
    }

    @Override
    public void visit(MethodCallExpr expr, Void arg) {
        TypeName ownerType = typeResolver.resolveOwnerType(expr);
        List<TypeName> argumentTypes = resolveArguments(expr);
        MethodSignature signature = registry.resolveMethod(ownerType, expr.getNameAsString(), argumentTypes);
        if (signature == null) {
            TypeName inferredOwner = registry.inferOwner(expr.getNameAsString(), argumentTypes);
            if (inferredOwner != null && !inferredOwner.isUnknown()) {
                ownerType = inferredOwner;
                signature = registry.resolveMethod(ownerType, expr.getNameAsString(), argumentTypes);
            }
        }
        boolean internalOwner = isInternalOwner(expr.getScope());
        if (!internalOwner) {
            metadataBuilder.registerOwner(ownerType);
        }
        if (signature != null) {
            TypeName returnType = signature.returnType();
            typeResolver.recordExpressionType(expr, returnType);
            if (!internalOwner) {
                metadataBuilder.registerMethodUsage(ownerType, signature);
                if (signature.isStatic() || registry.isStaticCall(ownerType, expr.getNameAsString())) {
                    metadataBuilder.registerStaticInvocation(ownerType, signature);
                }
            }
            metadataBuilder.registerContainerType(returnType);
        }
        super.visit(expr, arg);
    }

    @Override
    public void visit(ObjectCreationExpr expr, Void arg) {
        TypeName type = TypeName.of(expr.getType().toString());
        metadataBuilder.registerContainerType(type);
        List<TypeName> arguments = resolveExpressions(expr.getArguments());
        ConstructorSignature signature = registry.resolveConstructor(type, arguments);
        if (signature == null) {
            signature = new ConstructorSignature(type, arguments);
        }
        metadataBuilder.registerOwner(type);
        metadataBuilder.registerConstructorUsage(type, signature);
        typeResolver.recordExpressionType(expr, type);
        super.visit(expr, arg);
    }

    private boolean isInternalOwner(java.util.Optional<Expression> scope) {
        if (scope.isEmpty()) {
            return false;
        }
        return isInternalExpression(scope.get());
    }

    private boolean isInternalExpression(Expression expression) {
        if (expression == null) {
            return false;
        }
        if (expression.isNameExpr()) {
            return internalFieldNames.contains(expression.asNameExpr().getNameAsString());
        }
        if (expression.isEnclosedExpr()) {
            EnclosedExpr enclosed = expression.asEnclosedExpr();
            return isInternalExpression(enclosed.getInner());
        }
        if (expression.isFieldAccessExpr()) {
            FieldAccessExpr fieldAccess = expression.asFieldAccessExpr();
            if (internalFieldNames.contains(fieldAccess.getNameAsString())) {
                return true;
            }
            return isInternalExpression(fieldAccess.getScope());
        }
        if (expression.isThisExpr()) {
            return false;
        }
        return internalFieldNames.contains(expression.toString());
    }

    @Override
    public void visit(ReturnStmt stmt, Void arg) {
        stmt.getExpression().ifPresent(expression ->
                metadataBuilder.registerContainerType(typeResolver.resolveExpressionType(expression)));
        super.visit(stmt, arg);
    }

    @Override
    public void visit(LambdaExpr expr, Void arg) {
        TypeName lambdaType = typeResolver.resolveLambdaType(expr);
        metadataBuilder.registerFunctionalInterface(lambdaType);
        typeResolver.enterScope();
        List<TypeName> argumentTypes = lambdaType.typeArguments();
        for (int i = 0; i < expr.getParameters().size(); i++) {
            TypeName argumentType = argumentTypes.isEmpty()
                    ? TypeName.unknown()
                    : argumentTypes.get(Math.min(i, argumentTypes.size() - 1));
            typeResolver.registerLocal(expr.getParameters().get(i).getNameAsString(), argumentType);
        }
        super.visit(expr, arg);
        typeResolver.exitScope();
    }

    private List<TypeName> resolveArguments(MethodCallExpr expr) {
        List<Expression> expressions = expr.getArguments();
        List<TypeName> result = new ArrayList<>(expressions.size());
        for (Expression argument : expressions) {
            if (argument instanceof LambdaExpr lambdaExpr) {
                typeResolver.registerLambdaContext(lambdaExpr, typeResolver.resolveOwnerType(expr), expr.getNameAsString());
                result.add(typeResolver.resolveLambdaType(lambdaExpr));
                continue;
            }
            TypeName type = typeResolver.resolveExpressionType(argument);
            metadataBuilder.registerContainerType(type);
            result.add(type);
        }
        return result;
    }

    private List<TypeName> resolveExpressions(List<Expression> expressions) {
        List<TypeName> result = new ArrayList<>(expressions.size());
        for (Expression expression : expressions) {
            result.add(typeResolver.resolveExpressionType(expression));
        }
        return result;
    }
}
