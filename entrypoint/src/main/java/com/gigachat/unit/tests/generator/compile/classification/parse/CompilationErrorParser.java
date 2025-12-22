package com.gigachat.unit.tests.generator.compile.classification.parse;

import com.gigachat.unit.tests.generator.compile.classification.model.CompilationError;
import com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorClass;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses normalized compiler output into {@link CompilationError} instances.
 */
public class CompilationErrorParser {

    private static final Pattern FILE_LINE_PATTERN = Pattern.compile("^(.*?):(\\d+)(?::(\\d+))?:\\s*(?:error:)?\\s*(.*)$");
    private static final Pattern GENERIC_ERROR_PATTERN = Pattern.compile("^error:\\s*(.*)$");

    public List<CompilationError> parse(String normalizedOutput) {
        List<CompilationError> result = new ArrayList<>();
        if (normalizedOutput == null || normalizedOutput.isBlank()) {
            return result;
        }
        String[] lines = normalizedOutput.split("\n");
        int index = 0;
        while (index < lines.length) {
            String line = lines[index];
            Matcher fileMatcher = FILE_LINE_PATTERN.matcher(line);
            Matcher genericMatcher = GENERIC_ERROR_PATTERN.matcher(line);
            if (fileMatcher.matches()) {
                ParsedMessage parsed = collectMessage(lines, index + 1);
                CompilationError error = CompilationError.builder()
                        .filePath(fileMatcher.group(1))
                        .line(parseInt(fileMatcher.group(2)))
                        .column(parseInt(fileMatcher.group(3)))
                        .rawMessage(mergeMessage(fileMatcher.group(4), parsed.message()))
                        .normalizedMessage(mergeMessage(fileMatcher.group(4), parsed.message()))
                        .errorClass(CompilationErrorClass.OTHER)
                        .build();
                result.add(error);
                index = parsed.nextIndex();
                continue;
            }
            if (genericMatcher.matches()) {
                ParsedMessage parsed = collectMessage(lines, index + 1);
                CompilationError error = CompilationError.builder()
                        .rawMessage(mergeMessage(genericMatcher.group(1), parsed.message()))
                        .normalizedMessage(mergeMessage(genericMatcher.group(1), parsed.message()))
                        .errorClass(CompilationErrorClass.OTHER)
                        .build();
                result.add(error);
                index = parsed.nextIndex();
                continue;
            }
            index++;
        }
        return result;
    }

    private ParsedMessage collectMessage(String[] lines, int startIndex) {
        StringBuilder builder = new StringBuilder();
        int current = startIndex;
        while (current < lines.length) {
            String candidate = lines[current];
            if (FILE_LINE_PATTERN.matcher(candidate).matches() || GENERIC_ERROR_PATTERN.matcher(candidate).matches()) {
                break;
            }
            if (!candidate.isBlank()) {
                if (builder.length() > 0) {
                    builder.append("\n");
                }
                builder.append(candidate);
            }
            current++;
        }
        return new ParsedMessage(builder.toString(), current);
    }

    private Integer parseInt(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String mergeMessage(String primary, String remainder) {
        if (remainder == null || remainder.isBlank()) {
            return primary == null ? "" : primary.trim();
        }
        if (primary == null || primary.isBlank()) {
            return remainder.trim();
        }
        return primary.trim() + "\n" + remainder.trim();
    }

    private record ParsedMessage(String message, int nextIndex) {
    }
}
