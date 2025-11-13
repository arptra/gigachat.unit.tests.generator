package com.gigachat.unit.tests.generator.analyzer.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Describes a constructor exposed via the registry or inferred from an object creation expression.
 */
public final class ConstructorSignature {
    private final TypeName owner;
    private final List<TypeName> parameterTypes;

    public ConstructorSignature(TypeName owner, List<TypeName> parameterTypes) {
        this.owner = owner == null ? TypeName.unknown() : owner;
        this.parameterTypes = parameterTypes == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(parameterTypes));
    }

    public TypeName owner() {
        return owner;
    }

    public List<TypeName> parameterTypes() {
        return parameterTypes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConstructorSignature that)) {
            return false;
        }
        return Objects.equals(owner, that.owner) && Objects.equals(parameterTypes, that.parameterTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, parameterTypes);
    }

    @Override
    public String toString() {
        return owner + "(" + parameterTypes + ")";
    }
}
