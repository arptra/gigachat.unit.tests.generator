package com.gigachat.unit.tests.generator.reasoning.model;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Generates a stable trace identifier for one reasoning fix session.
 */
public final class FixSessionId {

    private FixSessionId() {
    }

    public static String create(Path testFile, String methodName) {
        String fileToken = testFile == null
                ? "unknown-file"
                : sanitize(testFile.getFileName() == null ? testFile.toString() : testFile.getFileName().toString());
        String methodToken = methodName == null || methodName.isBlank()
                ? "unknown-method"
                : sanitize(methodName);
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT)
                .format(LocalDateTime.now(ZoneOffset.UTC));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return fileToken + "-" + methodToken + "-" + timestamp + "-" + suffix;
    }

    private static String sanitize(String value) {
        return value
                .replaceAll("[^a-zA-Z0-9._-]", "_")
                .replace('.', '_');
    }
}
