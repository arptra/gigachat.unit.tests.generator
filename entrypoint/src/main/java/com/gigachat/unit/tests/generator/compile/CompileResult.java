package com.gigachat.unit.tests.generator.compile;

import java.util.List;

/**
 * Result of a compilation attempt.
 */
public record CompileResult(boolean success,
                            List<String> messages,
                            String stdout,
                            String stderr) {

    public CompileResult {
        messages = messages == null ? List.of() : List.copyOf(messages);
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }
}
