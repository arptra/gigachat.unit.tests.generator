package com.testagent.entrypoint.pipeline.helpers.analyze;

/**
 * Metadata describing the analysed method.
 */
public record MethodMetadata(String name,
                             String signature,
                             String returnType) {

    public MethodMetadata {
        name = normalise(name, "method");
        signature = normalise(signature, name + "()");
        returnType = normalise(returnType, "void");
    }

    private static String normalise(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }
}
