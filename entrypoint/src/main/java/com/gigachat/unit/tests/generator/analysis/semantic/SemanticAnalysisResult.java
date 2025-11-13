package com.gigachat.unit.tests.generator.analysis.semantic;

import com.gigachat.unit.tests.generator.analysis.api.ConstructorInfo;
import com.gigachat.unit.tests.generator.analysis.api.MethodInfo;
import com.gigachat.unit.tests.generator.analysis.api.StaticDependency;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SemanticAnalysisResult {
    private final List<ConstructorInfo> constructors;
    private final Map<String, List<MethodInfo>> methods;
    private final List<StaticDependency> staticDependencies;
    private final Set<String> collaboratorTypes;
    private final Set<String> domainTypes;

    public SemanticAnalysisResult(List<ConstructorInfo> constructors,
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
}
