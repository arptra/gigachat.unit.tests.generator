package com.gigachat.unit.tests.generator.dto;

import java.util.Objects;

/**
 * Describes a dependency that needs to be mocked while generating tests.
 */
public record MockTarget(String qualifiedType, String identifier) {

    public MockTarget {
        Objects.requireNonNull(qualifiedType, "qualifiedType");
        Objects.requireNonNull(identifier, "identifier");
    }
}
