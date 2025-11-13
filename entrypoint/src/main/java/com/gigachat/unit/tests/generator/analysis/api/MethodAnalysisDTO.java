package com.gigachat.unit.tests.generator.analysis.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Represents the unified output produced by any {@link MethodAnalyzer}
 * implementation.
 */
public class MethodAnalysisDTO {
    private final Map<String, List<ConstructorInfo>> typeConstructors;
    private final Map<String, List<MethodInfo>> typeMethods;
    private final List<StaticDependency> staticDependencies;
    private final Set<String> domainTypes;

    public MethodAnalysisDTO(Map<String, List<ConstructorInfo>> typeConstructors,
                             Map<String, List<MethodInfo>> typeMethods,
                             List<StaticDependency> staticDependencies,
                             Set<String> domainTypes) {
        this.typeConstructors = wrap(typeConstructors);
        this.typeMethods = wrap(typeMethods);
        this.staticDependencies = staticDependencies == null ? List.of() : List.copyOf(staticDependencies);
        this.domainTypes = domainTypes == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(domainTypes));
    }

    public static MethodAnalysisDTO empty() {
        return new MethodAnalysisDTO(Map.of(), Map.of(), List.of(), Set.of());
    }

    public Map<String, List<ConstructorInfo>> getTypeConstructors() {
        return typeConstructors;
    }

    public List<ConstructorInfo> getConstructors() {
        List<ConstructorInfo> flattened = new ArrayList<>();
        typeConstructors.values().forEach(flattened::addAll);
        return List.copyOf(flattened);
    }

    public Map<String, List<MethodInfo>> getTypeMethods() {
        return typeMethods;
    }

    public Map<String, List<MethodInfo>> getMethods() {
        return typeMethods;
    }

    public List<StaticDependency> getStaticDependencies() {
        return staticDependencies;
    }

    public Set<String> getDomainTypes() {
        return domainTypes;
    }

    private static <T> Map<String, List<T>> wrap(Map<String, List<T>> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, List<T>> copy = new LinkedHashMap<>();
        input.forEach((key, value) -> copy.put(key, value == null ? List.of() : List.copyOf(value)));
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public String toString() {
        return "MethodAnalysisDTO{" +
                "typeConstructors=" + typeConstructors +
                ", typeMethods=" + typeMethods +
                ", staticDependencies=" + staticDependencies +
                ", domainTypes=" + domainTypes +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MethodAnalysisDTO that)) {
            return false;
        }
        return Objects.equals(typeConstructors, that.typeConstructors)
                && Objects.equals(typeMethods, that.typeMethods)
                && Objects.equals(staticDependencies, that.staticDependencies)
                && Objects.equals(domainTypes, that.domainTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeConstructors, typeMethods, staticDependencies, domainTypes);
    }
}
