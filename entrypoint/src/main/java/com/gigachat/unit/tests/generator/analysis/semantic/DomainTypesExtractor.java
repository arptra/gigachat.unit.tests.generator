package com.gigachat.unit.tests.generator.analysis.semantic;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts unique domain types referenced inside a method body.
 */
public class DomainTypesExtractor {
    public Set<String> extract(MethodDeclaration declaration,
                               List<MethodCallExpr> methodCalls,
                               List<Expression> returnExpressions,
                               List<ConstructorSignature> constructorSignatures,
                               TypeResolver resolver,
                               String declaredReturnType) {
        LinkedHashSet<String> domainTypes = new LinkedHashSet<>();
        if (declaration != null) {
            declaration.findAll(VariableDeclarationExpr.class)
                    .forEach(expr -> expr.getVariables()
                            .forEach(variable -> addTypeTokens(variable.getType().asString(), domainTypes)));
        }
        for (MethodCallExpr call : methodCalls) {
            call.getScope().flatMap(resolver::resolve)
                    .ifPresent(type -> addTypeTokens(type, domainTypes));
        }
        for (ConstructorSignature signature : constructorSignatures) {
            addTypeTokens(signature.getTypeName(), domainTypes);
        }
        for (Expression expression : returnExpressions) {
            resolver.resolve(expression).ifPresent(type -> addTypeTokens(type, domainTypes));
        }
        if (domainTypes.isEmpty() && declaredReturnType != null && !declaredReturnType.isBlank()) {
            addTypeTokens(declaredReturnType, domainTypes);
        }
        return Set.copyOf(domainTypes);
    }

    private void addTypeTokens(String rawType, Set<String> accumulator) {
        if (rawType == null || rawType.isBlank()) {
            return;
        }
        List<String> exploded = TypeResolver.explodeTypes(rawType);
        if (exploded.isEmpty()) {
            return;
        }
        accumulator.addAll(exploded);
    }
}
