package com.gigachat.unit.tests.generator.pipeline.helpers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Simple logger that mirrors messages to stdout/stderr and stores them in a pipeline log file.
 */
public class PipelineLogger {
    private final Path logFile;
    private final Object lock = new Object();

    public PipelineLogger(Path projectRoot) {
        Path agentDir = projectRoot.resolve(".agent");
        Path logsDir = agentDir.resolve("logs");
        try {
            Files.createDirectories(logsDir);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create logs directory at " + logsDir, exception);
        }
        this.logFile = logsDir.resolve("pipeline.log");
    }

    public void info(String message) {
        log("INFO", message, false);
    }

    public void warn(String message) {
        log("WARN", message, false);
    }

    public void error(String message) {
        log("ERROR", message, true);
    }

    public void error(String message, Throwable throwable) {
        String combined = message + " -> " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        log("ERROR", combined, true);
    }

    private void log(String level, String message, boolean stderr) {
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String line = String.format("%s [%s] %s", timestamp, level, message);
        if (stderr) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }
        synchronized (lock) {
            try {
                Files.writeString(logFile,
                        line + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException exception) {
                String fallback = "Failed to write log entry: " + exception.getMessage();
                if (!stderr) {
                    System.err.println(fallback);
                } else {
                    System.err.println(fallback);
                }
            }
        }
    }
}
