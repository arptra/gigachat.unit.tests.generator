package com.gigachat.unit.tests.generator.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Compiles generated test classes.
 */
public interface CompilerInvoker {

    CompileResult compile(Path projectRoot, Path testClassFile, String methodName);

    default List<CompileResult> compileParallel(Path projectRoot, List<Path> testClassFiles, String methodName) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(testClassFiles, "testClassFiles");
        if (testClassFiles.isEmpty()) {
            return List.of();
        }

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(Math.max(1, Runtime.getRuntime().availableProcessors()), testClassFiles.size()));
        try {
            List<CompletableFuture<CompileResult>> futures = testClassFiles.stream()
                    .map(file -> CompletableFuture.supplyAsync(() -> compile(projectRoot, file, methodName), executor))
                    .toList();

            return futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
        } finally {
            executor.shutdown();
        }
    }
}
