package com.testagent.entrypoint.pipeline.helpers.analyze;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.ThisExpr;

/**
 * Heuristics used to infer the most appropriate mock type for a dependency usage.
 */
public class MockStrategyResolver {

    public MockType resolve(ObjectCreationExpr expression) {
        return MockType.CONSTRUCTOR;
    }

    public MockType resolve(FieldAccessExpr expression) {
        Expression scope = expression.getScope();
        if (scope instanceof ThisExpr || scope instanceof NameExpr) {
            return MockType.FIELD;
        }
        return MockType.UNKNOWN;
    }

    public MockType resolve(MethodCallExpr expression) {
        if (expression.getScope().isEmpty()) {
            return MockType.UNKNOWN;
        }
        Expression scope = expression.getScope().get();
        if (scope instanceof MethodCallExpr) {
            return MockType.CHAIN;
        }
        if (isLikelyStaticScope(scope)) {
            return MockType.STATIC;
        }
        return MockType.FIELD;
    }

    private boolean isLikelyStaticScope(Expression scope) {
        if (scope instanceof NameExpr nameExpr) {
            return startsWithUppercase(nameExpr.getName());
        }
        if (scope instanceof FieldAccessExpr fieldAccessExpr) {
            return isLikelyStaticScope(fieldAccessExpr.getScope());
        }
        return false;
    }

    private boolean startsWithUppercase(SimpleName name) {
        if (name == null) {
            return false;
        }
        String identifier = name.getIdentifier();
        return !identifier.isEmpty() && Character.isUpperCase(identifier.charAt(0));
    }
}
