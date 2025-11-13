package com.gigachat.unit.tests.generator.analysis.semantic;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a method AST and extracts only the expressions that carry semantic
 * weight for downstream analysis.
 */
public class SemanticNodeExtractor {
    public SemanticNodes extract(MethodDeclaration method) {
        if (method == null || method.getBody().isEmpty()) {
            return SemanticNodes.empty();
        }

        List<ObjectCreationExpr> creations = new ArrayList<>();
        List<MethodCallExpr> methodCalls = new ArrayList<>();
        List<MethodCallExpr> staticCalls = new ArrayList<>();
        List<Expression> returns = new ArrayList<>();

        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ObjectCreationExpr expr, Void arg) {
                super.visit(expr, arg);
                creations.add(expr);
            }

            @Override
            public void visit(MethodCallExpr expr, Void arg) {
                super.visit(expr, arg);
                if (expr.getScope().filter(Expression::isTypeExpr).isPresent()) {
                    staticCalls.add(expr);
                } else {
                    methodCalls.add(expr);
                }
            }

            @Override
            public void visit(ReturnStmt stmt, Void arg) {
                super.visit(stmt, arg);
                stmt.getExpression().ifPresent(returns::add);
            }
        }, null);

        return new SemanticNodes(creations, methodCalls, staticCalls, returns);
    }
}
