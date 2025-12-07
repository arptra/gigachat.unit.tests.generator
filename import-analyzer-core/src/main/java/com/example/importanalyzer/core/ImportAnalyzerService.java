package com.example.importanalyzer.core;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous API intended for consumers embedding the analyzer as a library.
 */
public interface ImportAnalyzerService {

    /**
     * Starts a background scan of the configured project if it is not already running.
     */
    void startScan();

    /**
     * Returns the current scan progress.
     */
    ScanResult status();

    /**
     * Requests a scan for a particular file. If a background scan is still running, a progress-only
     * result is returned. Otherwise a resolved action for the file is produced.
     */
    CompletableFuture<ScanResult> scan(Path file);
}
