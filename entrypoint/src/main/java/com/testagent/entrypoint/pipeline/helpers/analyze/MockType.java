package com.testagent.entrypoint.pipeline.helpers.analyze;

/**
 * Describes the high-level type of mocking strategy required for a dependency usage.
 */
public enum MockType {
    FIELD,
    CONSTRUCTOR,
    STATIC,
    CHAIN,
    UNKNOWN
}
