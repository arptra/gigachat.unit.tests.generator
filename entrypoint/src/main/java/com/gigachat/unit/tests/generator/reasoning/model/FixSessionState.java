package com.gigachat.unit.tests.generator.reasoning.model;

/**
 * Runtime states of a single reasoning fix session.
 */
public enum FixSessionState {
    OBSERVE,
    DIAGNOSE,
    PLAN,
    APPLY,
    VERIFY,
    LEARN,
    DONE,
    ABORT
}

