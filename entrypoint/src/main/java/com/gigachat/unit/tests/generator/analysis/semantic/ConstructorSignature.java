package com.gigachat.unit.tests.generator.analysis.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a constructor invocation for a given type.
 */
public class ConstructorSignature {
    private final String typeName;
    private final List<String> parameterTypes;
    private final String signature;

    public ConstructorSignature(String typeName, List<String> parameterTypes) {
        this(typeName, parameterTypes, buildSignature(typeName, parameterTypes));
    }

    public ConstructorSignature(String typeName, List<String> parameterTypes, String signature) {
        this.typeName = normalise(typeName);
        List<String> params = parameterTypes == null ? List.of() : new ArrayList<>(parameterTypes);
        params.replaceAll(ConstructorSignature::normalise);
        this.parameterTypes = Collections.unmodifiableList(params);
        this.signature = signature == null ? buildSignature(this.typeName, this.parameterTypes) : signature;
    }

    public String getTypeName() {
        return typeName;
    }

    public List<String> getParameterTypes() {
        return parameterTypes;
    }

    public String getSignature() {
        return signature;
    }

    private static String buildSignature(String typeName, List<String> params) {
        String name = normalise(typeName);
        return name + '(' + String.join(", ", params == null ? List.of() : params) + ')';
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
        return signature;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConstructorSignature that)) {
            return false;
        }
        return Objects.equals(typeName, that.typeName)
                && Objects.equals(parameterTypes, that.parameterTypes)
                && Objects.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeName, parameterTypes, signature);
    }
}
