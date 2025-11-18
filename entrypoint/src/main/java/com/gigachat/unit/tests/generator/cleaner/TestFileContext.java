package com.gigachat.unit.tests.generator.cleaner;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Mutable representation of a test file. The context lazily loads the file contents,
 * exposes the parsed compilation unit and keeps track of whether the file needs to be
 * persisted back to disk after rule application.
 */
public final class TestFileContext {
    private final Path file;
    private final JavaParser javaParser;
    private String source;
    private CompilationUnit compilationUnit;
    private boolean textDirty;
    private boolean astDirty;
    private boolean deleted;

    public TestFileContext(Path file, JavaParser javaParser) {
        this.file = file;
        this.javaParser = javaParser;
    }

    public Path getFile() {
        return file;
    }

    public String getSource() throws IOException {
        if (source == null) {
            source = Files.readString(file);
        }
        return source;
    }

    public Optional<CompilationUnit> getCompilationUnit() throws IOException {
        if (deleted) {
            return Optional.empty();
        }
        if (compilationUnit != null) {
            return Optional.of(compilationUnit);
        }
        ParseResult<CompilationUnit> parseResult = javaParser.parse(getSource());
        if (parseResult.getResult().isPresent()) {
            compilationUnit = parseResult.getResult().get();
            return Optional.of(compilationUnit);
        }
        return Optional.empty();
    }

    public void updateSource(String updated) {
        if (deleted) {
            return;
        }
        source = updated;
        textDirty = true;
    }

    public void markAstDirty() {
        if (deleted) {
            return;
        }
        astDirty = true;
        textDirty = true;
    }

    public void saveIfDirty() throws IOException {
        if (deleted || !textDirty) {
            return;
        }
        if (astDirty && compilationUnit != null) {
            source = compilationUnit.toString();
        }
        Files.writeString(file, source, StandardCharsets.UTF_8);
        textDirty = false;
        astDirty = false;
    }

    public void deleteFile() throws IOException {
        Files.deleteIfExists(file);
        deleted = true;
    }
}
