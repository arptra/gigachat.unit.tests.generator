package com.testagent.entrypoint.pipeline.helpers.analyze;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the semantic view of a method body used to provide deep context for the LLM.
 */
public record SemanticAnalysis(Map<String, List<SemanticTypeMethod>> typeMethods,
                               Map<String, List<SemanticTypeConstructor>> typeConstructors,
                               List<SemanticStaticCall> staticCalls,
                               List<String> domainTypes) {

    public SemanticAnalysis {
        typeMethods = sanitiseTypeMap(typeMethods);
        typeConstructors = sanitiseConstructorMap(typeConstructors);
        staticCalls = staticCalls == null ? List.of() : List.copyOf(staticCalls);
        domainTypes = domainTypes == null ? List.of() : List.copyOf(domainTypes);
    }

    public static SemanticAnalysis empty() {
        return new SemanticAnalysis(Map.of(), Map.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return typeMethods.isEmpty() && typeConstructors.isEmpty() && staticCalls.isEmpty() && domainTypes.isEmpty();
    }

    private Map<String, List<SemanticTypeMethod>> sanitiseTypeMap(Map<String, List<SemanticTypeMethod>> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, List<SemanticTypeMethod>> snapshot = new LinkedHashMap<>();
        raw.forEach((type, methods) -> {
            if (type == null || type.isBlank() || methods == null || methods.isEmpty()) {
                return;
            }
            List<SemanticTypeMethod> copy = new ArrayList<>(methods.size());
            for (SemanticTypeMethod method : methods) {
                if (method == null) {
                    continue;
                }
                copy.add(method);
            }
            if (!copy.isEmpty()) {
                snapshot.put(type, List.copyOf(copy));
            }
        });
        return Map.copyOf(snapshot);
    }

    private Map<String, List<SemanticTypeConstructor>> sanitiseConstructorMap(Map<String, List<SemanticTypeConstructor>> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, List<SemanticTypeConstructor>> snapshot = new LinkedHashMap<>();
        raw.forEach((type, constructors) -> {
            if (type == null || type.isBlank() || constructors == null || constructors.isEmpty()) {
                return;
            }
            List<SemanticTypeConstructor> copy = new ArrayList<>(constructors.size());
            for (SemanticTypeConstructor constructor : constructors) {
                if (constructor == null) {
                    continue;
                }
                copy.add(constructor);
            }
            if (!copy.isEmpty()) {
                snapshot.put(type, List.copyOf(copy));
            }
        });
        return Map.copyOf(snapshot);
    }
}
