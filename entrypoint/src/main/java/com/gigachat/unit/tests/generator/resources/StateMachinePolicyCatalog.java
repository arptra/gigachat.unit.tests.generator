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
 * Loads the bounded reasoning state machine from editable resources.
 */
public class StateMachinePolicyCatalog {

    private final StateMachinePolicy policy;

    public StateMachinePolicyCatalog() {
        this(new ResourceTextLoader());
    }

    public StateMachinePolicyCatalog(ResourceTextLoader loader) {
        Objects.requireNonNull(loader, "loader");
        JSONObject root = new JSONObject(loader.readText("policies/state-machine.json"));
        this.policy = parse(root);
    }

    public StateMachinePolicy policy() {
        return policy;
    }

    private StateMachinePolicy parse(JSONObject root) {
        String initialStateName = root.optString("initialState", AgentState.S0_INIT.name()).trim();
        Map<AgentState, StateDefinition> definitions = parseStates(root.optJSONObject("states"));
        AgentState initialState = AgentState.valueOf(initialStateName);
        if (!definitions.containsKey(initialState)) {
            throw new IllegalStateException("Initial state " + initialState + " is missing from state-machine resource");
        }
        return new StateMachinePolicy(initialState, definitions);
    }

    private Map<AgentState, StateDefinition> parseStates(JSONObject object) {
        if (object == null || object.isEmpty()) {
            throw new IllegalStateException("State-machine resource must define states");
        }
        Map<AgentState, StateDefinition> definitions = new LinkedHashMap<>();
        for (String stateName : object.keySet()) {
            JSONObject definitionObject = object.optJSONObject(stateName);
            AgentState state = AgentState.valueOf(stateName);
            String title = definitionObject == null ? "" : definitionObject.optString("title", "").trim();
            boolean terminal = definitionObject != null && definitionObject.optBoolean("terminal", false);
            List<AgentState> nextStates = parseStateList(definitionObject == null ? null : definitionObject.optJSONArray("nextStates"));
            definitions.put(state, new StateDefinition(state, title, terminal, nextStates));
        }
        return Map.copyOf(definitions);
    }

    private List<AgentState> parseStateList(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<AgentState> states = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            String value = array.optString(index, "").trim();
            if (!value.isBlank()) {
                states.add(AgentState.valueOf(value));
            }
        }
        return List.copyOf(states);
    }
}
