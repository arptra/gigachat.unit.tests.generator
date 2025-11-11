package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.dto.FailedMethodSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Persists failed method snapshots to disk so that repair steps can analyse them later.
 */
public class SnapshotStorage {
    private final PipelineLogger logger;
    private final Path snapshotsDir;

    public SnapshotStorage(Path projectRoot, PipelineLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        Path agentDir = projectRoot.resolve(".agent");
        this.snapshotsDir = agentDir.resolve("snapshots");
        try {
            Files.createDirectories(snapshotsDir);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create snapshots directory at " + snapshotsDir, exception);
        }
    }

    public void save(FailedMethodSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(snapshot.createdAt());
        String safeClass = snapshot.testClassFile().getFileName().toString().replace(".java", "");
        String safeMethod = snapshot.methodName().replaceAll("[^A-Za-z0-9]", "_");
        String fileName = timestamp.replace(':', '-') + "_" + safeClass + "_" + safeMethod + ".json";
        Path file = snapshotsDir.resolve(fileName);
        String content = "{\n"
                + "  \"class\": \"" + snapshot.testClassFile() + "\",\n"
                + "  \"method\": \"" + snapshot.methodName() + "\",\n"
                + "  \"reason\": \"" + escape(snapshot.reason()) + "\",\n"
                + "  \"imports\": " + snapshot.imports() + ",\n"
                + "  \"body\": " + quote(snapshot.methodBody()) + "\n"
                + "}";
        try {
            Files.writeString(file,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("Stored snapshot for method " + snapshot.methodName() + " at " + file);
        } catch (IOException exception) {
            logger.error("Failed to store snapshot for method " + snapshot.methodName(), exception);
        }
    }

    private String quote(String value) {
        return "\"" + escape(value) + "\"";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
