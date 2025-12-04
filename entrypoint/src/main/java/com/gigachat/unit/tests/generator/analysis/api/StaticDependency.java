package com.gigachat.unit.tests.generator.analysis.api;

import java.util.Objects;

/**
 * Captures information about a static method invocation that needs special
 * handling inside the generated tests.
 */
public class StaticDependency {
    private final String owner;
    private final String methodName;
    private final StaticMockStrategy strategy;
    private final String replacement;

    public StaticDependency(String owner,
                            String methodName,
                            StaticMockStrategy strategy,
                            String replacement) {
        this.owner = owner == null ? "" : owner;
        this.methodName = methodName == null ? "" : methodName;
        this.strategy = strategy == null ? StaticMockStrategy.STUB : strategy;
        this.replacement = replacement;
    }

    public String getOwner() {
        return owner;
    }

    public String getMethodName() {
        return methodName;
    }

    public StaticMockStrategy getStrategy() {
        return strategy;
    }

    public String getReplacement() {
        return replacement;
    }

    @Override
    public String toString() {
        return "StaticDependency{" +
                "owner='" + owner + '\'' +
                ", methodName='" + methodName + '\'' +
                ", strategy=" + strategy +
                ", replacement='" + replacement + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StaticDependency that)) {
            return false;
        }
        return Objects.equals(owner, that.owner)
                && Objects.equals(methodName, that.methodName)
                && strategy == that.strategy
                && Objects.equals(replacement, that.replacement);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, methodName, strategy, replacement);
    }
}
