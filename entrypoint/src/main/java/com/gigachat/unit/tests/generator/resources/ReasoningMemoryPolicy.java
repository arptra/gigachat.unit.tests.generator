package com.gigachat.unit.tests.generator.resources;

/**
 * Resource-driven defaults for persistent reasoning memory.
 */
public record ReasoningMemoryPolicy(int defaultContextBudget,
                                    int maxErrorHistory) {
}
