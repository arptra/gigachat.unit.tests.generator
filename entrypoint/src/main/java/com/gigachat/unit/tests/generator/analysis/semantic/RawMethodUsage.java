package com.gigachat.unit.tests.generator.analysis.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a method invocation discovered in the AST before metadata merging.
 */
public class RawMethodUsage {
    private final ResolvedType ownerType;
    private final String methodName;
    private final List<ResolvedType> parameterTypes;
    private final ResolvedType returnType;

    public RawMethodUsage(ResolvedType ownerType,
                          String methodName,
                          List<ResolvedType> parameterTypes,
                          ResolvedType returnType) {
        this.ownerType = ownerType == null ? ResolvedType.unknown() : ownerType;
        this.methodName = methodName == null ? "" : methodName.trim();
        List<ResolvedType> params = parameterTypes == null ? List.of() : new ArrayList<>(parameterTypes);
        this.parameterTypes = Collections.unmodifiableList(params);
        this.returnType = returnType == null ? ResolvedType.unknown() : returnType;
    }

    public ResolvedType getOwnerType() {
        return ownerType;
    }

    public String getMethodName() {
        return methodName;
    }

    public List<ResolvedType> getParameterTypes() {
        return parameterTypes;
    }

    public ResolvedType getReturnType() {
        return returnType;
    }

    @Override
    public String toString() {
        return ownerType.describe() + "#" + methodName + parameterTypes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RawMethodUsage that)) {
            return false;
        }
        return Objects.equals(ownerType, that.ownerType)
                && Objects.equals(methodName, that.methodName)
                && Objects.equals(parameterTypes, that.parameterTypes)
                && Objects.equals(returnType, that.returnType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ownerType, methodName, parameterTypes, returnType);
    }
}
