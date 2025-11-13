package com.gigachat.unit.tests.generator.analysis.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a type discovered while traversing a method body.
 */
public class ResolvedType {
    private static final Set<String> PRIMITIVES = Set.of(
            "void",
            "boolean",
            "byte",
            "short",
            "int",
            "long",
            "char",
            "float",
            "double"
    );

    private final String name;
    private final List<String> genericArguments;

    private ResolvedType(String name, List<String> genericArguments) {
        this.name = normalise(name);
        List<String> generics = genericArguments == null ? List.of() : new ArrayList<>(genericArguments);
        generics.replaceAll(ResolvedType::normalise);
        this.genericArguments = Collections.unmodifiableList(generics);
    }

    public static ResolvedType unknown() {
        return of(TypeResolver.UNKNOWN_TYPE);
    }

    public static ResolvedType of(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ResolvedType(TypeResolver.UNKNOWN_TYPE, List.of());
        }
        int genericsStart = raw.indexOf('<');
        String base = genericsStart >= 0 ? raw.substring(0, genericsStart) : raw;
        List<String> genericTokens = parseGenericTokens(raw);
        return new ResolvedType(base, genericTokens);
    }

    private static List<String> parseGenericTokens(String raw) {
        int start = raw.indexOf('<');
        int end = raw.lastIndexOf('>');
        if (start < 0 || end <= start) {
            return List.of();
        }
        String inner = raw.substring(start + 1, end);
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                addGenericToken(tokens, current);
                continue;
            }
            if (ch == '?' || Character.isWhitespace(ch)) {
                if (depth == 0 && current.length() == 0) {
                    continue;
                }
            }
            current.append(ch);
        }
        addGenericToken(tokens, current);
        return tokens.isEmpty() ? List.of() : List.copyOf(tokens);
    }

    private static void addGenericToken(List<String> tokens, StringBuilder current) {
        if (current.length() == 0) {
            return;
        }
        String token = current.toString();
        current.setLength(0);
        token = token.replace("extends", "").replace("super", "");
        token = token.replace("?", "");
        tokens.add(token.trim());
    }

    public String getName() {
        return name;
    }

    public List<String> getGenericArguments() {
        return genericArguments;
    }

    public boolean isUnknown() {
        return TypeResolver.UNKNOWN_TYPE.equals(name);
    }

    public boolean isPrimitive() {
        return PRIMITIVES.contains(name.toLowerCase(Locale.ROOT));
    }

    public List<String> flatten() {
        LinkedHashSet<String> flattened = new LinkedHashSet<>();
        if (!isUnknown()) {
            flattened.add(name);
        }
        for (String generic : genericArguments) {
            String normalised = normalise(generic);
            if (!normalised.isEmpty() && !TypeResolver.UNKNOWN_TYPE.equals(normalised)) {
                flattened.add(normalised);
            }
        }
        return List.copyOf(flattened);
    }

    public String describe() {
        if (genericArguments.isEmpty()) {
            return name;
        }
        return name + '<' + String.join(", ", genericArguments) + '>';
    }

    public static String normalise(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.endsWith("[]")) {
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        }
        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace >= 0 && lastSpace + 1 < trimmed.length()) {
            trimmed = trimmed.substring(lastSpace + 1);
        }
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < trimmed.length()) {
            trimmed = trimmed.substring(lastDot + 1);
        }
        return trimmed;
    }

    @Override
    public String toString() {
        return describe();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResolvedType that)) {
            return false;
        }
        return Objects.equals(name, that.name)
                && Objects.equals(genericArguments, that.genericArguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, genericArguments);
    }
}
