package com.gigachat.unit.tests.generator.dto;

import java.nio.file.Path;
import java.util.List;

/**
 * Represents compilation errors for a generated test method.
 */
public record CompileErrors(Path testClassFile,
                            String methodName,
                            List<String> messages,
                            String stdout,
                            String stderr) {

    public CompileErrors {
        messages = messages == null ? List.of() : List.copyOf(messages);
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }
}
