package com.gigachat.unit.tests.generator.analysis.semantic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SemanticAnalysisResult {
    private final Set<String> domainTypes;
    private final Map<String, List<MethodSignature>> typeMethods;
    private final Map<String, List<ConstructorSignature>> typeConstructors;
    private final List<StaticCall> staticCalls;

    public SemanticAnalysisResult(Set<String> domainTypes,
                                  Map<String, List<MethodSignature>> typeMethods,
                                  Map<String, List<ConstructorSignature>> typeConstructors,
                                  List<StaticCall> staticCalls) {
        this.domainTypes = domainTypes == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(domainTypes));
        this.typeMethods = wrap(typeMethods);
        this.typeConstructors = wrap(typeConstructors);
        this.staticCalls = staticCalls == null ? List.of() : List.copyOf(staticCalls);
    }

    private static <T> Map<String, List<T>> wrap(Map<String, List<T>> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, List<T>> snapshot = new LinkedHashMap<>();
        input.forEach((key, value) -> snapshot.put(key, value == null ? List.of() : List.copyOf(value)));
        return Collections.unmodifiableMap(snapshot);
    }

    public Set<String> getDomainTypes() {
        return domainTypes;
    }

    public Map<String, List<MethodSignature>> getTypeMethods() {
        return typeMethods;
    }

    public Map<String, List<ConstructorSignature>> getTypeConstructors() {
        return typeConstructors;
    }

    public List<StaticCall> getStaticCalls() {
        return staticCalls;
    }
}
