package com.gigachat.unit.tests.generator.reasoning.service.action;

import com.gigachat.unit.tests.generator.reasoning.service.BuildFileEditor;

import java.util.Objects;

/**
 * Adds a test dependency to the build file while avoiding duplicates.
 */
public class AddDependencyAction implements ProjectModificationAction {

    private final BuildFileEditor buildFileEditor;
    private final String dependencyNotation;

    public AddDependencyAction(BuildFileEditor buildFileEditor, String dependencyNotation) {
        this.buildFileEditor = Objects.requireNonNull(buildFileEditor, "buildFileEditor");
        this.dependencyNotation = Objects.requireNonNull(dependencyNotation, "dependencyNotation");
    }

    @Override
    public void apply() {
        buildFileEditor.addTestDependency(dependencyNotation);
    }

    @Override
    public String describe() {
        return "ADD_DEPENDENCY " + dependencyNotation;
    }
}
