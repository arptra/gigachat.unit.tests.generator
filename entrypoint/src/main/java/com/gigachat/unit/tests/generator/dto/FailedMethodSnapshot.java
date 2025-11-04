package com.gigachat.unit.tests.generator.dto;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Stores information about a failed generation attempt that can be used for later repair steps.
 */
public record FailedMethodSnapshot(Path testClassFile,
                                   String methodName,
                                   String methodBody,
                                   List<String> imports,
                                   String reason,
                                   Instant createdAt) {

    public FailedMethodSnapshot {
        imports = imports == null ? List.of() : List.copyOf(imports);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
