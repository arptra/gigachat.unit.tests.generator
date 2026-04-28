package com.gigachat.unit.tests.generator.pipeline.helpers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Normalizes model-produced import declarations before they reach JavaParser or generated files.
 */
public final class JavaImportSanitizer {
    private static final Pattern IMPORT_TARGET = Pattern.compile(
            "(static\\s+)?[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+(\\.\\*)?"
    );

    private JavaImportSanitizer() {
    }

    public static List<String> sanitizeImports(List<String> rawImports) {
        if (rawImports == null || rawImports.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String rawImport : rawImports) {
            String normalized = normalizeImportLine(rawImport);
            if (!normalized.isBlank()) {
                cleaned.add(normalized);
            }
        }
        return new ArrayList<>(cleaned);
    }

    public static String normalizeImportLine(String rawImport) {
        if (rawImport == null) {
            return "";
        }
        String trimmed = rawImport.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int semicolonIndex = trimmed.indexOf(';');
        if (semicolonIndex >= 0) {
            trimmed = trimmed.substring(0, semicolonIndex + 1);
        } else {
            int lineCommentIndex = trimmed.indexOf("//");
            if (lineCommentIndex >= 0) {
                trimmed = trimmed.substring(0, lineCommentIndex).trim();
            }
            int blockCommentIndex = trimmed.indexOf("/*");
            if (blockCommentIndex >= 0) {
                trimmed = trimmed.substring(0, blockCommentIndex).trim();
            }
        }
        trimmed = trimmed.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("import ")) {
            trimmed = trimmed.substring("import ".length()).trim();
        }
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
            return "";
        }
        if (!IMPORT_TARGET.matcher(trimmed).matches()) {
            return "";
        }
        return "import " + trimmed + ';';
    }

    public static String normalizeImportTarget(String rawImport) {
        String normalized = normalizeImportLine(rawImport);
        if (normalized.isBlank()) {
            return "";
        }
        String target = normalized.substring("import ".length(), normalized.length() - 1).trim();
        return target;
    }

    public static String sanitizeSourceImports(String source) {
        if (source == null || source.isBlank()) {
            return source;
        }
        String unix = source.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = unix.split("\n", -1);
        List<String> sanitizedLines = new ArrayList<>(lines.length);
        boolean changed = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) {
                sanitizedLines.add(line);
                continue;
            }
            String normalized = normalizeImportLine(trimmed);
            if (normalized.isBlank()) {
                changed = true;
                continue;
            }
            String indent = line.substring(0, line.length() - line.stripLeading().length());
            sanitizedLines.add(indent + normalized);
            changed |= !trimmed.equals(normalized);
        }
        if (!changed) {
            return source;
        }
        String sanitized = String.join("\n", sanitizedLines);
        if ("\n".equals(System.lineSeparator())) {
            return sanitized;
        }
        return sanitized.replace("\n", System.lineSeparator());
    }
}
