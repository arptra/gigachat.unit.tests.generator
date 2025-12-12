package com.testagent.entrypoint.pipeline.helpers.analyze;

import java.util.List;

/**
 * Describes a method that can be safely used for a specific domain type.
 */
public record SemanticTypeMethod(boolean isStatic,
                                 String name,
                                 List<String> parameterTypes,
                                 String returnType) {
    public SemanticTypeMethod {
        name = name == null ? "" : name.trim();
        returnType = returnType == null ? "" : returnType.trim();
        parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
    }
}
