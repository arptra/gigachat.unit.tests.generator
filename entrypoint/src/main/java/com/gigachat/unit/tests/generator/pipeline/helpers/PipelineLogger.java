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
    private final Path traceFile;
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
        this.traceFile = logsDir.resolve("state-trace.log");
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

    public void trace(String category, String message) {
        trace(category, null, null, message);
    }

    public void trace(String category, String methodName, String state, String message) {
        StringBuilder body = new StringBuilder();
        if (category != null && !category.isBlank()) {
            body.append('[').append(sanitize(category)).append(']');
        } else {
            body.append("[TRACE]");
        }
        if (methodName != null && !methodName.isBlank()) {
            body.append(" method=").append(sanitize(methodName));
        }
        if (state != null && !state.isBlank()) {
            body.append(" state=").append(sanitize(state));
        }
        if (message != null && !message.isBlank()) {
            body.append(" message=").append(sanitize(message));
        }
        logTrace(body.toString());
    }

    private void log(String level, String message, boolean stderr) {
        String line = formatLine(level, message);
        if (stderr) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }
        synchronized (lock) {
            try {
                writeLine(logFile, line);
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

    private void logTrace(String message) {
        String line = formatLine("TRACE", message);
        System.out.println(line);
        synchronized (lock) {
            try {
                writeLine(logFile, line);
                writeLine(traceFile, line);
            } catch (IOException exception) {
                System.err.println("Failed to write trace entry: " + exception.getMessage());
            }
        }
    }

    private String formatLine(String level, String message) {
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        return String.format("%s [%s] %s", timestamp, level, message);
    }

    private void writeLine(Path target, String line) throws IOException {
        Files.writeString(target,
                line + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private String sanitize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
