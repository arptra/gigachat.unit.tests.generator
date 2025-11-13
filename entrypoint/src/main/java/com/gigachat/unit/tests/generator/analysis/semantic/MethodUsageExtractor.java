package com.gigachat.unit.tests.generator.analysis.semantic;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            String owner = call.getScope()
                    .flatMap(resolver::resolve)
                    .orElse(TypeResolver.UNKNOWN_TYPE);
            List<String> parameterTypes = new ArrayList<>();
            for (Expression argument : call.getArguments()) {
                parameterTypes.add(resolver.resolve(argument)
                        .orElseGet(() -> resolver.inferLiteralType(argument)));
            }
            MethodSignature signature = new MethodSignature(owner,
                    call.getNameAsString(),
                    parameterTypes,
                    TypeResolver.UNKNOWN_TYPE);
            result.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(signature);
        }
        result.replaceAll((type, signatures) -> List.copyOf(signatures));
        return Map.copyOf(result);
    }
}
