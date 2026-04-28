package com.gigachat.unit.tests.generator.resources;

import org.json.JSONObject;

import java.util.Objects;

/**
 * Loads state/action definitions from editable JSON resources.
 */
public class StateModelCatalog {

    private final JSONObject generationValidationStates;
    private final JSONObject repairStates;

    public StateModelCatalog() {
        this(new ResourceTextLoader());
    }

    public StateModelCatalog(ResourceTextLoader loader) {
        Objects.requireNonNull(loader, "loader");
        this.generationValidationStates = parseJson(loader.readText("state-models/generation-validation.json"));
        this.repairStates = parseJson(loader.readText("state-models/repair-states.json"));
    }

    public JSONObject generationValidationState(String errorCode) {
        return copy(generationValidationStates.optJSONObject(errorCode));
    }

    public JSONObject repairState(String key) {
        return copy(repairStates.optJSONObject(key));
    }

    private JSONObject parseJson(String content) {
        if (content == null || content.isBlank()) {
            return new JSONObject();
        }
        return new JSONObject(content);
    }

    private JSONObject copy(JSONObject source) {
        return source == null ? null : new JSONObject(source.toString());
    }
}
