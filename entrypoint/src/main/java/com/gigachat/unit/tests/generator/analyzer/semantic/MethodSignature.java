package com.gigachat.unit.tests.generator.analyzer.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a method signature for either a discovered invocation or for entries stored in the registry.
 */
public final class MethodSignature {
    private final String name;
    private final TypeName returnType;
    private final List<TypeName> parameterTypes;
    private final boolean isStatic;

    public MethodSignature(String name, TypeName returnType, List<TypeName> parameterTypes, boolean isStatic) {
        this.name = normalise(name);
        this.returnType = returnType == null ? TypeName.unknown() : returnType;
        this.parameterTypes = parameterTypes == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(parameterTypes));
        this.isStatic = isStatic;
    }

    public String name() {
        return name;
    }

    public TypeName returnType() {
        return returnType;
    }

    public List<TypeName> parameterTypes() {
        return parameterTypes;
    }

    public boolean isStatic() {
        return isStatic;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MethodSignature that)) {
            return false;
        }
        return isStatic == that.isStatic
                && Objects.equals(name, that.name)
                && Objects.equals(returnType, that.returnType)
                && Objects.equals(parameterTypes, that.parameterTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, returnType, parameterTypes, isStatic);
    }

    @Override
    public String toString() {
        return returnType + " " + name + '(' + parameterTypes + ')';
    }

    private static String normalise(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }
}
