package com.gigachat.unit.tests.generator.analysis.semantic;

import java.util.Objects;

/**
 * Represents a raw static invocation detected in the AST.
 */
public class RawStaticCall {
    private final String owner;
    private final String methodName;

    public RawStaticCall(String owner, String methodName) {
        this.owner = owner == null ? "" : owner.trim();
        this.methodName = methodName == null ? "" : methodName.trim();
    }

    public String getOwner() {
        return owner;
    }

    public String getMethodName() {
        return methodName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RawStaticCall that)) {
            return false;
        }
        return Objects.equals(owner, that.owner)
                && Objects.equals(methodName, that.methodName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, methodName);
    }

    @Override
    public String toString() {
        return owner + "#" + methodName;
    }
}
