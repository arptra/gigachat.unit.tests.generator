package com.testagent.entrypoint.pipeline.helpers.analyze;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts dependency information from a method body.
 */
public class DependencyAnalyzer {
    private final MockStrategyResolver strategyResolver;

    public DependencyAnalyzer(MockStrategyResolver strategyResolver) {
        this.strategyResolver = strategyResolver;
    }

    public List<DependencyInfo> analyze(BlockStmt body, AnalysisOptions options) {
        if (body == null) {
            return List.of();
        }
        Map<String, DependencyInfo> dependencies = new LinkedHashMap<>();
        body.findAll(ObjectCreationExpr.class).forEach(expr -> handleObjectCreation(expr, dependencies, options));
        body.findAll(FieldAccessExpr.class).forEach(expr -> handleFieldAccess(expr, dependencies, options));
        body.findAll(MethodCallExpr.class).forEach(expr -> handleMethodCall(expr, dependencies, options));
        return List.copyOf(dependencies.values());
    }

    private void handleObjectCreation(ObjectCreationExpr expression,
                                      Map<String, DependencyInfo> dependencies,
                                      AnalysisOptions options) {
        String type = expression.getType().asString();
        if (options.isExcluded(type)) {
            return;
        }
        String variable = resolveAssignedVariable(expression).orElse(expression.getType().getName().getIdentifier());
        MockType mockType = strategyResolver.resolve(expression);
        addDependency(dependencies, new DependencyInfo(type, variable, mockType, expression.toString()));
    }

    private void handleFieldAccess(FieldAccessExpr expression,
                                   Map<String, DependencyInfo> dependencies,
                                   AnalysisOptions options) {
        String target = expression.getScope().toString();
        if (options.isExcluded(target)) {
            return;
        }
        MockType mockType = strategyResolver.resolve(expression);
        addDependency(dependencies, new DependencyInfo(target, expression.getNameAsString(), mockType, expression.toString()));
    }

    private void handleMethodCall(MethodCallExpr expression,
                                  Map<String, DependencyInfo> dependencies,
                                  AnalysisOptions options) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return;
        }
        Expression scopeExpression = scope.get();
        if (scopeExpression instanceof NameExpr nameExpr) {
            String identifier = nameExpr.getNameAsString();
            if (options.isExcluded(identifier)) {
                return;
            }
            MockType mockType = strategyResolver.resolve(expression);
            if (mockType == MockType.STATIC) {
                addDependency(dependencies, new DependencyInfo(identifier, expression.getNameAsString(), mockType, expression.toString()));
            }
        }
    }

    private Optional<String> resolveAssignedVariable(ObjectCreationExpr expression) {
        return expression.getParentNode()
                .flatMap(parent -> {
                    if (parent instanceof VariableDeclarator declarator) {
                        return Optional.ofNullable(declarator.getName()).map(SimpleName::getIdentifier);
                    }
                    if (parent instanceof AssignExpr assignExpr) {
                        Expression target = assignExpr.getTarget();
                        if (target instanceof NameExpr nameExpr) {
                            return Optional.of(nameExpr.getNameAsString());
                        }
                    }
                    return Optional.empty();
                });
    }

    private void addDependency(Map<String, DependencyInfo> dependencies, DependencyInfo info) {
        dependencies.putIfAbsent(info.className() + "#" + info.variableName(), info);
    }
}
