package com.gigachat.unit.tests.generator.config;

import java.util.Locale;
import java.util.Map;

/**
 * Normalised configuration for prompt generation behaviour.
 */
public record PromptConfig(PromptMode mode, PromptVerbosity verbosity) {

    private static final String KEY_MODE = "prompt.mode";
    private static final String KEY_VERBOSITY = "prompt.verbosity";

    public static PromptConfig from(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return defaults();
        }
        PromptMode mode = parseMode(options.get(KEY_MODE));
        PromptVerbosity verbosity = parseVerbosity(options.get(KEY_VERBOSITY));
        return new PromptConfig(mode, verbosity);
    }

    private static PromptMode parseMode(Object raw) {
        if (raw == null) {
            return PromptMode.GENERATION;
        }
        String normalised = raw.toString().trim().toUpperCase(Locale.ROOT);
        for (PromptMode mode : PromptMode.values()) {
            if (mode.name().equals(normalised)) {
                return mode;
            }
        }
        return PromptMode.GENERATION;
    }

    private static PromptVerbosity parseVerbosity(Object raw) {
        if (raw == null) {
            return PromptVerbosity.NORMAL;
        }
        String normalised = raw.toString().trim().toUpperCase(Locale.ROOT);
        for (PromptVerbosity verbosity : PromptVerbosity.values()) {
            if (verbosity.name().equals(normalised)) {
                return verbosity;
            }
        }
        return PromptVerbosity.NORMAL;
    }

    private static PromptConfig defaults() {
        return new PromptConfig(PromptMode.GENERATION, PromptVerbosity.NORMAL);
    }
}
