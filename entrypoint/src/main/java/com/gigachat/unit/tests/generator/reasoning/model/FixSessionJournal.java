package com.gigachat.unit.tests.generator.reasoning.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated journal for a fix session: state transitions and per-iteration snapshots.
 */
public class FixSessionJournal {

    private final List<FixSessionTransition> transitions;
    private final List<ReasoningIterationSnapshot> iterationSnapshots;

    public FixSessionJournal() {
        this.transitions = new ArrayList<>();
        this.iterationSnapshots = new ArrayList<>();
    }

    public void appendTransition(FixSessionState from, FixSessionState to, String reason) {
        transitions.add(new FixSessionTransition(transitions.size() + 1, from, to, reason));
    }

    public void appendSnapshot(ReasoningIterationSnapshot snapshot) {
        if (snapshot != null) {
            iterationSnapshots.add(snapshot);
        }
    }

    public List<FixSessionTransition> getTransitions() {
        return List.copyOf(transitions);
    }

    public List<ReasoningIterationSnapshot> getIterationSnapshots() {
        return List.copyOf(iterationSnapshots);
    }
}

