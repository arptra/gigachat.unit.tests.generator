package com.gigachat.unit.tests.generator.analysis.semantic;

import com.gigachat.unit.tests.generator.analysis.api.StaticMockStrategy;
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

    public List<StaticCall> resolve(List<RawStaticCall> staticCalls) {
        if (staticCalls == null || staticCalls.isEmpty()) {
            return List.of();
        }
        List<StaticCall> result = new ArrayList<>();
        for (RawStaticCall call : staticCalls) {
            if (call == null) {
                continue;
            }
            String owner = call.getOwner().isBlank() ? "Unknown" : call.getOwner();
            String key = owner + "#" + call.getMethodName();
            if (FIXED_VALUES.containsKey(key)) {
                result.add(new StaticCall(owner,
                        call.getMethodName(),
                        StaticMockStrategy.FIXED_VALUE,
                        FIXED_VALUES.get(key)));
            } else {
                result.add(new StaticCall(owner,
                        call.getMethodName(),
                        StaticMockStrategy.STUB,
                        null));
            }
        }
        return List.copyOf(result);
    }
}
