package com.gigachat.unit.tests.generator.reasoning.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ToolAction {

    private ToolActionType type;
    private List<ToolActionStep> steps;
    private ToolActionStep singleStep;

    public ToolAction() {
        this.steps = new ArrayList<>();
    }

    public ToolAction(ToolActionType type, List<ToolActionStep> steps, ToolActionStep singleStep) {
        this.type = type;
        this.steps = steps == null ? new ArrayList<>() : new ArrayList<>(steps);
        this.singleStep = singleStep;
    }

    public ToolActionType getType() {
        return type;
    }

    public void setType(ToolActionType type) {
        this.type = type;
    }

    public List<ToolActionStep> getSteps() {
        return steps;
    }

    public void setSteps(List<ToolActionStep> steps) {
        this.steps = steps == null ? new ArrayList<>() : new ArrayList<>(steps);
    }

    public ToolActionStep getSingleStep() {
        return singleStep;
    }

    public void setSingleStep(ToolActionStep singleStep) {
        this.singleStep = singleStep;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ToolAction toolAction = (ToolAction) o;
        return type == toolAction.type && Objects.equals(steps, toolAction.steps) && Objects.equals(singleStep, toolAction.singleStep);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, steps, singleStep);
    }

    @Override
    public String toString() {
        return "ToolAction{" +
                "type=" + type +
                ", steps=" + steps +
                ", singleStep=" + singleStep +
                '}';
    }
}
