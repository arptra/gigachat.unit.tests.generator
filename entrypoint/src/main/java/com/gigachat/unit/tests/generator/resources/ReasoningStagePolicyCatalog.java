package com.gigachat.unit.tests.generator.resources;

import com.gigachat.unit.tests.generator.reasoning.model.ReasoningStage;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads objective/protocol/decision/tool policy for each reasoning stage from editable resources.
 */
public class ReasoningStagePolicyCatalog {

    private final Map<ReasoningStage, ReasoningStagePolicy> policies;

    public ReasoningStagePolicyCatalog() {
        this(new ResourceTextLoader());
    }

    public ReasoningStagePolicyCatalog(ResourceTextLoader loader) {
        Objects.requireNonNull(loader, "loader");
        this.policies = loadPolicies(loader.readText("policies/reasoning-stage-policies.json"));
    }

    public ReasoningStagePolicy policyFor(ReasoningStage stage) {
        if (stage == null) {
            throw new IllegalArgumentException("stage");
        }
        ReasoningStagePolicy policy = policies.get(stage);
        if (policy == null) {
            throw new IllegalStateException("Missing reasoning-stage policy for " + stage);
        }
        return policy;
    }

    private Map<ReasoningStage, ReasoningStagePolicy> loadPolicies(String content) {
        if (content == null || content.isBlank()) {
            return Map.of();
        }
        JSONObject root = new JSONObject(content);
        JSONArray stages = root.optJSONArray("stages");
        if (stages == null || stages.isEmpty()) {
            return Map.of();
        }
        EnumMap<ReasoningStage, ReasoningStagePolicy> loaded = new EnumMap<>(ReasoningStage.class);
        for (int index = 0; index < stages.length(); index++) {
            JSONObject item = stages.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String stageName = item.optString("stage", "").trim();
            if (stageName.isBlank()) {
                continue;
            }
            ReasoningStage stage = ReasoningStage.valueOf(stageName);
            loaded.put(stage, new ReasoningStagePolicy(
                    stage,
                    item.optString("objective", ""),
                    jsonArrayToStrings(item.optJSONArray("protocol")),
                    jsonArrayToStrings(item.optJSONArray("allowedDecisions")),
                    jsonArrayToToolActions(item.optJSONArray("allowedToolActions"))
            ));
        }
        return Map.copyOf(loaded);
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

    private List<ToolActionType> jsonArrayToToolActions(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<ToolActionType> values = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            String value = array.optString(index, "").trim();
            if (value.isBlank()) {
                continue;
            }
            values.add(ToolActionType.valueOf(value));
        }
        return List.copyOf(values);
    }
}
