package com.testagent.entrypoint.pipeline.helpers.analyze;

import java.util.List;

/**
 * Constructor metadata exposed to the LLM for safe instantiation.
 */
public record SemanticTypeConstructor(String className,
                                      List<String> parameterTypes,
                                      String signature) {
    public SemanticTypeConstructor {
        className = className == null ? "" : className.trim();
        signature = signature == null ? "" : signature.trim();
        parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
    }
}
