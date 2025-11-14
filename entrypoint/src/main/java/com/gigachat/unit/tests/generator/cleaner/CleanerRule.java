package com.gigachat.unit.tests.generator.cleaner;

import java.io.IOException;

/**
 * Represents a single clean-up rule that can mutate a test file. Rules are intentionally
 * small and composable so that new validations can be plugged in without modifying the
 * cleaner orchestration logic.
 */
public interface CleanerRule {
    /**
     * Applies the rule to the provided test file context.
     *
     * @param context mutable test file context
     * @return {@code true} when the rule changed the file
     * @throws IOException if the underlying file cannot be read or written
     */
    boolean apply(TestFileContext context) throws IOException;
}
