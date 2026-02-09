package com.gigachat.unit.tests.generator.reasoning.model;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime holder for one fix session lifecycle and its journal.
 */
public class FixSession {

    private static final Map<FixSessionState, Set<FixSessionState>> ALLOWED_TRANSITIONS = buildAllowedTransitions();

    private final String id;
    private final Path testFile;
    private final String methodName;
    private final FixSessionJournal journal;
    private final ReasoningMemory memory;
    private ActionExecutionResult cumulativeExecutionResult;
    private FixSessionState state;

    public FixSession(String id, Path testFile, String methodName) {
        this.id = Objects.requireNonNull(id, "id");
        this.testFile = testFile;
        this.methodName = methodName;
        this.journal = new FixSessionJournal();
        this.memory = new ReasoningMemory();
        this.cumulativeExecutionResult = ActionExecutionResult.empty();
        this.state = FixSessionState.OBSERVE;
        this.journal.appendTransition(null, FixSessionState.OBSERVE, "session_started");
    }

    public static FixSession create(Path testFile, String methodName) {
        return new FixSession(FixSessionId.create(testFile, methodName), testFile, methodName);
    }

    public String getId() {
        return id;
    }

    public Path getTestFile() {
        return testFile;
    }

    public String getMethodName() {
        return methodName;
    }

    public FixSessionState getState() {
        return state;
    }

    public FixSessionJournal getJournal() {
        return journal;
    }

    public ReasoningMemory getMemory() {
        return memory;
    }

    public ActionExecutionResult getCumulativeExecutionResult() {
        return cumulativeExecutionResult;
    }

    public void mergeExecutionResult(ActionExecutionResult result) {
        if (result == null) {
            return;
        }
        this.cumulativeExecutionResult = this.cumulativeExecutionResult.merge(result);
    }

    public void resetExecutionResult() {
        this.cumulativeExecutionResult = ActionExecutionResult.empty();
    }

    public void transitionTo(FixSessionState target, String reason) {
        Objects.requireNonNull(target, "target");
        if (target == state) {
            return;
        }
        Set<FixSessionState> allowed = ALLOWED_TRANSITIONS.getOrDefault(state, Set.of());
        if (!allowed.contains(target)) {
            throw new IllegalStateException("Invalid fix session transition: " + state + " -> " + target);
        }
        FixSessionState previous = state;
        state = target;
        journal.appendTransition(previous, target, reason == null ? "" : reason);
    }

    public void appendSnapshot(ReasoningIterationSnapshot snapshot) {
        journal.appendSnapshot(snapshot);
    }

    private static Map<FixSessionState, Set<FixSessionState>> buildAllowedTransitions() {
        Map<FixSessionState, Set<FixSessionState>> map = new EnumMap<>(FixSessionState.class);
        map.put(FixSessionState.OBSERVE, Set.of(FixSessionState.DIAGNOSE, FixSessionState.DONE, FixSessionState.ABORT));
        map.put(FixSessionState.DIAGNOSE, Set.of(FixSessionState.PLAN, FixSessionState.ABORT));
        map.put(FixSessionState.PLAN, Set.of(FixSessionState.APPLY, FixSessionState.OBSERVE, FixSessionState.ABORT));
        map.put(FixSessionState.APPLY, Set.of(FixSessionState.VERIFY, FixSessionState.ABORT));
        map.put(FixSessionState.VERIFY, Set.of(FixSessionState.LEARN, FixSessionState.DONE, FixSessionState.ABORT));
        map.put(FixSessionState.LEARN, Set.of(FixSessionState.OBSERVE, FixSessionState.DONE, FixSessionState.ABORT));
        map.put(FixSessionState.DONE, Set.of(FixSessionState.OBSERVE, FixSessionState.ABORT));
        map.put(FixSessionState.ABORT, Set.of(FixSessionState.OBSERVE));
        return Map.copyOf(map);
    }
}
