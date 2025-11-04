package com.testagent.entrypoint.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public record AgentConfig(
        Mode mode,
        Path projectPath,
        List<String> includeModules,
        List<String> includeClasses,
        boolean parallelExecution,
        GigaChatClientConfig gigaChat,
        Map<String, Object> moduleOptions
) {

    public AgentConfig {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(projectPath, "projectPath");
        includeModules = sanitise(includeModules);
        includeClasses = sanitise(includeClasses);
        projectPath = projectPath.toAbsolutePath().normalize();
        gigaChat = gigaChat == null ? new GigaChatClientConfig(null, null) : gigaChat;
        moduleOptions = moduleOptions == null ? Map.of() : Map.copyOf(moduleOptions);
    }

    private static List<String> sanitise(List<String> value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        return List.copyOf(
                value.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(ArrayList::new))
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public String toJson() {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        builder.append("  \"mode\": \"").append(mode).append("\",\n");
        builder.append("  \"projectPath\": \"").append(projectPath).append("\",\n");
        builder.append("  \"includeModules\": ").append(renderArray(includeModules)).append(",\n");
        builder.append("  \"includeClasses\": ").append(renderArray(includeClasses)).append(",\n");
        builder.append("  \"parallelExecution\": ").append(parallelExecution).append(",\n");
        builder.append("  \"gigaChat\": {");
        builder.append("\n    \"token\": ").append(renderNullable(gigaChat.tokenOptional().orElse(null))).append(",");
        builder.append("\n    \"endpoint\": ").append(renderNullable(gigaChat.endpointOptional().map(Object::toString).orElse(null))).append("\n  },\n");
        builder.append("  \"moduleOptions\": {");
        if (!moduleOptions.isEmpty()) {
            builder.append('\n');
            int index = 0;
            for (Map.Entry<String, Object> entry : moduleOptions.entrySet()) {
                builder.append("    \"").append(entry.getKey()).append("\": ")
                        .append(renderNullable(entry.getValue()))
                        .append(index + 1 < moduleOptions.size() ? ",\n" : "\n");
                index++;
            }
            builder.append("  }");
        } else {
            builder.append('}');
        }
        builder.append("\n}");
        return builder.toString();
    }

    public String toYaml() {
        StringBuilder builder = new StringBuilder();
        builder.append("mode: ").append(mode).append('\n');
        builder.append("projectPath: \"").append(projectPath).append("\"\n");
        builder.append("includeModules:").append(includeModules.isEmpty() ? " []\n" : '\n');
        includeModules.forEach(module -> builder.append("  - ").append(module).append('\n'));
        builder.append("includeClasses:").append(includeClasses.isEmpty() ? " []\n" : '\n');
        includeClasses.forEach(className -> builder.append("  - ").append(className).append('\n'));
        builder.append("parallelExecution: ").append(parallelExecution).append('\n');
        builder.append("gigaChat:\n");
        builder.append("  token: ").append(renderYamlNullable(gigaChat.tokenOptional().orElse(null))).append('\n');
        builder.append("  endpoint: ").append(renderYamlNullable(gigaChat.endpointOptional().map(Object::toString).orElse(null))).append('\n');
        builder.append("moduleOptions:");
        if (moduleOptions.isEmpty()) {
            builder.append(" {}\n");
        } else {
            builder.append('\n');
            moduleOptions.forEach((key, value) -> builder.append("  ").append(key).append(": ")
                    .append(renderYamlNullable(value))
                    .append('\n'));
        }
        return builder.toString();
    }

    private String renderArray(List<String> values) {
        if (values.isEmpty()) {
            return "[]";
        }
        return values.stream()
                .map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String renderNullable(Object value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private String renderYamlNullable(Object value) {
        return value == null ? "null" : '"' + value.toString() + '"';
    }

    public enum Mode {
        SCAN,
        TEST,
        REPAIR,
        MONITOR;

        public static Mode from(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("Mode is required");
            }
            return switch (raw.toLowerCase()) {
                case "scan" -> SCAN;
                case "test" -> TEST;
                case "repair" -> REPAIR;
                case "monitor" -> MONITOR;
                default -> throw new IllegalArgumentException("Unknown mode: " + raw);
            };
        }
    }

    public static final class Builder {
        private Mode mode;
        private Path projectPath;
        private final List<String> includeModules = new ArrayList<>();
        private final List<String> includeClasses = new ArrayList<>();
        private boolean parallelExecution;
        private GigaChatClientConfig gigaChat = new GigaChatClientConfig(null, null);
        private final Map<String, Object> moduleOptions = new LinkedHashMap<>();

        public Builder() {
        }

        private Builder(AgentConfig config) {
            this.mode = config.mode;
            this.projectPath = config.projectPath;
            this.includeModules.addAll(config.includeModules);
            this.includeClasses.addAll(config.includeClasses);
            this.parallelExecution = config.parallelExecution;
            this.gigaChat = config.gigaChat;
            this.moduleOptions.putAll(config.moduleOptions);
        }

        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        public Builder mode(String mode) {
            this.mode = Mode.from(mode);
            return this;
        }

        public Builder projectPath(Path projectPath) {
            this.projectPath = projectPath;
            return this;
        }

        public Builder includeModules(List<String> modules) {
            this.includeModules.clear();
            if (modules != null) {
                modules.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                        .forEach(this.includeModules::add);
            }
            return this;
        }

        public Builder includeClasses(List<String> classes) {
            this.includeClasses.clear();
            if (classes != null) {
                classes.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                        .forEach(this.includeClasses::add);
            }
            return this;
        }

        public Builder addModuleOption(String key, Object value) {
            if (key != null) {
                moduleOptions.put(key, value);
            }
            return this;
        }

        public Builder parallelExecution(boolean parallelExecution) {
            this.parallelExecution = parallelExecution;
            return this;
        }

        public Builder gigaChat(GigaChatClientConfig gigaChat) {
            if (gigaChat != null) {
                this.gigaChat = gigaChat;
            }
            return this;
        }

        public Builder apply(ConfigurableModule module) {
            Objects.requireNonNull(module, "module").configure(this);
            return this;
        }

        public AgentConfig build() {
            if (mode == null) {
                throw new IllegalStateException("Mode is not specified");
            }
            if (projectPath == null) {
                throw new IllegalStateException("Project path is not specified");
            }
            return new AgentConfig(
                    mode,
                    projectPath,
                    List.copyOf(includeModules),
                    List.copyOf(includeClasses),
                    parallelExecution,
                    gigaChat,
                    Map.copyOf(moduleOptions)
            );
        }
    }
}
