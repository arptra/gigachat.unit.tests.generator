package com.gigachat.unit.tests.generator.analyzer;

/**
 * Describes a single parameter that belongs to a constructor that was discovered during scanning.
 */
public record ParameterMetadata(String name, String type, String description) {
    public ParameterMetadata {
        name = normalise(name);
        type = normalise(type);
        description = normaliseDescription(description);
    }

    private static String normalise(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    private static String normaliseDescription(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
