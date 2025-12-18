package com.gigachat.unit.tests.generator.compile.classification.parse;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Normalizes raw compiler output to simplify parsing.
 */
public class CompilerOutputNormalizer {

    public String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String unified = raw.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = unified.split("\n");
        return Arrays.stream(lines)
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(this::stripGradleNoise)
                .collect(Collectors.joining("\n"));
    }

    private String stripGradleNoise(String line) {
        if (line.startsWith(":") || line.startsWith("FAILURE:")) {
            return line;
        }
        if (line.startsWith(":compileJava") || line.startsWith(":compileTestJava")) {
            return line.substring(line.indexOf(":") + 1);
        }
        return line;
    }
}
