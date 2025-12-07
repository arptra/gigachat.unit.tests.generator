package com.example.importanalyzer.core;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class ImportGraph {
    private final Map<Path, Set<String>> fileToTypes = new ConcurrentHashMap<>();
    private final Map<String, Set<Path>> typeToFiles = new ConcurrentHashMap<>();

    public void recordUsage(Path file, String typeName) {
        fileToTypes.computeIfAbsent(file, f -> new CopyOnWriteArraySet<>()).add(typeName);
        typeToFiles.computeIfAbsent(typeName, t -> new CopyOnWriteArraySet<>()).add(file);
    }

    public Set<String> typesForFile(Path file) {
        return fileToTypes.getOrDefault(file, Set.of());
    }

    public Set<Path> filesForType(String type) {
        return typeToFiles.getOrDefault(type, Set.of());
    }

    public Map<Path, Set<String>> viewFileToTypes() {
        return Collections.unmodifiableMap(fileToTypes);
    }
}
