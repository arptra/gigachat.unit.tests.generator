package com.gigachat.unit.tests.generator.analysis.api;

import java.util.List;
import java.util.Objects;

/**
 * Description of a constructor that needs to be available to the LLM prompt.
 */
public class ConstructorInfo {
    private final String className;
    private final List<String> parameterTypes;
    private final String signature;

    public ConstructorInfo(String className, List<String> parameterTypes, String signature) {
        this.className = className == null ? "" : className;
        this.parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
        this.signature = signature == null ? "" : signature;
    }

    public String getClassName() {
        return className;
    }

    public List<String> getParameterTypes() {
        return parameterTypes;
    }

    public String getSignature() {
        return signature;
    }

    @Override
    public String toString() {
        return "ConstructorInfo{" +
                "className='" + className + '\'' +
                ", parameterTypes=" + parameterTypes +
                ", signature='" + signature + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConstructorInfo that)) {
            return false;
        }
        return Objects.equals(className, that.className)
                && Objects.equals(parameterTypes, that.parameterTypes)
                && Objects.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, parameterTypes, signature);
    }
}
