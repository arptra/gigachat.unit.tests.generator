package com.gigachat.unit.tests.generator.analysis.api;

import java.util.List;
import java.util.Objects;

/**
 * Describes a single method invocation that needs to be represented in the
 * generated prompt.
 */
public class MethodInfo {
    private final String methodName;
    private final List<String> parameterTypes;
    private final String returnType;

    public MethodInfo(String methodName, List<String> parameterTypes, String returnType) {
        this.methodName = methodName == null ? "" : methodName;
        this.parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
        this.returnType = returnType == null ? "unknown" : returnType;
    }

    public String getMethodName() {
        return methodName;
    }

    public List<String> getParameterTypes() {
        return parameterTypes;
    }

    public String getReturnType() {
        return returnType;
    }

    @Override
    public String toString() {
        return "MethodInfo{" +
                "methodName='" + methodName + '\'' +
                ", parameterTypes=" + parameterTypes +
                ", returnType='" + returnType + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MethodInfo that)) {
            return false;
        }
        return Objects.equals(methodName, that.methodName)
                && Objects.equals(parameterTypes, that.parameterTypes)
                && Objects.equals(returnType, that.returnType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(methodName, parameterTypes, returnType);
    }
}
