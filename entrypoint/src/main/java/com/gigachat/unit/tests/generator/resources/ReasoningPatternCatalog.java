package com.gigachat.unit.tests.generator.resources;

import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningStage;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Loads and matches compile/runtime/coverage patterns from editable resources.
 */
public class ReasoningPatternCatalog {

    private final List<ReasoningPattern> patterns;

    public ReasoningPatternCatalog() {
        this(new ResourceTextLoader());
    }

    public ReasoningPatternCatalog(ResourceTextLoader loader) {
        Objects.requireNonNull(loader, "loader");
        List<ReasoningPattern> loaded = new ArrayList<>();
        loaded.addAll(loadPatterns(loader.readText("patterns/compile-patterns.json")));
        loaded.addAll(loadPatterns(loader.readText("patterns/runtime-patterns.json")));
        loaded.addAll(loadPatterns(loader.readText("patterns/coverage-patterns.json")));
        this.patterns = List.copyOf(loaded);
    }

    public List<ReasoningPattern> patternsFor(ReasoningStage stage) {
        if (stage == null) {
            return List.of();
        }
        return patterns.stream()
                .filter(pattern -> pattern.stage() == stage)
                .toList();
    }

    public List<ReasoningPattern> match(ReasoningLoopContext loopContext) {
        if (loopContext == null || loopContext.getStage() == null) {
            return List.of();
        }
        String combined = combinedErrorText(loopContext).toLowerCase(Locale.ROOT);
        if (combined.isBlank()) {
            return List.of();
        }
        return patternsFor(loopContext.getStage()).stream()
                .filter(pattern -> matches(pattern, combined))
                .toList();
    }

    private boolean matches(ReasoningPattern pattern, String combinedLowercaseError) {
        if (pattern == null || combinedLowercaseError == null || combinedLowercaseError.isBlank()) {
            return false;
        }
        if (pattern.containsAny() == null || pattern.containsAny().isEmpty()) {
            return false;
        }
        for (String fragment : pattern.containsAny()) {
            if (fragment != null && !fragment.isBlank()
                    && combinedLowercaseError.contains(fragment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String combinedErrorText(ReasoningLoopContext loopContext) {
        StringBuilder builder = new StringBuilder();
        appendIfPresent(builder, loopContext.getErrorInfo() == null ? null : loopContext.getErrorInfo().getPrimaryMessage());
        appendIfPresent(builder, loopContext.getErrorInfo() == null ? null : loopContext.getErrorInfo().getCompilerOutput());
        appendIfPresent(builder, loopContext.getErrorInfo() == null ? null : loopContext.getErrorInfo().getStacktrace());
        if (loopContext.getExecutionResult() != null) {
            Object stdout = loopContext.getExecutionResult().getInformation().get("stdout");
            Object stderr = loopContext.getExecutionResult().getInformation().get("stderr");
            appendIfPresent(builder, stdout == null ? null : stdout.toString());
            appendIfPresent(builder, stderr == null ? null : stderr.toString());
        }
        return builder.toString();
    }

    private void appendIfPresent(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(value);
    }

    private List<ReasoningPattern> loadPatterns(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        JSONObject root = new JSONObject(content);
        JSONArray array = root.optJSONArray("patterns");
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<ReasoningPattern> loaded = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String stageName = item.optString("stage", "").trim();
            if (stageName.isBlank()) {
                continue;
            }
            ReasoningStage stage = ReasoningStage.valueOf(stageName);
            loaded.add(new ReasoningPattern(
                    item.optString("id", ""),
                    stage,
                    item.optString("title", ""),
                    item.optString("errorFamily", ""),
                    item.optString("summary", ""),
                    jsonArrayToStrings(item.optJSONArray("containsAny")),
                    jsonArrayToStrings(item.optJSONArray("allowedTools")),
                    jsonArrayToStrings(item.optJSONArray("executorActions")),
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
