package com.testagent.entrypoint.pipeline.helpers.analyze;

/**
 * Represents a dependency discovered while analysing a method body.
 */
public record DependencyInfo(String className,
                             String variableName,
                             MockType mockType,
                             String context,
                             boolean externalDependency,
                             boolean internalStructure) {

    public DependencyInfo {
        className = normalise(className, "UnknownDependency");
        variableName = normalise(variableName, "unknown");
        mockType = mockType == null ? MockType.UNKNOWN : mockType;
        context = normalise(context, "");
        internalStructure = internalStructure || !externalDependency;
    }

    private static String normalise(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }
}
