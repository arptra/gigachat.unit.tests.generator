package com.gigachat.unit.tests.generator.analysis.semantic;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts semantic domain types referenced inside a method body.
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
                    .forEach(expr -> expr.getVariables().forEach(variable ->
                            addResolvedType(ResolvedType.of(variable.getType().asString()), domainTypes)));
        }
        for (MethodCallExpr call : methodCalls) {
            call.getScope()
                    .flatMap(resolver::resolveOwnerType)
                    .ifPresent(type -> addResolvedType(type, domainTypes));
        }
        for (ConstructorSignature signature : constructorSignatures) {
            if (signature == null) {
                continue;
            }
            addResolvedType(ResolvedType.of(signature.getTypeName()), domainTypes);
        }
        for (Expression expression : returnExpressions) {
            resolver.resolve(expression).ifPresent(type -> addResolvedType(type, domainTypes));
        }
        if (declaredReturnType != null && !declaredReturnType.isBlank()) {
            addResolvedType(ResolvedType.of(declaredReturnType), domainTypes);
        }
        return Set.copyOf(domainTypes);
    }

    private void addResolvedType(ResolvedType type, Set<String> domainTypes) {
        if (type == null || type.isUnknown()) {
            return;
        }
        domainTypes.addAll(type.flatten());
    }
}
