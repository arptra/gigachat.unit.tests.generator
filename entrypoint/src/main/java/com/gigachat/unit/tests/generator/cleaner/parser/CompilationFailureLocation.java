package com.gigachat.unit.tests.generator.cleaner.parser;

import java.nio.file.Path;

public record CompilationFailureLocation(Path filePath, int lineNumber) {
}
