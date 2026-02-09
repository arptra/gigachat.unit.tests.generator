package com.gigachat.unit.tests.generator.reasoning.model;

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

    private static final int MAX_ERROR_HISTORY = 5;
    private static final int DEFAULT_CONTEXT_BUDGET = 3;

    private int attempt;
    private AgentState state;
    private int contextRequestBudgetRemaining;
    private final Deque<String> recentErrorSignatures;
    private final Set<String> knownMissingSymbols;
    private final Set<String> appliedFixSignatures;
    private final Map<String, String> contextCache;
    private final Set<String> forbiddenActions;
    private final Map<String, Integer> fixFingerprintAttempts;
    private final Set<String> blockedFixFingerprints;
    private int noProgressStreak;

    public ReasoningMemory() {
        this.attempt = 0;
        this.state = AgentState.S0_INIT;
        this.contextRequestBudgetRemaining = DEFAULT_CONTEXT_BUDGET;
        this.recentErrorSignatures = new ArrayDeque<>();
        this.knownMissingSymbols = new HashSet<>();
        this.appliedFixSignatures = new HashSet<>();
        this.contextCache = new HashMap<>();
        this.forbiddenActions = new HashSet<>();
        this.fixFingerprintAttempts = new HashMap<>();
        this.blockedFixFingerprints = new HashSet<>();
        this.noProgressStreak = 0;
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
        contextRequestBudgetRemaining = DEFAULT_CONTEXT_BUDGET;
    }

    public List<String> getRecentErrorSignatures() {
        return List.copyOf(recentErrorSignatures);
    }

    public void addErrorSignature(String signature) {
        if (signature == null || signature.isBlank()) {
            return;
        }
        if (recentErrorSignatures.size() >= MAX_ERROR_HISTORY) {
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

    public int incrementFixFingerprintAttempt(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return 0;
        }
        int next = fixFingerprintAttempts.getOrDefault(fingerprint, 0) + 1;
        fixFingerprintAttempts.put(fingerprint, next);
        return next;
    }

    public Map<String, Integer> getFixFingerprintAttempts() {
        return Collections.unmodifiableMap(fixFingerprintAttempts);
    }

    public void blockFixFingerprint(String fingerprint) {
        if (fingerprint != null && !fingerprint.isBlank()) {
            blockedFixFingerprints.add(fingerprint);
        }
    }

    public boolean isFixFingerprintBlocked(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return false;
        }
        return blockedFixFingerprints.contains(fingerprint);
    }

    public Set<String> getBlockedFixFingerprints() {
        return Collections.unmodifiableSet(blockedFixFingerprints);
    }

    public int getNoProgressStreak() {
        return noProgressStreak;
    }

    public void incrementNoProgressStreak() {
        noProgressStreak++;
    }

    public void resetNoProgressStreak() {
        noProgressStreak = 0;
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
        clone.fixFingerprintAttempts.putAll(this.fixFingerprintAttempts);
        clone.blockedFixFingerprints.addAll(this.blockedFixFingerprints);
        clone.noProgressStreak = this.noProgressStreak;
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
