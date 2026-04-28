package com.gigachat.unit.tests.generator.gradle;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runtime classpath details for executing a compiled generated test.
 */
public record ResolvedTestRuntimeClasspath(Path buildRoot,
                                           Path moduleRoot,
                                           String modulePath,
                                           Set<Path> entries,
                                           List<String> messages) {

    public ResolvedTestRuntimeClasspath {
        entries = entries == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(entries));
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
