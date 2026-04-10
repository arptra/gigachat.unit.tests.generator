package com.gigachat.unit.tests.generator.reasoning.service.action;

/**
 * Represents a project-modifying action issued by the reasoning model. Implementations encapsulate
 * a single side effect without producing user-facing output.
 */
public interface ProjectModificationAction {

    /**
     * Applies the modification to the project.
     */
    void apply();

    /**
     * Returns a short description of the action performed, suitable for inclusion in the execution log.
     */
    String describe();
}
