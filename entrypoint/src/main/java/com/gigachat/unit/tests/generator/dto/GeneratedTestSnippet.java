package com.gigachat.unit.tests.generator.dto;

import java.util.List;

/**
 * Represents the generated test method snippet returned by the LLM.
 */
public record GeneratedTestSnippet(String className,
                                   String methodName,
                                   String methodBody,
                                   List<String> imports,
                                   List<String> classAnnotations,
                                   List<String> fieldDeclarations,
                                   List<String> helperMethods) {

    public GeneratedTestSnippet {
        imports = imports == null ? List.of() : List.copyOf(imports);
        classAnnotations = classAnnotations == null ? List.of() : List.copyOf(classAnnotations);
        fieldDeclarations = fieldDeclarations == null ? List.of() : List.copyOf(fieldDeclarations);
        helperMethods = helperMethods == null ? List.of() : List.copyOf(helperMethods);
    }

    public GeneratedTestSnippet(String className,
                                String methodName,
                                String methodBody,
                                List<String> imports) {
        this(className, methodName, methodBody, imports, List.of(), List.of(), List.of());
    }
}
