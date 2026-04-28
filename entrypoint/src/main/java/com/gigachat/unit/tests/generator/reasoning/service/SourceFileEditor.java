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

    private static final Pattern HUNK_HEADER = Pattern.compile("@@ -(?<start1>\\d+)(,(?<count1>\\d+))? +\\+(?<start2>\\d+)(,(?<count2>\\d+))? @@(?: .*)?");

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
            boolean staticImport = isStaticImport(importFqcn);
            String normalizedImport = normalizeImport(importFqcn);
            if (normalizedImport.isBlank()) {
                return "";
            }
            String importStatement = staticImport
                    ? "import static " + normalizedImport + ";"
                    : "import " + normalizedImport + ";";
            if (lines.stream().map(String::trim).anyMatch(importStatement::equals)) {
                return String.join("\n", lines);
            }
            if (staticImport) {
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
                lines.add(insertIndex, importStatement);
                Files.write(file, lines, StandardCharsets.UTF_8);
                return String.join("\n", lines);
            }
            String targetSimpleName = simpleName(normalizedImport);
            String targetPackage = packageName(normalizedImport);
            String filePackage = packageNameFromLines(lines);
            boolean samePackageImport = !filePackage.isBlank() && filePackage.equals(targetPackage);
            boolean changed = false;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (!trimmed.startsWith("import ") || trimmed.startsWith("import static ")) {
                    continue;
                }
                String existingImport = extractImportName(trimmed);
                if (existingImport == null) {
                    continue;
                }
                if (!simpleName(existingImport).equals(targetSimpleName)) {
                    continue;
                }
                if (samePackageImport) {
                    lines.remove(i);
                    i--;
                    changed = true;
                    continue;
                }
                if (!existingImport.equals(normalizedImport)) {
                    lines.set(i, "import " + normalizedImport + ";");
                    changed = true;
                }
                if (changed) {
                    Files.write(file, lines, StandardCharsets.UTF_8);
                }
                return String.join("\n", lines);
            }
            if (samePackageImport) {
                if (changed) {
                    Files.write(file, lines, StandardCharsets.UTF_8);
                }
                return String.join("\n", lines);
            }
            int insertIndex = 0;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("package ")) {
                    insertIndex = i + 1;
                }
                if (trimmed.startsWith("import ") && !trimmed.startsWith("import static ")) {
                    insertIndex = i + 1;
                }
            }
            lines.add(insertIndex, "import " + normalizedImport + ";");
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

    public String writeFile(Path file, String content) {
        if (file == null || content == null) {
            return "";
        }
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return content;
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
                    String formatted = trimmed.endsWith(";") ? trimmed : trimmed + ";";
                    imports.add(formatted);
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
            List<String> beforeBlock = new ArrayList<>();
            List<String> replacement = new ArrayList<>();
            List<String> removedLines = new ArrayList<>();
            List<String> addedLines = new ArrayList<>();
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
                            beforeBlock.add(payload);
                            replacement.add(payload);
                        }
                        case '-' -> {
                            beforeBlock.add(payload);
                            removedLines.add(payload);
                        }
                        case '+' -> {
                            replacement.add(payload);
                            addedLines.add(payload);
                        }
                        default -> replacement.add(contentLine);
                    }
                }
                index++;
            }
            int suggestedIndex = start1 - 1 + offset;
            int removeFrom = resolveHunkStart(lines, beforeBlock, suggestedIndex);
            int removeLength = beforeBlock.isEmpty() ? 0 : beforeBlock.size();
            List<String> replacementLines = replacement;
            if (removeFrom < 0 && !removedLines.isEmpty()) {
                removeFrom = resolveRemovedLinesStart(lines, removedLines);
                removeLength = removedLines.size();
                replacementLines = addedLines;
            }
            if (removeFrom < 0 && !beforeBlock.isEmpty()) {
                removeFrom = resolveApproximateHunkStart(lines, beforeBlock, suggestedIndex);
                removeLength = beforeBlock.size();
                replacementLines = replacement;
            }
            if (removeFrom < 0 || removeFrom > lines.size()) {
                throw new IllegalArgumentException("Patch offset outside of file bounds");
            }
            int removeTo = Math.min(lines.size(), removeFrom + removeLength);
            for (int i = removeFrom; i < removeTo; i++) {
                lines.remove(removeFrom);
            }
            lines.addAll(removeFrom, replacementLines);
            offset += replacementLines.size() - removeLength;
        }
        return String.join("\n", lines);
    }

    private int resolveHunkStart(List<String> lines, List<String> beforeBlock, int suggestedIndex) {
        if (beforeBlock == null || beforeBlock.isEmpty()) {
            return Math.max(0, Math.min(suggestedIndex, lines.size()));
        }
        if (matchesAt(lines, suggestedIndex, beforeBlock)) {
            return suggestedIndex;
        }
        if (matchesAtNormalised(lines, suggestedIndex, beforeBlock)) {
            return suggestedIndex;
        }
        for (int index = 0; index <= lines.size() - beforeBlock.size(); index++) {
            if (matchesAt(lines, index, beforeBlock)) {
                return index;
            }
            if (matchesAtNormalised(lines, index, beforeBlock)) {
                return index;
            }
        }
        return -1;
    }

    private int resolveRemovedLinesStart(List<String> lines, List<String> removedLines) {
        if (lines == null || removedLines == null || removedLines.isEmpty()) {
            return -1;
        }
        for (int index = 0; index <= lines.size() - removedLines.size(); index++) {
            boolean matches = true;
            for (int offset = 0; offset < removedLines.size(); offset++) {
                if (!Objects.equals(stripLeadingWhitespace(lines.get(index + offset)),
                        stripLeadingWhitespace(removedLines.get(offset)))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return index;
            }
        }
        return -1;
    }

    private int resolveApproximateHunkStart(List<String> lines, List<String> beforeBlock, int suggestedIndex) {
        if (lines == null || beforeBlock == null || beforeBlock.isEmpty() || beforeBlock.size() > lines.size()) {
            return -1;
        }
        int bestIndex = -1;
        int bestScore = -1;
        for (int index = 0; index <= lines.size() - beforeBlock.size(); index++) {
            int score = matchScoreNormalised(lines, index, beforeBlock);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            } else if (score == bestScore && bestIndex >= 0) {
                int bestDistance = Math.abs(bestIndex - suggestedIndex);
                int currentDistance = Math.abs(index - suggestedIndex);
                if (currentDistance < bestDistance) {
                    bestIndex = index;
                }
            }
        }
        int minimumScore = Math.max(2, beforeBlock.size() - 2);
        return bestScore >= minimumScore ? bestIndex : -1;
    }

    private boolean matchesAt(List<String> lines, int start, List<String> block) {
        if (lines == null || block == null || start < 0 || start + block.size() > lines.size()) {
            return false;
        }
        for (int index = 0; index < block.size(); index++) {
            if (!Objects.equals(lines.get(start + index), block.get(index))) {
                return false;
            }
        }
        return true;
    }

    private int matchScoreNormalised(List<String> lines, int start, List<String> block) {
        if (lines == null || block == null || start < 0 || start + block.size() > lines.size()) {
            return -1;
        }
        int score = 0;
        for (int index = 0; index < block.size(); index++) {
            String left = stripLeadingWhitespace(lines.get(start + index));
            String right = stripLeadingWhitespace(block.get(index));
            if (Objects.equals(left, right)) {
                score++;
            }
        }
        return score;
    }

    private boolean matchesAtNormalised(List<String> lines, int start, List<String> block) {
        if (lines == null || block == null || start < 0 || start + block.size() > lines.size()) {
            return false;
        }
        for (int index = 0; index < block.size(); index++) {
            String left = stripLeadingWhitespace(lines.get(start + index));
            String right = stripLeadingWhitespace(block.get(index));
            if (!Objects.equals(left, right)) {
                return false;
            }
        }
        return true;
    }

    private String stripLeadingWhitespace(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return value.substring(index);
    }

    private String normalizeImport(String importFqcn) {
        String trimmed = importFqcn == null ? "" : importFqcn.trim();
        if (trimmed.startsWith("import static ")) {
            trimmed = trimmed.substring("import static ".length()).trim();
        } else if (trimmed.startsWith("import ")) {
            trimmed = trimmed.substring("import ".length()).trim();
        } else if (trimmed.startsWith("static ")) {
            trimmed = trimmed.substring("static ".length()).trim();
        }
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private boolean isStaticImport(String importFqcn) {
        if (importFqcn == null) {
            return false;
        }
        String trimmed = importFqcn.trim();
        return trimmed.startsWith("static ") || trimmed.startsWith("import static ");
    }

    private String simpleName(String importFqcn) {
        int separator = importFqcn.lastIndexOf('.');
        return separator >= 0 ? importFqcn.substring(separator + 1) : importFqcn;
    }

    private String packageName(String importFqcn) {
        int separator = importFqcn.lastIndexOf('.');
        return separator >= 0 ? importFqcn.substring(0, separator) : "";
    }

    private String packageNameFromLines(List<String> lines) {
        if (lines == null) {
            return "";
        }
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                return trimmed.substring("package ".length(), trimmed.length() - 1).trim();
            }
        }
        return "";
    }

    private String extractImportName(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith("import ") || trimmed.startsWith("import static ")) {
            return null;
        }
        String body = trimmed.substring("import ".length()).trim();
        if (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1).trim();
        }
        return body.isBlank() ? null : body;
    }
}
