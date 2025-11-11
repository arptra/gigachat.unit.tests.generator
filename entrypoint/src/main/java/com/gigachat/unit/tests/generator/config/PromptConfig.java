package com.gigachat.unit.tests.generator.config;

import java.util.Locale;
import java.util.Map;

/**
 * Normalised configuration for prompt generation behaviour.
 */
public record PromptConfig(PromptMode mode,
                           PromptVerbosity verbosity,
                           boolean includeInstructionHeader,
                           String instructionTemplate,
                           String responseFormat) {

    private static final String KEY_MODE = "prompt.mode";
    private static final String KEY_VERBOSITY = "prompt.verbosity";
    private static final String KEY_INCLUDE_HEADER = "prompt.includeInstructionHeader";
    private static final String KEY_INSTRUCTION_TEMPLATE = "prompt.instructionTemplate";
    private static final String KEY_RESPONSE_FORMAT = "prompt.responseFormat";

    private static final String DEFAULT_TEMPLATE = "You are an AI agent that generates Java unit tests.";
    private static final String DEFAULT_RESPONSE_FORMAT = "JAVA_CODE_ONLY";

    public static PromptConfig from(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return defaults();
        }
        PromptMode mode = parseMode(options.get(KEY_MODE));
        PromptVerbosity verbosity = parseVerbosity(options.get(KEY_VERBOSITY));
        boolean includeHeader = parseBoolean(options.get(KEY_INCLUDE_HEADER), true);
        String instructionTemplate = parseTemplate(options.get(KEY_INSTRUCTION_TEMPLATE));
        String responseFormat = parseResponseFormat(options.get(KEY_RESPONSE_FORMAT));
        return new PromptConfig(mode, verbosity, includeHeader, instructionTemplate, responseFormat);
    }

    public static PromptConfig defaults() {
        return new PromptConfig(PromptMode.GENERATION,
                PromptVerbosity.NORMAL,
                true,
                DEFAULT_TEMPLATE,
                DEFAULT_RESPONSE_FORMAT);
    }

    public String resolvedInstructionTemplate() {
        return instructionTemplate == null || instructionTemplate.isBlank()
                ? DEFAULT_TEMPLATE
                : instructionTemplate.strip();
    }

    public String resolvedResponseFormat() {
        return responseFormat == null || responseFormat.isBlank()
                ? DEFAULT_RESPONSE_FORMAT
                : responseFormat.trim();
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

    private static boolean parseBoolean(Object raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String normalised = raw.toString().trim().toLowerCase(Locale.ROOT);
        if (normalised.isEmpty()) {
            return defaultValue;
        }
        return normalised.equals("true") || normalised.equals("1") || normalised.equals("yes");
    }

    private static String parseTemplate(Object raw) {
        if (raw == null) {
            return DEFAULT_TEMPLATE;
        }
        String value = raw.toString();
        return value.isBlank() ? DEFAULT_TEMPLATE : value;
    }

    private static String parseResponseFormat(Object raw) {
        if (raw == null) {
            return DEFAULT_RESPONSE_FORMAT;
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? DEFAULT_RESPONSE_FORMAT : value;
    }
}
