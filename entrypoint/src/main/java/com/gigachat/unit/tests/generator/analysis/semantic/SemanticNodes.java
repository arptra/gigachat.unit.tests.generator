package com.gigachat.unit.tests.generator.analysis.semantic;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.List;

public class SemanticNodes {
    private final List<ObjectCreationExpr> creations;
    private final List<MethodCallExpr> methodCalls;
    private final List<MethodCallExpr> staticCalls;
    private final List<Expression> returns;

    public SemanticNodes(List<ObjectCreationExpr> creations,
                         List<MethodCallExpr> methodCalls,
                         List<MethodCallExpr> staticCalls,
                         List<Expression> returns) {
        this.creations = creations == null ? List.of() : List.copyOf(creations);
        this.methodCalls = methodCalls == null ? List.of() : List.copyOf(methodCalls);
        this.staticCalls = staticCalls == null ? List.of() : List.copyOf(staticCalls);
        this.returns = returns == null ? List.of() : List.copyOf(returns);
    }

    public static SemanticNodes empty() {
        return new SemanticNodes(List.of(), List.of(), List.of(), List.of());
    }

    public List<ObjectCreationExpr> getCreations() {
        return creations;
    }

    public List<MethodCallExpr> getMethodCalls() {
        return methodCalls;
    }

    public List<MethodCallExpr> getStaticCalls() {
        return staticCalls;
    }

    public List<Expression> getReturns() {
        return returns;
    }
}
