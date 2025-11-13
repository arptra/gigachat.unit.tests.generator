package com.gigachat.unit.tests.generator.analysis.api;

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
    private final List<ConstructorInfo> constructors;
    private final Map<String, List<MethodInfo>> methods;
    private final List<StaticDependency> staticDependencies;
    private final Set<String> collaboratorTypes;
    private final Set<String> domainTypes;

    public MethodAnalysisDTO(List<ConstructorInfo> constructors,
                             Map<String, List<MethodInfo>> methods,
                             List<StaticDependency> staticDependencies,
                             Set<String> collaboratorTypes,
                             Set<String> domainTypes) {
        this.constructors = constructors == null ? List.of() : List.copyOf(constructors);
        if (methods == null || methods.isEmpty()) {
            this.methods = Map.of();
        } else {
            Map<String, List<MethodInfo>> copy = new LinkedHashMap<>();
            methods.forEach((key, value) -> copy.put(key, value == null ? List.of() : List.copyOf(value)));
            this.methods = Collections.unmodifiableMap(copy);
        }
        this.staticDependencies = staticDependencies == null ? List.of() : List.copyOf(staticDependencies);
        this.collaboratorTypes = collaboratorTypes == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(collaboratorTypes));
        this.domainTypes = domainTypes == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(domainTypes));
    }

    public static MethodAnalysisDTO empty() {
        return new MethodAnalysisDTO(List.of(), Map.of(), List.of(), Set.of(), Set.of());
    }

    public List<ConstructorInfo> getConstructors() {
        return constructors;
    }

    public Map<String, List<MethodInfo>> getMethods() {
        return methods;
    }

    public List<StaticDependency> getStaticDependencies() {
        return staticDependencies;
    }

    public Set<String> getCollaboratorTypes() {
        return collaboratorTypes;
    }

    public Set<String> getDomainTypes() {
        return domainTypes;
    }

    @Override
    public String toString() {
        return "MethodAnalysisDTO{" +
                "constructors=" + constructors +
                ", methods=" + methods +
                ", staticDependencies=" + staticDependencies +
                ", collaboratorTypes=" + collaboratorTypes +
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
        return Objects.equals(constructors, that.constructors)
                && Objects.equals(methods, that.methods)
                && Objects.equals(staticDependencies, that.staticDependencies)
                && Objects.equals(collaboratorTypes, that.collaboratorTypes)
                && Objects.equals(domainTypes, that.domainTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(constructors, methods, staticDependencies, collaboratorTypes, domainTypes);
    }
}
