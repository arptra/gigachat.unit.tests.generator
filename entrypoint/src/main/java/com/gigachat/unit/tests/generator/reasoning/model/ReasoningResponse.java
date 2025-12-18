package com.gigachat.unit.tests.generator.reasoning.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ReasoningResponse {

    private List<String> reasoning;
    private ToolAction action;

    public ReasoningResponse() {
        this.reasoning = new ArrayList<>();
    }

    public ReasoningResponse(List<String> reasoning, ToolAction action) {
        this.reasoning = reasoning == null ? new ArrayList<>() : new ArrayList<>(reasoning);
        this.action = action;
    }

    public List<String> getReasoning() {
        return reasoning;
    }

    public void setReasoning(List<String> reasoning) {
        this.reasoning = reasoning == null ? new ArrayList<>() : new ArrayList<>(reasoning);
    }

    public ToolAction getAction() {
        return action;
    }

    public void setAction(ToolAction action) {
        this.action = action;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ReasoningResponse that = (ReasoningResponse) o;
        return Objects.equals(reasoning, that.reasoning) && Objects.equals(action, that.action);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reasoning, action);
    }

    @Override
    public String toString() {
        return "ReasoningResponse{" +
                "reasoning=" + reasoning +
                ", action=" + action +
                '}';
    }
}
