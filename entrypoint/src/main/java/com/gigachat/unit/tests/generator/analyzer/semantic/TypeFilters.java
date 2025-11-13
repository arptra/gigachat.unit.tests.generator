package com.gigachat.unit.tests.generator.analyzer.semantic;

import java.util.List;
import java.util.Locale;
import java.util.Set;

final class TypeFilters {
    static final Set<String> PRIMITIVE_TYPES = Set.of(
            "byte", "short", "int", "long", "float", "double", "boolean", "char", "void"
    );
    static final Set<String> BOXED_TYPES = Set.of(
            "Byte", "Short", "Integer", "Long", "Float", "Double", "Boolean", "Character",
            "String", "Instant", "Date"
    );
    static final Set<String> FUNCTIONAL_INTERFACES = Set.of(
            "Function", "Predicate", "Consumer", "Supplier", "BiFunction", "BiPredicate"
    );
    static final Set<String> CONTAINER_TYPES = Set.of(
            "List", "Map", "Set", "Collection", "Iterable"
    );
    private static final List<String> IGNORED_PACKAGES = List.of(
            "java.", "javax.", "jakarta."
    );

    private TypeFilters() {
    }

    static boolean isDomainType(TypeName type) {
        if (type == null || type.isUnknown()) {
            return false;
        }
        if (type.isPrimitive() || type.isBoxedPrimitive()) {
            return false;
        }
        String raw = type.rawName();
        for (String prefix : IGNORED_PACKAGES) {
            if (raw.startsWith(prefix)) {
                return false;
            }
        }
        if (FUNCTIONAL_INTERFACES.contains(type.simpleName())) {
            return false;
        }
        if (type.simpleName().equals("String")) {
            return false;
        }
        return !PRIMITIVE_TYPES.contains(type.simpleName().toLowerCase(Locale.ROOT));
    }

    static boolean shouldKeep(TypeName type) {
        return isDomainType(type) && !"UNKNOWN".equalsIgnoreCase(type.name());
    }
}
