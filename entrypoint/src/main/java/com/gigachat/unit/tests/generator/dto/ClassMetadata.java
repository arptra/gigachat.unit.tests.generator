package com.gigachat.unit.tests.generator.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Captures structural information about the class under test.
 */
public final class ClassMetadata {
    private final String className;
    private final List<FieldMetadata> fields;

    public ClassMetadata(String className, List<FieldMetadata> fields) {
        this.className = sanitise(className);
        if (fields == null || fields.isEmpty()) {
            this.fields = List.of();
        } else {
            this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
        }
    }

    public String getClassName() {
        return className;
    }

    public List<FieldMetadata> getFields() {
        return fields;
    }

    private String sanitise(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }

    @Override
    public String toString() {
        return "ClassMetadata{"
                + "className='" + className + '\''
                + ", fields=" + fields
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClassMetadata that)) {
            return false;
        }
        return Objects.equals(className, that.className)
                && Objects.equals(fields, that.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, fields);
    }
}
