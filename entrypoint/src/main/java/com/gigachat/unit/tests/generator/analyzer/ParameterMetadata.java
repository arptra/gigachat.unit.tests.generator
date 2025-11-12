package com.gigachat.unit.tests.generator.analyzer;

/**
 * Describes a single constructor parameter discovered during scanning.
 */
public record ParameterMetadata(String name, String type) {
    public ParameterMetadata {
        name = normalise(name);
        type = normalise(type);
    }

    private static String normalise(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }
}
