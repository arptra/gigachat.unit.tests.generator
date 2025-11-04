package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Applies generated snippets to the target test class.
 */
public class DiffEngine {
    private final TestClassWriter writer;
    private final PipelineLogger logger;

    public DiffEngine(TestClassWriter writer, PipelineLogger logger) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public MergeResult merge(TestClassInfo classInfo, GeneratedTestSnippet snippet) {
        Objects.requireNonNull(classInfo, "classInfo");
        Objects.requireNonNull(snippet, "snippet");
        Path file = classInfo.getTargetPath();
        String originalSource = writer.readSource(file);
        String withImports = writer.ensureImports(originalSource, snippet.imports());
        String mergedSource = writer.appendMethod(withImports, snippet);
        boolean changed = !mergedSource.equals(originalSource);
        if (changed) {
            writer.writeSource(file, mergedSource);
            logger.info("Merged generated method " + snippet.methodName() + " into " + file);
        } else {
            logger.warn("No changes applied while merging snippet for " + snippet.methodName());
        }
        String diff = diff(originalSource, mergedSource);
        return new MergeResult(changed, originalSource, mergedSource, snippet.methodBody(), diff);
    }

    public String diff(String original, String updated) {
        if (Objects.equals(original, updated)) {
            return "";
        }
        String[] originalLines = original.split("\\R");
        String[] updatedLines = updated.split("\\R");
        StringBuilder builder = new StringBuilder();
        int max = Math.max(originalLines.length, updatedLines.length);
        for (int i = 0; i < max; i++) {
            String orig = i < originalLines.length ? originalLines[i] : "";
            String upd = i < updatedLines.length ? updatedLines[i] : "";
            if (!Objects.equals(orig, upd)) {
                builder.append('-').append(orig).append(System.lineSeparator());
                builder.append('+').append(upd).append(System.lineSeparator());
            }
        }
        return builder.toString();
    }

    public record MergeResult(boolean changed,
                              String originalSource,
                              String updatedSource,
                              String insertedBlock,
                              String diff) {
    }
}
