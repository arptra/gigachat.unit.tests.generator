package com.gigachat.unit.tests.generator.reasoning.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Captures structured data produced by executing tool actions. Information-gathering steps append
 * details to {@code information} while project-modifying steps are tracked via {@code performedActions}.
 * The accumulated payload is serialisable and injected into the next reasoning prompt in a single
 * batch.
 */
public class ActionExecutionResult {

    private final Map<String, Object> information;
    private final List<String> performedActions;

    public ActionExecutionResult() {
        this.information = new HashMap<>();
        this.performedActions = new ArrayList<>();
    }

    public ActionExecutionResult(Map<String, Object> information) {
        this(information, List.of());
    }

    public ActionExecutionResult(Map<String, Object> information, List<String> performedActions) {
        this.information = information == null ? new HashMap<>() : new HashMap<>(information);
        this.performedActions = performedActions == null ? new ArrayList<>() : new ArrayList<>(performedActions);
    }

    public Map<String, Object> getInformation() {
        return Collections.unmodifiableMap(information);
    }

    public List<String> getPerformedActions() {
        return Collections.unmodifiableList(performedActions);
    }

    public ActionExecutionResult merge(ActionExecutionResult other) {
        if (other == null || (other.information.isEmpty() && other.performedActions.isEmpty())) {
            return this;
        }
        Map<String, Object> mergedInformation = new HashMap<>(this.information);
        other.information.forEach((key, value) -> mergedInformation.merge(key, value, ActionExecutionResult::mergeValues));

        List<String> mergedPerformedActions = new ArrayList<>(this.performedActions);
        mergedPerformedActions.addAll(other.performedActions);

        return new ActionExecutionResult(mergedInformation, mergedPerformedActions);
    }

    public static ActionExecutionResult empty() {
        return new ActionExecutionResult();
    }

    private static Object mergeValues(Object existing, Object incoming) {
        if (existing instanceof List<?> existingList && incoming instanceof List<?> incomingList) {
            return mergeLists(existingList, incomingList);
        }
        if (existing instanceof Map<?, ?> existingMap && incoming instanceof Map<?, ?> incomingMap) {
            Map<Object, Object> merged = new HashMap<>();
            merged.putAll(existingMap);
            incomingMap.forEach(merged::put);
            return merged;
        }
        return incoming;
    }

    private static List<?> mergeLists(List<?> existing, List<?> incoming) {
        Map<Integer, Object> ordered = new HashMap<>();
        int index = 0;
        for (Object value : existing) {
            ordered.put(index++, value);
        }
        for (Object value : incoming) {
            ordered.put(index++, value);
        }
        return List.copyOf(ordered.values());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ActionExecutionResult that = (ActionExecutionResult) o;
        return Objects.equals(information, that.information) && Objects.equals(performedActions, that.performedActions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(information, performedActions);
    }

    /**
     * Converts the aggregated result into a prompt-ready payload containing both the information
     * collected and a list of performed project modifications.
     */
    public Map<String, Object> toPromptPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("performedActions", getPerformedActions());
        payload.put("informationCollected", getInformation());
        return payload;
    }

    public static ActionExecutionResult error(String message) {
        return new ActionExecutionResult(Map.of("errors", List.of(message)));
    }
}
