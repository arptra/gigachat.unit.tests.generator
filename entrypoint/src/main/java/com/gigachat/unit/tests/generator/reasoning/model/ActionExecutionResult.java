package com.gigachat.unit.tests.generator.reasoning.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Captures structured data produced by executing tool actions. The payload is expected to be
 * JSON-serialisable and will be forwarded to the next reasoning prompt.
 */
public class ActionExecutionResult {

    private final Map<String, Object> context;

    public ActionExecutionResult() {
        this.context = new HashMap<>();
    }

    public ActionExecutionResult(Map<String, Object> context) {
        this.context = context == null ? new HashMap<>() : new HashMap<>(context);
    }

    public Map<String, Object> getContext() {
        return Collections.unmodifiableMap(context);
    }

    public ActionExecutionResult merge(ActionExecutionResult other) {
        if (other == null || other.context.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = new HashMap<>(this.context);
        other.context.forEach((key, value) -> merged.merge(key, value, ActionExecutionResult::mergeValues));
        return new ActionExecutionResult(merged);
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
        return Objects.equals(context, that.context);
    }

    @Override
    public int hashCode() {
        return Objects.hash(context);
    }
}
