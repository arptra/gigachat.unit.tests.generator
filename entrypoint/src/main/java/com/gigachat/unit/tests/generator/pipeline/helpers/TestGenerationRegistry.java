package com.gigachat.unit.tests.generator.pipeline.helpers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Persists information about successfully generated test methods to avoid duplicate work.
 */
public class TestGenerationRegistry {

    private final Path projectRoot;
    private final Path registryFile;

    public TestGenerationRegistry(Path projectRoot) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        Path agentDir = this.projectRoot.resolve(".agent");
        Path registryDir = agentDir.resolve("generated-tests");
        try {
            Files.createDirectories(registryDir);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create registry directory at " + registryDir, exception);
        }
        this.registryFile = registryDir.resolve("generated-tests.json");
    }

    public boolean hasEntry(Path testFile, String methodSignature) {
        Map<String, Set<String>> data = read();
        String key = toClassKey(testFile);
        Set<String> methods = data.get(key);
        if (methods == null) {
            return false;
        }
        return methods.contains(normalize(methodSignature));
    }

    public void record(Path testFile, String methodSignature) {
        Map<String, Set<String>> data = read();
        String key = toClassKey(testFile);
        Set<String> methods = data.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
        methods.add(normalize(methodSignature));
        write(data);
    }

    private Map<String, Set<String>> read() {
        if (!Files.exists(registryFile)) {
            return new LinkedHashMap<>();
        }
        try {
            String content = Files.readString(registryFile, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return new LinkedHashMap<>();
            }
            JSONObject root = new JSONObject(content);
            Map<String, Set<String>> result = new LinkedHashMap<>();
            for (String key : root.keySet()) {
                JSONArray array = root.optJSONArray(key);
                if (array == null) {
                    continue;
                }
                Set<String> methods = new LinkedHashSet<>();
                for (int i = 0; i < array.length(); i++) {
                    String entry = array.optString(i, "");
                    if (!entry.isBlank()) {
                        methods.add(entry);
                    }
                }
                result.put(key, methods);
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read generated tests registry at " + registryFile, exception);
        }
    }

    private void write(Map<String, Set<String>> data) {
        JSONObject root = new JSONObject();
        data.forEach((key, methods) -> root.put(key, new JSONArray(methods)));
        try {
            Files.writeString(registryFile,
                    root.toString(2),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write generated tests registry at " + registryFile, exception);
        }
    }

    private String toClassKey(Path testFile) {
        Path normalized = testFile.toAbsolutePath().normalize();
        try {
            Path relative = projectRoot.relativize(normalized);
            return relative.toString();
        } catch (IllegalArgumentException ignored) {
            return normalized.toString();
        }
    }

    private String normalize(String methodSignature) {
        return methodSignature == null ? "" : methodSignature.trim();
    }

    public Map<String, Set<String>> snapshot() {
        Map<String, Set<String>> data = read();
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        data.forEach((k, v) -> copy.put(k, Collections.unmodifiableSet(new LinkedHashSet<>(v))));
        return Collections.unmodifiableMap(copy);
    }
}
