package com.gigachat.unit.tests.generator.analyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Describes a single constructor parameter discovered during scanning.
 */
public record ParameterMetadata(String name, String type, List<String> modifiers) {
    public ParameterMetadata {
        name = normalise(name);
        type = normalise(type);
        modifiers = normaliseModifiers(modifiers);
    }

    private static String normalise(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    private static List<String> normaliseModifiers(List<String> modifiers) {
        if (modifiers == null || modifiers.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>(modifiers.size());
        for (String modifier : modifiers) {
            String normalised = normalise(modifier);
            if (!normalised.isEmpty()) {
                cleaned.add(normalised);
            }
        }
        if (cleaned.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(cleaned);
    }
}
