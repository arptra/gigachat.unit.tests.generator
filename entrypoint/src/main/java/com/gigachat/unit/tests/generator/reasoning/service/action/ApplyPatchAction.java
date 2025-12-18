package com.gigachat.unit.tests.generator.reasoning.service.action;

import com.gigachat.unit.tests.generator.reasoning.service.SourceFileEditor;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Applies a textual patch to a source file.
 */
public class ApplyPatchAction implements ProjectModificationAction {

    private final SourceFileEditor sourceFileEditor;
    private final Path filePath;
    private final String patch;

    public ApplyPatchAction(SourceFileEditor sourceFileEditor, Path filePath, String patch) {
        this.sourceFileEditor = Objects.requireNonNull(sourceFileEditor, "sourceFileEditor");
        this.filePath = Objects.requireNonNull(filePath, "filePath");
        this.patch = Objects.requireNonNull(patch, "patch");
    }

    @Override
    public void apply() {
        sourceFileEditor.applyPatch(filePath, patch);
    }

    @Override
    public String describe() {
        return "APPLY_PATCH " + filePath;
    }
}
