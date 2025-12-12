package com.gigachat.unit.tests.generator.cleaner.rules.classlevel;

import com.gigachat.unit.tests.generator.cleaner.TestFileContext;
import com.gigachat.unit.tests.generator.cleaner.rules.classlevel.api.TestClassCleanerRule;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Removes test methods that only contain placeholder assertions such as {@code assertTrue(true)}
 * or {@code assertEquals(true, true)}.
 */
public final class StubAssertionRemovalRule implements TestClassCleanerRule {
    @Override
    public boolean apply(TestFileContext context) throws IOException {
        CompilationUnit unit = context.getCompilationUnit().orElse(null);
        if (unit == null) {
            return false;
        }
        List<MethodDeclaration> methods = new ArrayList<>(unit.findAll(MethodDeclaration.class));
        boolean changed = false;
        for (MethodDeclaration method : methods) {
            if (!hasTestAnnotation(method)) {
                continue;
            }
            if (method.getBody().isEmpty()) {
                method.remove();
                changed = true;
                continue;
            }
            if (isStub(method)) {
                method.remove();
                changed = true;
            }
        }
        if (changed) {
            context.markAstDirty();
        }
        return changed;
    }

    private boolean hasTestAnnotation(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .map(annotation -> annotation.getName().getIdentifier())
                .anyMatch(name -> name.endsWith("Test"));
    }

    private boolean isStub(MethodDeclaration method) {
        List<Statement> statements = method.getBody()
                .map(body -> new ArrayList<>(body.getStatements()))
                .orElseGet(ArrayList::new);
        if (statements.isEmpty()) {
            return true;
        }
        for (Statement statement : statements) {
            if (!(statement instanceof ExpressionStmt expressionStmt)) {
                return false;
            }
            if (!(expressionStmt.getExpression() instanceof MethodCallExpr callExpr)) {
                return false;
            }
            String name = callExpr.getName().getIdentifier().toLowerCase();
            if (!name.startsWith("assert")) {
                return false;
            }
            if (!allBooleanLiterals(callExpr)) {
                return false;
            }
        }
        return true;
    }

    private boolean allBooleanLiterals(MethodCallExpr expression) {
        if (expression.getArguments().isEmpty()) {
            return false;
        }
        boolean allTrue = true;
        boolean allFalse = true;
        for (var argument : expression.getArguments()) {
            if (!(argument instanceof BooleanLiteralExpr literal)) {
                return false;
            }
            allTrue &= literal.getValue();
            allFalse &= !literal.getValue();
        }
        return allTrue || allFalse;
    }
}
