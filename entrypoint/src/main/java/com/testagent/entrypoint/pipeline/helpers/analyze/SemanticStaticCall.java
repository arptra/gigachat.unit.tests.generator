package com.testagent.entrypoint.pipeline.helpers.analyze;

import java.util.List;

/**
 * Captures metadata about static helper invocations within the analysed method.
 */
public record SemanticStaticCall(String ownerType,
                                 String methodName,
                                 List<String> parameterTypes) {
    public SemanticStaticCall {
        ownerType = ownerType == null ? "" : ownerType.trim();
        methodName = methodName == null ? "" : methodName.trim();
        parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
    }
}
