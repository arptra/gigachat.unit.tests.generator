package com.gigachat.unit.tests.generator.compile;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared compilation cache that holds {@link CompilationCacheEntry} instances for compiled sources.
 * The cache is process-wide so every {@link GradleCompilerInvoker} instance can benefit from the same
 * cached compilation results across the full lifecycle of the application.
 */
public final class CompilationCache {
    private static final CompilationCache INSTANCE = new CompilationCache();
    private final Map<Path, CompilationCacheEntry> cache = new ConcurrentHashMap<>();

    private CompilationCache() {
    }

    public static CompilationCache getInstance() {
        return INSTANCE;
    }

    public CompilationCacheEntry get(Path sourcePath) {
        return cache.get(normalize(sourcePath));
    }

    public void put(Path sourcePath, CompilationCacheEntry entry) {
        cache.put(normalize(sourcePath), entry);
    }

    public void remove(Path sourcePath) {
        cache.remove(normalize(sourcePath));
    }

    private Path normalize(Path sourcePath) {
        return sourcePath.toAbsolutePath().normalize();
    }
}
