package com.gigachat.unit.tests.generator.reasoning.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ToolActionStep {

    private ToolActionType type;
    private Map<String, Object> arguments;

    public ToolActionStep() {
        this.arguments = new HashMap<>();
    }

    public ToolActionStep(ToolActionType type, Map<String, Object> arguments) {
        this.type = type;
        this.arguments = arguments == null ? new HashMap<>() : new HashMap<>(arguments);
    }

    public ToolActionType getType() {
        return type;
    }

    public void setType(ToolActionType type) {
        this.type = type;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public void setArguments(Map<String, Object> arguments) {
        this.arguments = arguments == null ? new HashMap<>() : new HashMap<>(arguments);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ToolActionStep that = (ToolActionStep) o;
        return type == that.type && Objects.equals(arguments, that.arguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, arguments);
    }

    @Override
    public String toString() {
        return "ToolActionStep{" +
                "type=" + type +
                ", arguments=" + arguments +
                '}';
    }
}
