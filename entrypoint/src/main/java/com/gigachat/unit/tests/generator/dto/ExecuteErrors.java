package com.gigachat.unit.tests.generator.dto;

import java.nio.file.Path;
import java.util.List;

/**
 * Represents execution errors that occurred while running a generated test method.
 */
public record ExecuteErrors(Path testClassFile,
                            String methodName,
                            List<String> failedTests,
                            String stdout,
                            String stderr) {

    public ExecuteErrors {
        failedTests = failedTests == null ? List.of() : List.copyOf(failedTests);
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }
}
