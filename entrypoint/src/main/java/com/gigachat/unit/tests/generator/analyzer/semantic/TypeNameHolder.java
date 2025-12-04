package com.gigachat.unit.tests.generator.analyzer.semantic;

/**
 * Allows components to expose a {@link TypeName} without additional allocations.
 */
public interface TypeNameHolder {
    TypeName typeName();
}
