package com.gigachat.unit.tests.generator.reasoning.model;

import com.gigachat.unit.tests.generator.resources.ReasoningLoopPolicyCatalog;
import com.gigachat.unit.tests.generator.resources.ReasoningMemoryPolicy;
import com.gigachat.unit.tests.generator.resources.StateMachinePolicy;
import com.gigachat.unit.tests.generator.resources.StateMachinePolicyCatalog;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistent agent memory carried across reasoning iterations.
 */
public class ReasoningMemory {

    private static final ReasoningMemoryPolicy MEMORY_POLICY = new ReasoningLoopPolicyCatalog().memoryPolicy();
    private static final StateMachinePolicy STATE_MACHINE = new StateMachinePolicyCatalog().policy();

    private int attempt;
    private AgentState state;
    private int contextRequestBudgetRemaining;
    private final Deque<String> recentErrorSignatures;
    private final Set<String> knownMissingSymbols;
    private final Set<String> appliedFixSignatures;
    private final Map<String, String> contextCache;
    private final Set<String> forbiddenActions;

    public ReasoningMemory() {
        this.attempt = 0;
        this.state = STATE_MACHINE.initialState();
        this.contextRequestBudgetRemaining = MEMORY_POLICY.defaultContextBudget();
        this.recentErrorSignatures = new ArrayDeque<>();
        this.knownMissingSymbols = new HashSet<>();
        this.appliedFixSignatures = new HashSet<>();
        this.contextCache = new HashMap<>();
        this.forbiddenActions = new HashSet<>();
    }

    public int getAttempt() {
        return attempt;
    }

    public void incrementAttempt() {
        this.attempt++;
    }

    public AgentState getState() {
        return state;
    }

    public void setState(AgentState state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (!STATE_MACHINE.canTransition(this.state, state)) {
            throw new IllegalStateException("Invalid reasoning state transition: "
                    + this.state
                    + " -> "
                    + state
                    + "; allowed next states="
                    + STATE_MACHINE.allowedNextStates(this.state));
        }
        this.state = state;
    }

    public int getContextRequestBudgetRemaining() {
        return contextRequestBudgetRemaining;
    }

    public void decrementContextBudget() {
        if (contextRequestBudgetRemaining > 0) {
            contextRequestBudgetRemaining--;
        }
    }

    public void resetContextBudget() {
        contextRequestBudgetRemaining = MEMORY_POLICY.defaultContextBudget();
    }

    public List<String> getRecentErrorSignatures() {
        return List.copyOf(recentErrorSignatures);
    }

    public void addErrorSignature(String signature) {
        if (signature == null || signature.isBlank()) {
            return;
        }
        if (recentErrorSignatures.size() >= MEMORY_POLICY.maxErrorHistory()) {
            recentErrorSignatures.removeFirst();
        }
        recentErrorSignatures.addLast(signature);
    }

    public int countOccurrences(String signature) {
        int count = 0;
        for (String value : recentErrorSignatures) {
            if (signature.equals(value)) {
                count++;
            }
        }
        return count;
    }

    public Set<String> getKnownMissingSymbols() {
        return Collections.unmodifiableSet(knownMissingSymbols);
    }

    public void addKnownMissingSymbol(String symbol) {
        if (symbol != null && !symbol.isBlank()) {
            knownMissingSymbols.add(symbol);
        }
    }

    public Set<String> getAppliedFixSignatures() {
        return Collections.unmodifiableSet(appliedFixSignatures);
    }

    public void addAppliedFixSignature(String signature) {
        if (signature != null && !signature.isBlank()) {
            appliedFixSignatures.add(signature);
        }
    }

    public Map<String, String> getContextCache() {
        return Collections.unmodifiableMap(contextCache);
    }

    public void addContextCacheEntry(String key, String value) {
        if (key != null && value != null) {
            contextCache.put(key, value);
        }
    }

    public void addContextCacheEntries(Map<String, String> entries) {
        if (entries != null) {
            entries.forEach(this::addContextCacheEntry);
        }
    }

    public Set<String> getForbiddenActions() {
        return Collections.unmodifiableSet(forbiddenActions);
    }

    public void addForbiddenAction(String action) {
        if (action != null && !action.isBlank()) {
            forbiddenActions.add(action);
        }
    }

    public ReasoningMemory copy() {
        ReasoningMemory clone = new ReasoningMemory();
        clone.attempt = this.attempt;
        clone.state = this.state;
        clone.contextRequestBudgetRemaining = this.contextRequestBudgetRemaining;
        clone.recentErrorSignatures.addAll(this.recentErrorSignatures);
        clone.knownMissingSymbols.addAll(this.knownMissingSymbols);
        clone.appliedFixSignatures.addAll(this.appliedFixSignatures);
        clone.contextCache.putAll(this.contextCache);
        clone.forbiddenActions.addAll(this.forbiddenActions);
        return clone;
    }

    public void applyUpdates(Set<String> newMissingSymbols, Set<String> newAppliedFixes, Map<String, String> cacheUpdates) {
        if (newMissingSymbols != null) {
            knownMissingSymbols.addAll(newMissingSymbols);
        }
        if (newAppliedFixes != null) {
            appliedFixSignatures.addAll(newAppliedFixes);
        }
        addContextCacheEntries(cacheUpdates);
    }
}
