package com.gigachat.unit.tests.generator.cleaner;

import com.gigachat.unit.tests.generator.cleaner.rules.classlevel.api.TestClassCleanerRule;
import com.gigachat.unit.tests.generator.cleaner.rules.packagelevel.api.TestPackageCleanerRule;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Container for cleaner rules grouped by their execution scope.
 */
public final class CleanerRules {
    private final List<TestClassCleanerRule> classRules;
    private final List<TestPackageCleanerRule> packageRules;

    public CleanerRules(List<TestClassCleanerRule> classRules, List<TestPackageCleanerRule> packageRules) {
        this.classRules = List.copyOf(Objects.requireNonNullElse(classRules, Collections.emptyList()));
        this.packageRules = List.copyOf(Objects.requireNonNullElse(packageRules, Collections.emptyList()));
    }

    public List<TestClassCleanerRule> getClassRules() {
        return classRules;
    }

    public List<TestPackageCleanerRule> getPackageRules() {
        return packageRules;
    }
}
