package com.gigachat.unit.tests.generator.analysis.semantic;

import com.gigachat.unit.tests.generator.analysis.api.StaticMockStrategy;

import java.util.Objects;

/**
 * Represents a static method dependency discovered in the method body.
 */
public class StaticCall {
    private final String owner;
    private final String methodName;
    private final StaticMockStrategy strategy;
    private final String replacement;

    public StaticCall(String owner, String methodName, StaticMockStrategy strategy, String replacement) {
        this.owner = owner == null ? "Unknown" : owner.trim();
        this.methodName = methodName == null ? "" : methodName.trim();
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
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StaticCall staticCall)) {
            return false;
        }
        return Objects.equals(owner, staticCall.owner)
                && Objects.equals(methodName, staticCall.methodName)
                && strategy == staticCall.strategy
                && Objects.equals(replacement, staticCall.replacement);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, methodName, strategy, replacement);
    }

    @Override
    public String toString() {
        return owner + "#" + methodName + " (" + strategy + ')';
    }
}
