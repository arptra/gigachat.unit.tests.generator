package com.example.importanalyzer.core;

import java.nio.file.Path;

public record ClassIndexEntry(String fullyQualifiedName, String simpleName, ClassOrigin origin, Path location) {
}
