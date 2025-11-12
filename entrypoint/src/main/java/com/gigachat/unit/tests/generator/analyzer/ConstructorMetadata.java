package com.gigachat.unit.tests.generator.analyzer;

import java.util.List;

/**
 * Detailed representation of a constructor discovered within the project, including its signature and
 * parameter metadata. The structure is intentionally minimal so it serialises cleanly into the prompt JSON
 * consumed by the LLM.
 */
public record ConstructorMetadata(String signature, List<ParameterMetadata> parameters) {

    public ConstructorMetadata {
        signature = normaliseSignature(signature);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    private static String normaliseSignature(String signature) {
        if (signature == null) {
            return "";
        }
        String trimmed = signature.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }
}
