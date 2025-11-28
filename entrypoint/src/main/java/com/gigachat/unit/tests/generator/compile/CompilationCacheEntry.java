package com.gigachat.unit.tests.generator.compile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stores a cached compilation result along with timestamps for when the source
 * file was last modified and compiled.
 */
public class CompilationCacheEntry {
    private final long lastModifiedMillis;
    private final long lastCompiledMillis;
    private final CompileResult result;

    private CompilationCacheEntry(long lastModifiedMillis, long lastCompiledMillis, CompileResult result) {
        this.lastModifiedMillis = lastModifiedMillis;
        this.lastCompiledMillis = lastCompiledMillis;
        this.result = result;
    }

    public static CompilationCacheEntry from(Path sourceFile, CompileResult result) throws IOException {
        long lastModified = Files.getLastModifiedTime(sourceFile).toMillis();
        long compiledAt = System.currentTimeMillis();
        return new CompilationCacheEntry(lastModified, compiledAt, result);
    }

    public boolean isStale(Path sourceFile) {
        try {
            long currentLastModified = Files.getLastModifiedTime(sourceFile).toMillis();
            return lastCompiledMillis < currentLastModified || lastModifiedMillis != currentLastModified;
        } catch (IOException exception) {
            return true;
        }
    }

    public CompileResult result() {
        return result;
    }
}
