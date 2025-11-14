package com.gigachat.unit.tests.generator.analysis.deepsemantic;

import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticTypeConstructor;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticTypeMethod;

import java.util.LinkedHashSet;
import java.util.List;

final class DeepSemanticTypeUsage {
    private final String typeName;
    private final LinkedHashSet<SemanticTypeMethod> methods = new LinkedHashSet<>();
    private final LinkedHashSet<SemanticTypeConstructor> constructors = new LinkedHashSet<>();

    DeepSemanticTypeUsage(String typeName) {
        this.typeName = typeName;
    }

    void addMethod(SemanticTypeMethod method) {
        if (method == null || method.name().isBlank()) {
            return;
        }
        methods.add(method);
    }

    void addConstructor(SemanticTypeConstructor constructor) {
        if (constructor == null || constructor.signature().isBlank()) {
            return;
        }
        constructors.add(constructor);
    }

    List<SemanticTypeMethod> methods() {
        return List.copyOf(methods);
    }

    List<SemanticTypeConstructor> constructors() {
        return List.copyOf(constructors);
    }

    String typeName() {
        return typeName;
    }
}
