package com.gigachat.unit.tests.generator.resources;

import com.gigachat.unit.tests.generator.reasoning.model.AgentState;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads retry budgets and loop-transition policy from editable resources.
 */
public class ReasoningLoopPolicyCatalog {

    private final ReasoningMemoryPolicy memoryPolicy;
    private final ReasoningLoopPolicy compilationPolicy;
    private final ReasoningLoopPolicy executionPolicy;
    private final ReasoningLoopPolicy coveragePolicy;

    public ReasoningLoopPolicyCatalog() {
        this(new ResourceTextLoader());
    }

    public ReasoningLoopPolicyCatalog(ResourceTextLoader loader) {
        Objects.requireNonNull(loader, "loader");
        JSONObject root = new JSONObject(loader.readText("policies/reasoning-loop-policies.json"));
        this.memoryPolicy = parseMemoryPolicy(root.optJSONObject("reasoningMemory"));
        this.compilationPolicy = parseLoopPolicy(root.optJSONObject("compilationLoop"));
        this.executionPolicy = parseLoopPolicy(root.optJSONObject("executionLoop"));
        this.coveragePolicy = parseLoopPolicy(root.optJSONObject("coverageLoop"));
    }

    public ReasoningMemoryPolicy memoryPolicy() {
        return memoryPolicy;
    }

    public ReasoningLoopPolicy compilationPolicy() {
        return compilationPolicy;
    }

    public ReasoningLoopPolicy executionPolicy() {
        return executionPolicy;
    }

    public ReasoningLoopPolicy coveragePolicy() {
        return coveragePolicy;
    }

    private ReasoningMemoryPolicy parseMemoryPolicy(JSONObject object) {
        if (object == null) {
            return new ReasoningMemoryPolicy(3, 5);
        }
        return new ReasoningMemoryPolicy(
                Math.max(object.optInt("defaultContextBudget", 3), 1),
                Math.max(object.optInt("maxErrorHistory", 5), 1)
        );
    }

    private ReasoningLoopPolicy parseLoopPolicy(JSONObject object) {
        if (object == null) {
            return new ReasoningLoopPolicy(1, 1, 0, List.of(), Map.of(), null, Map.of());
        }
        return new ReasoningLoopPolicy(
                Math.max(object.optInt("maxIterations", 1), 1),
                Math.max(object.optInt("repeatedSignatureThreshold", 1), 1),
                Math.max(object.optInt("stopGraceRounds", 0), 0),
                jsonArrayToStrings(object.optJSONArray("defaultForbiddenActions")),
                parseDecisionStates(object.optJSONObject("decisionNextStates")),
                parseFalseDependencyPolicy(object.optJSONObject("falseDependency")),
                parseStringMap(object.optJSONObject("runtimeRegression"))
        );
    }

    private Map<String, AgentState> parseDecisionStates(JSONObject object) {
        if (object == null || object.isEmpty()) {
            return Map.of();
        }
        Map<String, AgentState> states = new LinkedHashMap<>();
        for (String key : object.keySet()) {
            String stateName = object.optString(key, "").trim();
            if (key == null || key.isBlank() || stateName.isBlank()) {
                continue;
            }
            states.put(key.trim().toUpperCase(), AgentState.valueOf(stateName));
        }
        return Map.copyOf(states);
    }

    private FalseDependencyPolicy parseFalseDependencyPolicy(JSONObject object) {
        if (object == null || object.isEmpty()) {
            return null;
        }
        String nextState = object.optString("nextState", "").trim();
        if (nextState.isBlank()) {
            return null;
        }
        return new FalseDependencyPolicy(
                AgentState.valueOf(nextState),
                jsonArrayToStrings(object.optJSONArray("forbiddenActions"))
        );
    }

    private Map<String, String> parseStringMap(JSONObject object) {
        if (object == null || object.isEmpty()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : object.keySet()) {
            String value = object.optString(key, "").trim();
            if (key != null && !key.isBlank() && !value.isBlank()) {
                values.put(key, value);
            }
        }
        return Map.copyOf(values);
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
