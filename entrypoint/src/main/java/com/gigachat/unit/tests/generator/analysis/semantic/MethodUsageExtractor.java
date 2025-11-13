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
            List<String> parameterTypes = resolveParameterTypes(call, resolver, ownerType.get());
            String typeName = ownerType.get().describe();
            if (typeName.isBlank()) {
                continue;
            }
            String returnType = resolver.resolve(call)
                    .map(ResolvedType::describe)
                    .orElse(TypeResolver.UNKNOWN_TYPE);
            MethodSignature signature = new MethodSignature(typeName,
                    call.getNameAsString(),
                    parameterTypes,
                    returnType);
            result.computeIfAbsent(typeName, ignored -> new ArrayList<>()).add(signature);
        }
        result.replaceAll((key, value) -> value == null ? List.of() : List.copyOf(value));
        return Map.copyOf(result);
    }

    private List<String> resolveParameterTypes(MethodCallExpr call,
                                               TypeResolver resolver,
                                               ResolvedType ownerType) {
        List<String> parameterTypes = new ArrayList<>(call.getArguments().size());
        for (Expression argument : call.getArguments()) {
            parameterTypes.add(resolveParameterType(resolver, argument));
        }
        return enrichCollectionMethodParameters(ownerType, call.getNameAsString(), parameterTypes);
    }

    private List<String> enrichCollectionMethodParameters(ResolvedType ownerType,
                                                          String methodName,
                                                          List<String> parameterTypes) {
        if (ownerType == null || ownerType.getName().isBlank()) {
            return parameterTypes;
        }
        if (!"List".equals(ownerType.getName())) {
            return parameterTypes;
        }
        List<String> generics = ownerType.getGenericArguments();
        if (generics.isEmpty()) {
            return parameterTypes;
        }
        String elementType = ResolvedType.normalise(generics.get(0));
        if (elementType.isBlank()) {
            return parameterTypes;
        }
        if ("removeIf".equals(methodName)) {
            return List.of("Predicate<" + elementType + ">");
        }
        if ("add".equals(methodName) && !parameterTypes.isEmpty()) {
            List<String> enriched = new ArrayList<>(parameterTypes);
            if (enriched.get(0) == null
                    || enriched.get(0).isBlank()
                    || TypeResolver.UNKNOWN_TYPE.equals(enriched.get(0))) {
                enriched.set(0, elementType);
            }
            return enriched;
        }
        return parameterTypes;
    }

    private String resolveParameterType(TypeResolver resolver, Expression argument) {
        Optional<ResolvedType> resolved = resolver.resolve(argument);
        if (resolved.isEmpty() || resolved.get().isUnknown()) {
            ResolvedType literal = resolver.inferLiteralType(argument);
            if (!literal.isUnknown()) {
                resolved = Optional.of(literal);
            }
        }
        return resolved.map(ResolvedType::describe).orElse(TypeResolver.UNKNOWN_TYPE);
    }
}
