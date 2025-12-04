package com.gigachat.unit.tests.generator.cleaner.rules.classlevel.api;

import com.gigachat.unit.tests.generator.cleaner.TestFileContext;

import java.io.IOException;

/**
 * Represents a clean-up rule that targets a single test class file.
 */
public interface TestClassCleanerRule {
    /**
     * Applies the rule to the provided test file context.
     *
     * @param context mutable test file context
     * @return {@code true} when the rule changed the file
     * @throws IOException if the underlying file cannot be read or written
     */
    boolean apply(TestFileContext context) throws IOException;
}
