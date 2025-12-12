package com.gigachat.unit.tests.generator.reasoning.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides minimal source editing helpers used by the reasoning tool executor.
 */
public class SourceFileEditor {

    private static final Pattern HUNK_HEADER = Pattern.compile("@@ -(?<start1>\\d+)(,(?<count1>\\d+))? +\\+(?<start2>\\d+)(,(?<count2>\\d+))? @@");

    public SourceFileEditor() {
    }

    public String applyPatch(Path file, String patchContent) {
        if (file == null || patchContent == null || patchContent.isBlank()) {
            return "";
        }
        try {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            String updated = applyUnifiedPatch(original, patchContent);
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            return updated;
        } catch (IOException | IllegalArgumentException exception) {
            return "";
        }
    }

    public String addImport(Path file, String importFqcn) {
        Objects.requireNonNull(file, "file");
        if (importFqcn == null || importFqcn.isBlank()) {
            return "";
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.stream().anyMatch(line -> line.contains("import " + importFqcn))) {
                return String.join("\n", lines);
            }
            int insertIndex = 0;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("package ")) {
                    insertIndex = i + 1;
                }
                if (trimmed.startsWith("import ")) {
                    insertIndex = i + 1;
                }
            }
            lines.add(insertIndex, "import " + importFqcn + ";");
            Files.write(file, lines, StandardCharsets.UTF_8);
            return String.join("\n", lines);
        } catch (IOException exception) {
            return "";
        }
    }

    public String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "";
        }
    }

    public List<String> readImports(Path file) {
        try {
            List<String> imports = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import ")) {
                    imports.add(trimmed.replace("import ", "").replace(";", "").trim());
                }
            }
            return imports;
        } catch (IOException exception) {
            return List.of();
        }
    }

    private String applyUnifiedPatch(String original, String patchText) {
        List<String> lines = new ArrayList<>(Arrays.asList(original.split("\\n", -1)));
        String[] patchLines = patchText.split("\\r?\\n");
        int offset = 0;
        int index = 0;
        while (index < patchLines.length) {
            String line = patchLines[index];
            if (!line.startsWith("@@")) {
                index++;
                continue;
            }
            Matcher matcher = HUNK_HEADER.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Invalid hunk header: " + line);
            }
            int start1 = Integer.parseInt(matcher.group("start1"));
            int count1 = matcher.group("count1") == null ? 1 : Integer.parseInt(matcher.group("count1"));
            int start2 = Integer.parseInt(matcher.group("start2"));
            int targetIndex = start1 - 1 + offset;
            List<String> replacement = new ArrayList<>();
            index++;
            while (index < patchLines.length && !patchLines[index].startsWith("@@")) {
                String contentLine = patchLines[index];
                if (contentLine.startsWith("---") || contentLine.startsWith("+++")) {
                    index++;
                    continue;
                }
                if (!contentLine.isEmpty()) {
                    char prefix = contentLine.charAt(0);
                    String payload = contentLine.length() > 1 ? contentLine.substring(1) : "";
                    switch (prefix) {
                        case ' ' -> {
                            replacement.add(payload);
                            targetIndex++;
                        }
                        case '-' -> targetIndex++;
                        case '+' -> replacement.add(payload);
                        default -> replacement.add(contentLine);
                    }
                }
                index++;
            }
            int removeFrom = start1 - 1 + offset;
            if (removeFrom < 0 || removeFrom > lines.size()) {
                throw new IllegalArgumentException("Patch offset outside of file bounds");
            }
            int removeTo = Math.min(lines.size(), removeFrom + count1);
            for (int i = removeFrom; i < removeTo; i++) {
                lines.remove(removeFrom);
            }
            lines.addAll(removeFrom, replacement);
            offset += replacement.size() - count1;
        }
        return String.join("\n", lines);
    }
}

