package com.gigachat.unit.tests.generator.resources;

import java.util.List;

/**
 * Resource-driven generation or generated-test artifact validation pattern.
 */
public record GenerationValidationPattern(String id,
                                          String errorCode,
                                          String title,
                                          String summary,
                                          List<String> containsAny,
                                          List<String> retryConstraints,
                                          List<String> dynamicConstraintBuilders,
                                          String llmHeuristic) {
}
