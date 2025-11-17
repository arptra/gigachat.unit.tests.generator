package com.gigachat.unit.tests.generator.cleaner.rules;

import com.gigachat.unit.tests.generator.cleaner.CleanerRule;
import com.gigachat.unit.tests.generator.cleaner.TestFileContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Removes stray {@code @Test} annotations that are not followed by a declaration. These
 * often appear after interrupted merges or partially generated files.
 */
public final class DanglingTestAnnotationRule implements CleanerRule {
    @Override
    public boolean apply(TestFileContext context) throws IOException {
        String source = context.getSource();
        String[] lines = source.split("\r?\n", -1);
        List<String> updated = new ArrayList<>(lines.length);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("@Test")) {
                int nextIndex = findNextSignificant(lines, i + 1);
                if (nextIndex < 0 || shouldDropTestAnnotation(lines[nextIndex])) {
                    changed = true;
                    continue;
                }
            }
            updated.add(line);
        }
        if (changed) {
            context.updateSource(String.join(System.lineSeparator(), updated));
        }
        return changed;
    }

    private int findNextSignificant(String[] lines, int start) {
        for (int i = start; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty() || isComment(trimmed)) {
                continue;
            }
            if (trimmed.startsWith("@")) {
                return i;
            }
            if (looksLikeMethod(trimmed) || trimmed.equals("}")) {
                return i;
            }
        }
        return -1;
    }

    private boolean shouldDropTestAnnotation(String nextLine) {
        String trimmed = nextLine.trim();
        if (trimmed.equals("}")) {
            return true;
        }
        if (trimmed.startsWith("@Test")) {
            return true;
        }
        return !looksLikeMethod(trimmed);
    }

    private boolean isComment(String line) {
        return line.startsWith("//") || line.startsWith("/*") || line.startsWith("*") || line.startsWith("*/");
    }

    private boolean looksLikeMethod(String trimmed) {
        if (trimmed.startsWith("class ") || trimmed.startsWith("interface ") || trimmed.startsWith("enum ")) {
            return false;
        }
        return trimmed.contains("(") && !trimmed.startsWith("//");
    }
}
