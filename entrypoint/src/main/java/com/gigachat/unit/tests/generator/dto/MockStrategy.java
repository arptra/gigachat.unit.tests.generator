package com.gigachat.unit.tests.generator.dto;

/**
 * Defines strategies that the pipeline may use for mocking dependencies when generating tests.
 */
public enum MockStrategy {
    NONE,
    MOCKITO,
    SPY,
    STATIC,
    STATIC_SKIP,
    CHAIN_PARTIAL,
    LLM_ASSISTED
}
