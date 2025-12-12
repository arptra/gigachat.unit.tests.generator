package com.gigachat.unit.tests.generator.cleaner.rules.classlevel;

import com.gigachat.unit.tests.generator.cleaner.TestFileContext;
import com.gigachat.unit.tests.generator.cleaner.rules.classlevel.api.TestClassCleanerRule;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes import statements that are obviously malformed (e.g. missing identifiers or containing
 * invalid characters). These often appear in partially generated files and prevent parsing from
 * succeeding.
 */
public final class InvalidImportCleanupRule implements TestClassCleanerRule {
    private static final Pattern VALID_IMPORT = Pattern.compile(
            "^import\\s+(static\\s+)?[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)*(\\.\\*)?\\s*;\\s*$");

    @Override
    public boolean apply(TestFileContext context) throws IOException {
        String source = context.getSource();
        String[] lines = source.split("\\r?\\n", -1);
        List<String> updated = new ArrayList<>(lines.length);
        boolean changed = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import")) {
                if (!VALID_IMPORT.matcher(trimmed).matches()) {
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
}
