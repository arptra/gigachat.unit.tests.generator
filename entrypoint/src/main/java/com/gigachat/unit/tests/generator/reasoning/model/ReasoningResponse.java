package com.gigachat.unit.tests.generator.reasoning.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decision-based response from the LLM.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReasoningResponse {

    @JsonProperty("decision")
    private String decision;
    @JsonProperty("actions")
    private List<ReasoningAction> actions;
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

    public List<ReasoningAction> getActions() {
        return actions;
    }

    public void setActions(List<ReasoningAction> actions) {
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
        for (ReasoningAction action : actions) {
            ToolActionType type = ToolActionType.valueOf(action.getType());
            steps.add(new ToolActionStep(type, action.getArgs() == null ? Map.of() : action.getArgs()));
        }
        if (steps.size() == 1) {
            return new ToolAction(steps.get(0).getType(), null, steps.get(0));
        }
        return new ToolAction(ToolActionType.COMPOSITE, steps, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MemoryUpdate {
        private Set<String> knownMissingSymbols = new HashSet<>();
        private Set<String> appliedFixSignatures = new HashSet<>();
        private Map<String, String> contextCache = new HashMap<>();

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

        public Map<String, String> getContextCache() {
            return contextCache;
        }

        public void setContextCache(Map<String, String> contextCache) {
            this.contextCache = contextCache == null ? new HashMap<>() : new HashMap<>(contextCache);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReasoningAction {
        private String type;
        private Map<String, Object> args = new HashMap<>();

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Map<String, Object> getArgs() {
            return args;
        }

        public void setArgs(Map<String, Object> args) {
            this.args = args == null ? new HashMap<>() : new HashMap<>(args);
        }
    }
}
