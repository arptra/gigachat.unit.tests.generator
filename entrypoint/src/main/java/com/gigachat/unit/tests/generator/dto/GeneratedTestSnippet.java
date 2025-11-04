package com.gigachat.unit.tests.generator.dto;

import java.util.List;

/**
 * Represents the generated test method snippet returned by the LLM.
 */
public record GeneratedTestSnippet(String className,
                                   String methodName,
                                   String methodBody,
                                   List<String> imports) {

    public GeneratedTestSnippet {
        imports = imports == null ? List.of() : List.copyOf(imports);
    }
}
