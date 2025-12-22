package com.gigachat.unit.tests.generator.reasoning.service.action;

import com.gigachat.unit.tests.generator.reasoning.service.SourceFileEditor;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Adds an import statement to a Java source file if not already present.
 */
public class AddImportAction implements ProjectModificationAction {

    private final SourceFileEditor sourceFileEditor;
    private final Path filePath;
    private final String importFqcn;

    public AddImportAction(SourceFileEditor sourceFileEditor, Path filePath, String importFqcn) {
        this.sourceFileEditor = Objects.requireNonNull(sourceFileEditor, "sourceFileEditor");
        this.filePath = Objects.requireNonNull(filePath, "filePath");
        this.importFqcn = Objects.requireNonNull(importFqcn, "importFqcn");
    }

    @Override
    public void apply() {
        sourceFileEditor.addImport(filePath, importFqcn);
    }

    @Override
    public String describe() {
        return "ADD_IMPORT " + importFqcn + " -> " + filePath;
    }

    public Path getFilePath() {
        return filePath;
    }
}
