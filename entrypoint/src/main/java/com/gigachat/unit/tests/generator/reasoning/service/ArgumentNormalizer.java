package com.gigachat.unit.tests.generator.reasoning.service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Normalizes LLM-provided argument maps into canonical key names.
 * All keys are lower-cased, and underscores/dashes are removed prior to matching.
 */
public final class ArgumentNormalizer {

    private ArgumentNormalizer() {
    }

    public static Map<String, Object> normalize(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new HashMap<>();
        raw.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            String canonical = canonicalKey(key);
            if (canonical != null) {
                normalized.put(canonical, value);
            }
        });
        return normalized;
    }

    private static String canonicalKey(String key) {
        String cleaned = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return switch (cleaned) {
            case "filepath", "path", "target", "file" -> "path";
            case "classname" -> "className";
            case "methodname", "method" -> "methodName";
            case "symbol", "name" -> "symbol";
            case "targetclass" -> "targetClass";
            case "targetidentifier", "instance", "instancename" -> "targetIdentifier";
            case "mocktargets", "targets" -> "mockTargets";
            case "mockstubs", "stubs", "stubtargets" -> "mockStubs";
            case "strategy", "mockstrategy" -> "strategy";
            case "import", "importfqcn", "fqcn" -> "import";
            case "patch", "diff", "content" -> "patch";
            case "dependency", "dependencynotation", "gav", "artifact", "coordinate", "coordinates" -> "dependency";
            default -> null;
        };
    }
}
