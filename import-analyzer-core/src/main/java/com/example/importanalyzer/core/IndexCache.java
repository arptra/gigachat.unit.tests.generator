package com.example.importanalyzer.core;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IndexCache {
    private final Path path;
    private final ObjectMapper mapper;

    public IndexCache(Path path, ObjectMapper mapper) {
        this.path = path;
        this.mapper = mapper;
    }

    public void save(SerializedIndex index) {
        try {
            Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
            mapper.writeValue(path.toFile(), index);
        } catch (IOException e) {
            // ignore
        }
    }

    public SerializedIndex load() {
        try {
            return mapper.readValue(path.toFile(), SerializedIndex.class);
        } catch (IOException e) {
            return null;
        }
    }

    public record SerializedIndex(List<ClassIndexEntry> entries, Map<String, Set<String>> graph, Map<String, Long> fileTimestamps) {}
}
