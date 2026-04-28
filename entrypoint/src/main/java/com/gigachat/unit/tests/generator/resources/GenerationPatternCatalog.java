package com.gigachat.unit.tests.generator.resources;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads generation-validation and generated-artifact patterns from editable resources.
 */
public class GenerationPatternCatalog {

    private final List<GenerationValidationPattern> patterns;

    public GenerationPatternCatalog() {
        this(new ResourceTextLoader());
    }

    public GenerationPatternCatalog(ResourceTextLoader loader) {
        Objects.requireNonNull(loader, "loader");
        this.patterns = loadPatterns(loader.readText("patterns/generation-patterns.json"));
    }

    public Optional<GenerationValidationPattern> matchValidationPattern(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        String lower = message.toLowerCase(Locale.ROOT);
        GenerationValidationPattern bestPattern = null;
        int bestScore = 0;
        for (GenerationValidationPattern pattern : patterns) {
            int score = matchScore(pattern, lower);
            if (score > bestScore) {
                bestPattern = pattern;
                bestScore = score;
            }
        }
        return Optional.ofNullable(bestPattern);
    }

    public Optional<GenerationValidationPattern> patternByErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return Optional.empty();
        }
        return patterns.stream()
                .filter(pattern -> errorCode.equalsIgnoreCase(pattern.errorCode()))
                .findFirst();
    }

    private int matchScore(GenerationValidationPattern pattern, String lowerMessage) {
        if (pattern == null || lowerMessage == null || lowerMessage.isBlank()) {
            return 0;
        }
        if (pattern.containsAny() == null || pattern.containsAny().isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String fragment : pattern.containsAny()) {
            if (fragment != null && !fragment.isBlank()
                    && lowerMessage.contains(fragment.toLowerCase(Locale.ROOT))) {
                score++;
            }
        }
        return score;
    }

    private List<GenerationValidationPattern> loadPatterns(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        JSONObject root = new JSONObject(content);
        JSONArray array = root.optJSONArray("patterns");
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<GenerationValidationPattern> loaded = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String errorCode = item.optString("errorCode", "").trim();
            if (errorCode.isBlank()) {
                continue;
            }
            loaded.add(new GenerationValidationPattern(
                    item.optString("id", errorCode),
                    errorCode,
                    item.optString("title", ""),
                    item.optString("summary", ""),
                    jsonArrayToStrings(item.optJSONArray("containsAny")),
                    jsonArrayToStrings(item.optJSONArray("retryConstraints")),
                    jsonArrayToStrings(item.optJSONArray("dynamicConstraintBuilders")),
                    item.optString("llmHeuristic", "")
            ));
        }
        return List.copyOf(loaded);
    }

    private List<String> jsonArrayToStrings(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            String value = array.optString(index, "").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }
}
