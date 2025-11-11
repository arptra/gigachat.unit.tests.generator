package com.gigachat.unit.tests.generator.execute;

import java.util.List;

/**
 * Result of executing generated tests.
 */
public record ExecuteResult(boolean success,
                            List<String> failedTests,
                            String stdout,
                            String stderr) {

    public ExecuteResult {
        failedTests = failedTests == null ? List.of() : List.copyOf(failedTests);
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }
}
