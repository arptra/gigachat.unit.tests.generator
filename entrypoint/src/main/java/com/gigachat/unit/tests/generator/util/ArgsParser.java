package com.gigachat.unit.tests.generator.util;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.config.AgentMode;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ArgsParser {
    public AgentConfig parse(String[] args) {
        AgentConfigBuilder builder = new AgentConfigBuilder();
        String gigaChatToken = null;
        URI gigaChatEndpoint = null;
        URI gigaAuthUrl = null;
        Path certificatePath = null;
        Path rootCertificatePath = null;
        Path privateKeyPath = null;
        boolean verifySslCerts = false;
        String modelName = null;

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
                case "mode" -> builder.mode(parseMode(readValue(args, ++i, key)));
                case "path" -> builder.projectPath(Path.of(readValue(args, ++i, key)));
                case "include-modules" -> builder.includeModules(splitValues(readValue(args, ++i, key)));
                case "include-classes" -> builder.includeClasses(splitValues(readValue(args, ++i, key)));
                case "class" -> builder.targetClasses(splitValues(readValue(args, ++i, key)));
                case "single-file" -> builder.singleFileMode(true);
                case "clean" -> builder.mode(AgentMode.CLEAN);
                case "project" -> {
                    boolean value = true;
                    if (hasValue(args, i)) {
                        value = Boolean.parseBoolean(args[++i]);
                    }
                    builder.scanWholeProject(value);
                }
                case "gigachat-token", "token" -> gigaChatToken = readValue(args, ++i, key);
                case "gigachat-endpoint", "endpoint" -> gigaChatEndpoint = toUri(readValue(args, ++i, key));
                case "auth-url" -> gigaAuthUrl = toUri(readValue(args, ++i, key));
                case "cert" -> certificatePath = toPath(readValue(args, ++i, key));
                case "rootCert" -> rootCertificatePath = toPath(readValue(args, ++i, key));
                case "key" -> privateKeyPath = toPath(readValue(args, ++i, key));
                case "compile" -> builder.moduleOption("pipeline.compile.enabled", true);
                case "execute" -> builder.moduleOption("pipeline.execute.enabled", true);
                case "ssl" -> {
                    boolean value = true;
                    if (hasValue(args, i)) {
                        value = Boolean.parseBoolean(args[++i]);
                    }
                    verifySslCerts = value;
                }
                case "model" -> modelName = readValue(args, ++i, key);
                default -> throw new IllegalArgumentException("Unknown option: --" + key);
            }
        }

        validateGigachatOptions(gigaChatToken, certificatePath, rootCertificatePath, privateKeyPath);
        builder.gigaChat(gigaChatToken,
                gigaChatEndpoint,
                gigaAuthUrl,
                certificatePath,
                rootCertificatePath,
                privateKeyPath,
                verifySslCerts,
                modelName);
        try {
            return builder.build();
        } catch (IllegalStateException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    private void validateGigachatOptions(String token,
                                         Path certificate,
                                         Path rootCertificate,
                                         Path privateKey) {
        boolean hasToken = token != null && !token.isBlank();
        boolean hasAnyCertificate = certificate != null || rootCertificate != null || privateKey != null;
        if (hasToken && hasAnyCertificate) {
            throw new IllegalArgumentException("Specify either --token or the certificate options, not both.");
        }
        if (!hasToken && hasAnyCertificate) {
            if (certificate == null || rootCertificate == null || privateKey == null) {
                throw new IllegalArgumentException("Certificate authentication requires --cert, --rootCert and --key options.");
            }
        }
    }

    private AgentMode parseMode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Mode is required");
        }
        return switch (value.toLowerCase()) {
            case "scan" -> AgentMode.SCAN;
            case "test" -> AgentMode.TEST;
            case "repair" -> AgentMode.REPAIR;
            case "monitor" -> AgentMode.MONITOR;
            case "clean" -> AgentMode.CLEAN;
            default -> throw new IllegalArgumentException("Unknown mode: " + value);
        };
    }

    private boolean hasValue(String[] args, int index) {
        return index + 1 < args.length && !args[index + 1].startsWith("--");
    }

    private String readValue(String[] args, int index, String key) {
        if (args == null || index >= args.length) {
            throw new IllegalArgumentException("Missing value for --" + key);
        }
        String value = args[index];
        if (value.startsWith("--")) {
            throw new IllegalArgumentException("Missing value for --" + key);
        }
        return value;
    }

    private List<String> splitValues(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private URI toUri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new URI(value);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid URI for --gigachat-endpoint: " + value, ex);
        }
    }

    private Path toPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Paths.get(value).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid path: " + value, ex);
        }
    }
}
