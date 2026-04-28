package com.gigachat.unit.tests.generator.resources;

/**
 * Resource-driven settings for generated test-class merge behavior.
 */
public record MergePolicy(boolean renameMethodOnCollision,
                          String collisionSuffixStem,
                          int maxCollisionAttempts,
                          boolean dedupeDuplicateAnnotations,
                          boolean normalizeImports,
                          boolean autoAddTestAnnotationWhenMissing) {
}
