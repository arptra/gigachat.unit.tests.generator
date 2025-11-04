package com.testagent.entrypoint.util;

import com.testagent.entrypoint.config.AgentConfig;
import com.testagent.entrypoint.config.GigaChatClientConfig;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class ArgsParser {

    private ArgsParser() {
    }

    public static AgentConfig parse(String[] args) {
        AgentConfig.Builder builder = AgentConfig.builder();
        String gigaChatToken = null;
        URI gigaChatEndpoint = null;

        for (int i = 0; i < (args == null ? 0 : args.length); i++) {
            String argument = args[i];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + argument);
            }
            String key = argument.substring(2);
            switch (key) {
                case "parallel" -> {
                    boolean value = true;
                    if (hasValue(args, i)) {
                        value = Boolean.parseBoolean(args[++i]);
                    }
                    builder.parallelExecution(value);
                }
                case "mode" -> builder.mode(readValue(args, ++i, key));
                case "path" -> builder.projectPath(Path.of(readValue(args, ++i, key)));
                case "include-modules" -> builder.includeModules(splitValues(readValue(args, ++i, key)));
                case "include-classes" -> builder.includeClasses(splitValues(readValue(args, ++i, key)));
                case "class" -> builder.targetClass(readValue(args, ++i, key));
                case "project" -> {
                    boolean value = true;
                    if (hasValue(args, i)) {
                        value = Boolean.parseBoolean(args[++i]);
                    }
                    builder.scanWholeProject(value);
                }
                case "gigachat-token" -> gigaChatToken = readValue(args, ++i, key);
                case "gigachat-endpoint" -> gigaChatEndpoint = toUri(readValue(args, ++i, key));
                default -> throw new IllegalArgumentException("Unknown option: --" + key);
            }
        }

        builder.gigaChat(new GigaChatClientConfig(gigaChatToken, gigaChatEndpoint));
        try {
            return builder.build();
        } catch (IllegalStateException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    private static boolean hasValue(String[] args, int index) {
        return index + 1 < args.length && !args[index + 1].startsWith("--");
    }

    private static String readValue(String[] args, int index, String key) {
        if (args == null || index >= args.length) {
            throw new IllegalArgumentException("Missing value for --" + key);
        }
        String value = args[index];
        if (value.startsWith("--")) {
            throw new IllegalArgumentException("Missing value for --" + key);
        }
        return value;
    }

    private static List<String> splitValues(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static URI toUri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new URI(value);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid URI for --gigachat-endpoint: " + value, ex);
        }
    }
}
