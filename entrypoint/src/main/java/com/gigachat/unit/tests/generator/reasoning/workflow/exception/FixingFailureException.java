package com.gigachat.unit.tests.generator.reasoning.workflow.exception;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.reasoning.model.FixSession;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningIterationSnapshot;

import java.util.List;

/**
 * Raised when the reasoning loop cannot fix compilation failures within the configured iterations.
 */
public class FixingFailureException extends RuntimeException {

    private final CompileResult lastResult;
    private final List<ReasoningIterationSnapshot> iterationSnapshots;
    private final FixSession fixSession;

    public FixingFailureException(String message, CompileResult lastResult) {
        this(message, lastResult, null, List.of());
    }

    public FixingFailureException(String message,
                                  CompileResult lastResult,
                                  FixSession fixSession) {
        this(message,
                lastResult,
                fixSession,
                fixSession == null ? List.of() : fixSession.getJournal().getIterationSnapshots());
    }

    public FixingFailureException(String message,
                                  CompileResult lastResult,
                                  List<ReasoningIterationSnapshot> iterationSnapshots) {
        this(message, lastResult, null, iterationSnapshots);
    }

    private FixingFailureException(String message,
                                   CompileResult lastResult,
                                   FixSession fixSession,
                                   List<ReasoningIterationSnapshot> iterationSnapshots) {
        super(message);
        this.lastResult = lastResult;
        this.fixSession = fixSession;
        this.iterationSnapshots = iterationSnapshots == null ? List.of() : List.copyOf(iterationSnapshots);
    }

    public CompileResult getLastResult() {
        return lastResult;
    }

    public List<ReasoningIterationSnapshot> getIterationSnapshots() {
        return iterationSnapshots;
    }

    public FixSession getFixSession() {
        return fixSession;
    }
}
