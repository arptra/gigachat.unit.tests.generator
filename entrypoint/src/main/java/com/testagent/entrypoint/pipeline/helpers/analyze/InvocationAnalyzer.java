package com.testagent.entrypoint.pipeline.helpers.analyze;

import com.gigachat.unit.tests.generator.config.AnalysisConfig;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Analyses method call chains and static invocations within a method body.
 */
public class InvocationAnalyzer {
    private final MockStrategyResolver strategyResolver;

    public InvocationAnalyzer(MockStrategyResolver strategyResolver) {
        this.strategyResolver = strategyResolver;
    }

    public InvocationAnalysis analyze(BlockStmt body, AnalysisConfig options) {
        if (body == null) {
            return InvocationAnalysis.empty();
        }
        List<InvocationInfo> invocations = new ArrayList<>();
        Set<String> staticUsages = new LinkedHashSet<>();
        Set<String> unresolved = new LinkedHashSet<>();
        body.findAll(MethodCallExpr.class).forEach(expr -> handleMethodCall(expr, options, invocations, staticUsages, unresolved));
        return new InvocationAnalysis(List.copyOf(invocations), List.copyOf(staticUsages), List.copyOf(unresolved));
    }

    private void handleMethodCall(MethodCallExpr expression,
                                  AnalysisConfig options,
                                  List<InvocationInfo> invocations,
                                  Set<String> staticUsages,
                                  Set<String> unresolved) {
        MockType mockType = strategyResolver.resolve(expression);
        int chainDepth = determineChainDepth(expression);
        if (chainDepth > options.maxChainDepth()) {
            return;
        }
        Optional<Expression> scopeOptional = expression.getScope();
        String methodName = expression.getNameAsString();
        if (mockType == MockType.STATIC) {
            if (options.includeStatic()) {
                String scope = scopeOptional.map(Expression::toString).orElse("");
                staticUsages.add(scope.isBlank() ? methodName : scope + '.' + methodName);
            }
            return;
        }
        if (scopeOptional.isEmpty()) {
            unresolved.add(methodName);
            return;
        }
        String target = scopeOptional.map(Expression::toString).orElse("");
        if (target.isBlank()) {
            unresolved.add(methodName);
            return;
        }
        if (options.isExcluded(target)) {
            return;
        }
        List<String> args = expression.getArguments().stream()
                .map(this::normaliseArgument)
                .toList();
        invocations.add(new InvocationInfo(target, methodName, args));
    }

    private int determineChainDepth(MethodCallExpr expression) {
        int depth = 1;
        Optional<Expression> scope = expression.getScope();
        while (scope.isPresent()) {
            Expression scopeExpression = scope.get();
            if (scopeExpression instanceof MethodCallExpr methodCallExpr) {
                depth++;
                scope = methodCallExpr.getScope();
            } else {
                break;
            }
        }
        return depth;
    }

    private String normaliseArgument(Expression expression) {
        if (expression instanceof NameExpr nameExpr) {
            return nameExpr.getNameAsString();
        }
        if (expression instanceof StringLiteralExpr) {
            return "String";
        }
        String text = expression.toString();
        if (text.length() > 40) {
            return text.substring(0, 37) + "...";
        }
        return text;
    }

    public record InvocationAnalysis(List<InvocationInfo> invocations,
                                     List<String> staticUsages,
                                     List<String> unresolved) {
        public static InvocationAnalysis empty() {
            return new InvocationAnalysis(List.of(), List.of(), List.of());
        }
    }
}
