package com.gigachat.unit.tests.generator.analysis.semantic;

import com.gigachat.unit.tests.generator.analysis.api.StaticMockStrategy;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StaticDependencyResolver {
    private static final Map<String, String> FIXED_VALUES = Map.of(
            "UUID#randomUUID", "00000000-0000-0000-0000-000000000000",
            "LocalDate#now", "2020-01-01",
            "LocalDateTime#now", "2020-01-01T00:00",
            "Math#random", "0.5d"
    );

    public List<StaticCall> resolve(List<MethodCallExpr> staticCalls) {
        if (staticCalls == null || staticCalls.isEmpty()) {
            return List.of();
        }
        List<StaticCall> result = new ArrayList<>();
        for (MethodCallExpr call : staticCalls) {
            String owner = call.getScope().map(Expression::toString).orElse("Unknown");
            String key = owner + "#" + call.getNameAsString();
            if (FIXED_VALUES.containsKey(key)) {
                result.add(new StaticCall(owner,
                        call.getNameAsString(),
                        StaticMockStrategy.FIXED_VALUE,
                        FIXED_VALUES.get(key)));
            } else {
                result.add(new StaticCall(owner,
                        call.getNameAsString(),
                        StaticMockStrategy.STUB,
                        null));
            }
        }
        return List.copyOf(result);
    }
}
