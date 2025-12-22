package com.gigachat.unit.tests.generator.reasoning.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Represents the parsed decision returned by the LLM.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReasoningResponse {

    @JsonProperty("decision")
    private String decision;
    @JsonProperty("actions")
    private List<ReasoningStep> actions;
    @JsonProperty("memory_updates")
    private MemoryUpdate memoryUpdates;

    public ReasoningResponse() {
        this.actions = new ArrayList<>();
        this.memoryUpdates = new MemoryUpdate();
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public List<ReasoningStep> getActions() {
        return actions;
    }

    public void setActions(List<ReasoningStep> actions) {
        this.actions = actions == null ? new ArrayList<>() : new ArrayList<>(actions);
    }

    public MemoryUpdate getMemoryUpdates() {
        return memoryUpdates == null ? new MemoryUpdate() : memoryUpdates;
    }

    public void setMemoryUpdates(MemoryUpdate memoryUpdates) {
        this.memoryUpdates = memoryUpdates == null ? new MemoryUpdate() : memoryUpdates;
    }

    public ToolAction toToolAction() {
        if (actions == null || actions.isEmpty()) {
            return null;
        }
        List<ToolActionStep> steps = new ArrayList<>();
        for (ReasoningStep step : actions) {
            ToolActionType type = ToolActionType.valueOf(step.type());
            steps.add(new ToolActionStep(type, step.toArguments()));
        }
        if (steps.size() == 1) {
            return new ToolAction(steps.get(0).getType(), null, steps.get(0));
        }
        return new ToolAction(ToolActionType.COMPOSITE, steps, null);
    }

    public static class MemoryUpdate {
        private Set<String> knownMissingSymbols = new HashSet<>();
        private Set<String> appliedFixSignatures = new HashSet<>();

        public Set<String> getKnownMissingSymbols() {
            return knownMissingSymbols;
        }

        public void setKnownMissingSymbols(Set<String> knownMissingSymbols) {
            this.knownMissingSymbols = knownMissingSymbols == null ? new HashSet<>() : new HashSet<>(knownMissingSymbols);
        }

        public Set<String> getAppliedFixSignatures() {
            return appliedFixSignatures;
        }

        public void setAppliedFixSignatures(Set<String> appliedFixSignatures) {
            this.appliedFixSignatures = appliedFixSignatures == null ? new HashSet<>() : new HashSet<>(appliedFixSignatures);
        }
    }

    public record ReasoningStep(String type, String target, String details) {
        public java.util.Map<String, Object> toArguments() {
            java.util.Map<String, Object> args = new java.util.HashMap<>();
            if (target != null) {
                args.put("filePath", target);
                args.put("target", target);
            }
            if (details != null) {
                args.put("patch", details);
                args.put("importFqcn", details);
                args.put("details", details);
            }
            return args;
        }
    }
}
