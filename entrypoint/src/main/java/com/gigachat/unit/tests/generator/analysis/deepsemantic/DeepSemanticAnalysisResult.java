package com.gigachat.unit.tests.generator.analysis.deepsemantic;

import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticAnalysis;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticStaticCall;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticTypeConstructor;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticTypeMethod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class DeepSemanticAnalysisResult {
    private final DeepSemanticTypeResolver resolver;
    private final LinkedHashSet<String> domainTypes = new LinkedHashSet<>();
    private final Map<String, DeepSemanticTypeUsage> typeUsages = new LinkedHashMap<>();
    private final List<SemanticStaticCall> staticCalls = new ArrayList<>();

    DeepSemanticAnalysisResult(DeepSemanticTypeResolver resolver) {
        this.resolver = resolver;
    }

    void addDomainType(String typeName) {
        String normalised = resolver.normalise(typeName);
        if (normalised.isEmpty() || resolver.isNoise(normalised)) {
            return;
        }
        domainTypes.add(normalised);
        typeUsages.computeIfAbsent(normalised, DeepSemanticTypeUsage::new);
    }

    DeepSemanticTypeUsage usageFor(String typeName) {
        addDomainType(typeName);
        return typeUsages.get(resolver.normalise(typeName));
    }

    void addStaticCall(SemanticStaticCall call) {
        if (call == null || call.ownerType().isBlank() || call.methodName().isBlank()) {
            return;
        }
        staticCalls.add(call);
    }

    List<String> domainTypes() {
        return List.copyOf(domainTypes);
    }

    SemanticAnalysis toSemanticAnalysis() {
        LinkedHashMap<String, List<SemanticTypeMethod>> methods = new LinkedHashMap<>();
        LinkedHashMap<String, List<SemanticTypeConstructor>> constructors = new LinkedHashMap<>();
        typeUsages.forEach((type, usage) -> {
            if (!usage.methods().isEmpty()) {
                methods.put(type, usage.methods());
            }
            if (!usage.constructors().isEmpty()) {
                constructors.put(type, usage.constructors());
            }
        });
        return new SemanticAnalysis(methods, constructors, List.copyOf(staticCalls), List.copyOf(domainTypes));
    }
}
