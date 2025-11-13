package com.gigachat.unit.tests.generator.analyzer.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Represents a type discovered during semantic analysis. The implementation keeps the raw textual
 * representation but also exposes helpers to work with generics and to derive simple names.
 */
public final class TypeName implements TypeName.TypeNameHolder {
    private static final String UNKNOWN_VALUE = "UNKNOWN";
    private static final TypeName UNKNOWN = new TypeName(UNKNOWN_VALUE);

    private final String name;
    private final List<TypeName> typeArguments;

    private TypeName(String value) {
        String normalised = normalise(value);
        this.name = normalised.isEmpty() ? UNKNOWN_VALUE : normalised;
        this.typeArguments = parseTypeArguments(this.name);
    }

    public static TypeName of(String value) {
        if (value instanceof TypeNameHolder holder) {
            return holder.typeName();
        }
        return new TypeName(value);
    }

    public static TypeName of(TypeName typeName) {
        return typeName == null ? unknown() : typeName;
    }

    public static TypeName unknown() {
        return UNKNOWN;
    }

    public String name() {
        return name;
    }

    public String rawName() {
        int genericStart = name.indexOf('<');
        return genericStart >= 0 ? name.substring(0, genericStart) : name;
    }

    public String simpleName() {
        String raw = rawName();
        int lastDot = raw.lastIndexOf('.');
        return lastDot >= 0 ? raw.substring(lastDot + 1) : raw;
    }

    public List<TypeName> typeArguments() {
        return typeArguments;
    }

    public boolean isUnknown() {
        return UNKNOWN_VALUE.equalsIgnoreCase(name);
    }

    public boolean isPrimitive() {
        String simple = simpleName().toLowerCase(Locale.ROOT);
        return TypeFilters.PRIMITIVE_TYPES.contains(simple);
    }

    public boolean isJavaType() {
        String raw = rawName();
        return raw.startsWith("java.") || raw.startsWith("javax.");
    }

    public boolean isBoxedPrimitive() {
        return TypeFilters.BOXED_TYPES.contains(simpleName());
    }

    public boolean isFunctionalInterface() {
        return TypeFilters.FUNCTIONAL_INTERFACES.contains(simpleName());
    }

    public boolean isContainer() {
        return TypeFilters.CONTAINER_TYPES.contains(simpleName());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TypeName typeName = (TypeName) o;
        return name.equals(typeName.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return name;
    }

    private static String normalise(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.replace("\\n", " ").replaceAll("\\s+", " ");
    }

    private static List<TypeName> parseTypeArguments(String value) {
        int start = value.indexOf('<');
        int end = value.lastIndexOf('>');
        if (start < 0 || end <= start) {
            return List.of();
        }
        String inside = value.substring(start + 1, end);
        List<String> parts = splitGenerics(inside);
        if (parts.isEmpty()) {
            return List.of();
        }
        List<TypeName> parsed = new ArrayList<>(parts.size());
        for (String part : parts) {
            String cleaned = normalise(part);
            if (!cleaned.isEmpty()) {
                parsed.add(new TypeName(cleaned));
            }
        }
        return Collections.unmodifiableList(parsed);
    }

    private static List<String> splitGenerics(String inside) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < inside.length(); i++) {
            char ch = inside.charAt(i);
            if (ch == '<') {
                depth++;
                current.append(ch);
                continue;
            }
            if (ch == '>') {
                depth = Math.max(0, depth - 1);
                current.append(ch);
                continue;
            }
            if (ch == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    /**
     * Allows efficient reuse when a type name is already wrapped by another component.
     */
    public interface TypeNameHolder {
        TypeName typeName();
    }

    @Override
    public TypeName typeName() {
        return this;
    }
}
