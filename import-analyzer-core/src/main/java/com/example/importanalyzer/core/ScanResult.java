package com.example.importanalyzer.core;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of an asynchronous scan request for a single file.
 */
public record ScanResult(
        Path file,
        ImportAction action,
        int line,
        List<String> candidates,
        ImportSource source,
        boolean inProgress,
        int scannedFiles,
        int totalFiles
) {
}
