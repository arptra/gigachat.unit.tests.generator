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
                int nextIndex = findNextNonEmpty(lines, i + 1);
                if (nextIndex < 0 || lines[nextIndex].trim().equals("}")) {
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

    private int findNextNonEmpty(String[] lines, int start) {
        for (int i = start; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) {
                return i;
            }
        }
        return -1;
    }
}
