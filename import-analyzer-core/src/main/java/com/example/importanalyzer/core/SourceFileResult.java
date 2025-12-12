package com.example.importanalyzer.core;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public record SourceFileResult(
        Path file,
        String packageName,
        Map<String, Integer> imports,
        Map<String, Integer> wildcardImports,
        Map<String, Integer> staticImports,
        Map<String, Integer> staticWildcardImports,
        Set<String> declaredTypes,
        Set<String> usedTypes,
        Set<String> usedIdentifiers,
        Map<String, Set<String>> methodCallsByType
) {
}
