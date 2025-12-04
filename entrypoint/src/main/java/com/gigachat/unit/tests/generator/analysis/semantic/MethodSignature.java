package com.gigachat.unit.tests.generator.analysis.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a method invocation grouped by the owning type.
 */
public class MethodSignature {
    private final String typeName;
    private final String methodName;
    private final List<String> parameterTypes;
    private final String returnType;

    public MethodSignature(String typeName,
                           String methodName,
                           List<String> parameterTypes,
                           String returnType) {
        this.typeName = normalise(typeName);
        this.methodName = methodName == null ? "" : methodName.trim();
        List<String> params = parameterTypes == null ? List.of() : new ArrayList<>(parameterTypes);
        params.replaceAll(MethodSignature::normalise);
        this.parameterTypes = Collections.unmodifiableList(params);
        this.returnType = normalise(returnType);
    }

    public String getTypeName() {
        return typeName;
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

    private static String normalise(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.endsWith("[]")) {
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        }
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < trimmed.length()) {
            trimmed = trimmed.substring(lastDot + 1);
        }
        return trimmed;
    }

    @Override
    public String toString() {
        return returnType + " " + methodName + '(' + String.join(", ", parameterTypes) + ')';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MethodSignature that)) {
            return false;
        }
        return Objects.equals(typeName, that.typeName)
                && Objects.equals(methodName, that.methodName)
                && Objects.equals(parameterTypes, that.parameterTypes)
                && Objects.equals(returnType, that.returnType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeName, methodName, parameterTypes, returnType);
    }
}
