package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;

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
        return normalize(raw, null);
    }

    public static Map<String, Object> normalize(Map<String, Object> raw, ToolActionType actionType) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new HashMap<>();
        raw.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            String canonical = canonicalKey(key, actionType);
            if (canonical != null) {
                normalized.put(canonical, value);
            }
        });
        return normalized;
    }

    private static String canonicalKey(String key, ToolActionType actionType) {
        String cleaned = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if ("fqcn".equals(cleaned) || "fqn".equals(cleaned)) {
            if (actionType == ToolActionType.ADD_IMPORT) {
                return "import";
            }
            if (actionType == ToolActionType.READ_CLASS
                    || actionType == ToolActionType.READ_METHOD
                    || actionType == ToolActionType.LIST_METHODS) {
                return "className";
            }
            return "symbol";
        }
        return switch (cleaned) {
            case "filepath", "path", "target", "file" -> "path";
            case "classname" -> "className";
            case "testclass" -> "testClass";
            case "methodname", "method" -> "methodName";
            case "testpattern" -> "testPattern";
            case "symbol", "name" -> "symbol";
            case "recipeid", "recipe" -> "recipeId";
            case "import", "importfqcn" -> "import";
            case "patch", "diff", "content" -> "patch";
            default -> null;
        };
    }
}
