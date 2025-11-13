package com.gigachat.unit.tests.generator.analysis.semantic;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a method AST and records semantic artefacts.
 */
public class SemanticMethodVisitor extends VoidVisitorAdapter<Void> {
    private final TypeResolver resolver;
    private final List<MethodCallExpr> methodCalls = new ArrayList<>();
    private final List<MethodCallExpr> staticCalls = new ArrayList<>();
    private final List<Expression> returnExpressions = new ArrayList<>();
    private final List<ConstructorSignature> semanticConstructors = new ArrayList<>();

    public SemanticMethodVisitor(TypeResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void visit(MethodCallExpr expr, Void arg) {
        super.visit(expr, arg);
        if (isStaticCall(expr)) {
            staticCalls.add(expr);
        } else {
            methodCalls.add(expr);
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
        if (value == null || value.isBlank()) {
            return false;
        }
        char first = value.charAt(0);
        return Character.isUpperCase(first);
    }

    @Override
    public void visit(ReturnStmt stmt, Void arg) {
        super.visit(stmt, arg);
        stmt.getExpression().ifPresent(returnExpressions::add);
    }

    @Override
    public void visit(ObjectCreationExpr expr, Void arg) {
        super.visit(expr, arg);
        List<String> parameterTypes = new ArrayList<>();
        for (Expression argument : expr.getArguments()) {
            parameterTypes.add(resolver.resolve(argument)
                    .orElseGet(() -> resolver.inferLiteralType(argument)));
        }
        semanticConstructors.add(new ConstructorSignature(expr.getType().asString(), parameterTypes));
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
}
