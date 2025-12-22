package com.gigachat.unit.tests.generator.reasoning.workflow.exception;

import com.gigachat.unit.tests.generator.compile.CompileResult;

/**
 * Raised when the reasoning loop cannot fix compilation failures within the configured iterations.
 */
public class FixingFailureException extends RuntimeException {

    private final CompileResult lastResult;

    public FixingFailureException(String message, CompileResult lastResult) {
        super(message);
        this.lastResult = lastResult;
    }

    public CompileResult getLastResult() {
        return lastResult;
    }
}

