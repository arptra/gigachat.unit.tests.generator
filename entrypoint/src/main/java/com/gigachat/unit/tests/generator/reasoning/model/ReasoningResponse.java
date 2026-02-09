package com.gigachat.unit.tests.generator.reasoning.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
            ToolActionType type = parseType(action.getType());
            if (type == null || type == ToolActionType.COMPOSITE) {
                continue;
            }
            steps.add(new ToolActionStep(type, action.toArguments(type)));
        }
        if (steps.isEmpty()) {
            return null;
        }
        if (steps.size() == 1) {
            return new ToolAction(steps.get(0).getType(), null, steps.get(0));
        }
        return new ToolAction(ToolActionType.COMPOSITE, steps, null);
    }

    private ToolActionType parseType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        try {
            return ToolActionType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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
        private Map<String, Object> extensions = new HashMap<>();

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

        @JsonAnySetter
        public void putExtension(String key, Object value) {
            if (key == null || "type".equals(key) || "args".equals(key)) {
                return;
            }
            extensions.put(key, value);
        }

        public Map<String, Object> toArguments(ToolActionType actionType) {
            Map<String, Object> merged = new HashMap<>();
            if (args != null) {
                merged.putAll(args);
            }
            if (extensions == null || extensions.isEmpty() || actionType == null) {
                return merged;
            }
            switch (actionType) {
                case SHOW_FILE, SHOW_IMPORTS -> putIfAbsent(merged, "path", firstOf("path", "filePath", "file", "target"));
                case SEARCH_SYMBOL, MARK_FALSE_DEPENDENCY -> putIfAbsent(merged, "symbol", firstOf("symbol", "name", "details"));
                case READ_CLASS, LIST_METHODS -> putIfAbsent(merged, "className", firstOf("className", "class", "details"));
                case READ_METHOD -> {
                    putIfAbsent(merged, "className", firstOf("className", "class"));
                    putIfAbsent(merged, "methodName", firstOf("methodName", "method"));
                }
                case APPLY_PATCH -> {
                    putIfAbsent(merged, "path", firstOf("path", "filePath", "file", "target"));
                    putIfAbsent(merged, "patch", firstOf("patch", "diff", "content", "details"));
                }
                case ADD_IMPORT -> {
                    putIfAbsent(merged, "path", firstOf("path", "filePath", "file", "target"));
                    putIfAbsent(merged, "import", firstOf("import", "importFqcn", "fqcn", "details"));
                }
                case ADD_DEPENDENCY -> putIfAbsent(merged, "dependency", firstOf("dependency", "dependencyNotation", "gav", "details"));
                default -> {
                    // no-op
                }
            }
            return merged;
        }

        private void putIfAbsent(Map<String, Object> target, String key, Object value) {
            if (!target.containsKey(key) && value != null) {
                target.put(key, value);
            }
        }

        private Object firstOf(String... keys) {
            if (extensions == null || keys == null) {
                return null;
            }
            for (String key : keys) {
                if (key != null && extensions.containsKey(key) && extensions.get(key) != null) {
                    return extensions.get(key);
                }
            }
            return null;
        }
    }
}
