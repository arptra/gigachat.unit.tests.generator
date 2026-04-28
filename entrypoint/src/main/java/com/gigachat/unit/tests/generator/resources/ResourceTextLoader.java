package com.gigachat.unit.tests.generator.resources;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Small helper for loading editable text resources from the classpath.
 */
public class ResourceTextLoader {

    public String readText(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        try (InputStream stream = openResource(resourcePath)) {
            if (stream == null) {
                return "";
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load resource: " + resourcePath, exception);
        }
    }

    public List<String> readDirectiveLines(String resourcePath) {
        return readText(resourcePath).lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("#"))
                .toList();
    }

    private InputStream openResource(String resourcePath) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = ResourceTextLoader.class.getClassLoader();
        }
        return classLoader.getResourceAsStream(resourcePath);
    }
}
