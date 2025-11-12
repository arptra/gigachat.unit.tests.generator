package com.gigachat.unit.tests.generator.analyzer;

import java.util.List;

/**
 * Detailed representation of a constructor discovered within the project, including parameters and hints
 * that can be surfaced to the LLM prompt.
 */
public record ConstructorMetadata(String signature,
                                  List<ParameterMetadata> parameters,
                                  List<String> hints) {

    public ConstructorMetadata {
        signature = normaliseSignature(signature);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        hints = hints == null ? List.of() : List.copyOf(hints);
    }

    public ConstructorMetadata withHints(List<String> newHints) {
        List<String> hintsCopy = newHints == null ? List.of() : List.copyOf(newHints);
        return new ConstructorMetadata(signature, parameters, hintsCopy);
    }

    private static String normaliseSignature(String signature) {
        if (signature == null) {
            return "";
        }
        String trimmed = signature.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }
}
