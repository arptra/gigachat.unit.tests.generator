package com.gigachat.unit.tests.generator.dto;

import java.util.Objects;

/**
 * Describes a field declared on the class under test.
 */
public final class FieldMetadata {
    private final String name;
    private final String typeName;
    private final boolean isPrivate;

    public FieldMetadata(String name, String typeName, boolean isPrivate) {
        this.name = sanitise(name);
        this.typeName = sanitise(typeName);
        this.isPrivate = isPrivate;
    }

    public String getName() {
        return name;
    }

    public String getTypeName() {
        return typeName;
    }

    public boolean isPrivate() {
        return isPrivate;
    }

    private String sanitise(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }

    @Override
    public String toString() {
        return "FieldMetadata{"
                + "name='" + name + '\''
                + ", typeName='" + typeName + '\''
                + ", isPrivate=" + isPrivate
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FieldMetadata that)) {
            return false;
        }
        return isPrivate == that.isPrivate
                && Objects.equals(name, that.name)
                && Objects.equals(typeName, that.typeName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, typeName, isPrivate);
    }
}
