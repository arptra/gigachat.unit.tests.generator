package com.gigachat.unit.tests.generator.analyzer.semantic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable DTO that describes the semantic footprint of a single method.
 */
public final class SemanticMethodAnalysis {
    private final Set<TypeName> domainTypes;
    private final Map<TypeName, List<MethodSignature>> typeMethods;
    private final Map<TypeName, List<ConstructorSignature>> typeConstructors;
    private final List<StaticInvocation> staticCalls;

    public SemanticMethodAnalysis(Set<TypeName> domainTypes,
                                  Map<TypeName, List<MethodSignature>> typeMethods,
                                  Map<TypeName, List<ConstructorSignature>> typeConstructors,
                                  List<StaticInvocation> staticCalls) {
        this.domainTypes = domainTypes == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(domainTypes));
        this.typeMethods = wrapMap(typeMethods);
        this.typeConstructors = wrapConstructors(typeConstructors);
        this.staticCalls = staticCalls == null ? List.of() : List.copyOf(staticCalls);
    }

    public Set<TypeName> domainTypes() {
        return domainTypes;
    }

    public Map<TypeName, List<MethodSignature>> typeMethods() {
        return typeMethods;
    }

    public Map<TypeName, List<ConstructorSignature>> typeConstructors() {
        return typeConstructors;
    }

    public List<StaticInvocation> staticCalls() {
        return staticCalls;
    }

    private Map<TypeName, List<MethodSignature>> wrapMap(Map<TypeName, List<MethodSignature>> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<TypeName, List<MethodSignature>> snapshot = new LinkedHashMap<>();
        map.forEach((key, value) -> snapshot.put(key, value == null ? List.of() : List.copyOf(value)));
        return Collections.unmodifiableMap(snapshot);
    }

    private Map<TypeName, List<ConstructorSignature>> wrapConstructors(Map<TypeName, List<ConstructorSignature>> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<TypeName, List<ConstructorSignature>> snapshot = new LinkedHashMap<>();
        map.forEach((key, value) -> snapshot.put(key, value == null ? List.of() : List.copyOf(value)));
        return Collections.unmodifiableMap(snapshot);
    }
}
