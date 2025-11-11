package com.gigachat.unit.tests.generator.config;

/**
 * Represents how the pipeline parallelises generation tasks.
 */
public enum ParallelMode {
    NONE,
    CLASS,
    METHOD,
    FULL;

    public boolean paralleliseClasses() {
        return this == CLASS || this == FULL;
    }

    public boolean paralleliseMethods() {
        return this == METHOD || this == FULL;
    }
}
