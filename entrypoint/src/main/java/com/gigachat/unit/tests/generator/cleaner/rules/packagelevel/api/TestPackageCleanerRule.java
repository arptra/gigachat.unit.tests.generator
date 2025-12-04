package com.gigachat.unit.tests.generator.cleaner.rules.packagelevel.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Represents a clean-up rule that works across a collection of test classes.
 */
public interface TestPackageCleanerRule {
    /**
     * Applies the rule to the provided collection of test files.
     *
     * @param projectRoot root path of the project under cleaning
     * @param testFiles   list of discovered test files
     * @return {@code true} when any file was changed
     * @throws IOException if underlying files cannot be read or written
     */
    boolean apply(Path projectRoot, List<Path> testFiles) throws IOException;
}
