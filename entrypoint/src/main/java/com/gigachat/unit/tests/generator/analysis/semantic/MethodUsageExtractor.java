package com.gigachat.unit.tests.generator.analysis.semantic;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Collects method invocations grouped by the resolved receiver type.
 */
public class MethodUsageExtractor {
    public Map<String, List<MethodSignature>> extract(List<MethodCallExpr> calls, TypeResolver resolver) {
        if (calls == null || calls.isEmpty()) {
            return Map.of();
        }
        Map<String, List<MethodSignature>> result = new LinkedHashMap<>();
        for (MethodCallExpr call : calls) {
            Optional<ResolvedType> ownerType = call.getScope()
                    .flatMap(resolver::resolveOwnerType)
                    .or(() -> resolver.resolveOwnerType(null));
            if (ownerType.isEmpty() || ownerType.get().isUnknown()) {
                continue;
            }
            List<String> parameterTypes = new ArrayList<>(call.getArguments().size());
            for (Expression argument : call.getArguments()) {
                parameterTypes.add(resolveParameterType(resolver, argument));
            }
            String typeName = ownerType.get().getName();
            MethodSignature signature = new MethodSignature(typeName,
                    call.getNameAsString(),
                    parameterTypes,
                    TypeResolver.UNKNOWN_TYPE);
            result.computeIfAbsent(typeName, ignored -> new ArrayList<>()).add(signature);
        }
        result.replaceAll((key, value) -> value == null ? List.of() : List.copyOf(value));
        return Map.copyOf(result);
    }

    private String resolveParameterType(TypeResolver resolver, Expression argument) {
        return resolver.resolve(argument)
                .or(() -> Optional.of(resolver.inferLiteralType(argument)))
                .map(ResolvedType::getName)
                .orElse(TypeResolver.UNKNOWN_TYPE);
    }
}
