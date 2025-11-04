package com.gigachat.unit.tests.generator.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class AgentConfig {
    private final AgentMode mode;
    private final Path projectPath;
    private final List<String> includeModules;
    private final List<String> includeClasses;
    private final boolean parallelExecution;
    private final boolean scanWholeProject;
    private final List<String> targetClasses;
    private final GigaChatClientConfig gigaChat;
    private final Map<String, Object> moduleOptions;
    private final PromptConfig promptConfig;
    private final AnalysisConfig analysisConfig;

    AgentConfig(AgentMode mode,
                Path projectPath,
                List<String> includeModules,
                List<String> includeClasses,
                boolean parallelExecution,
                boolean scanWholeProject,
                List<String> targetClasses,
                GigaChatClientConfig gigaChat,
                Map<String, Object> moduleOptions) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.projectPath = Objects.requireNonNull(projectPath, "projectPath").toAbsolutePath().normalize();
        this.includeModules = sanitise(includeModules);
        this.includeClasses = sanitise(includeClasses);
        this.parallelExecution = parallelExecution;
        this.scanWholeProject = scanWholeProject;
        this.targetClasses = sanitise(targetClasses);
        this.gigaChat = gigaChat == null ? new GigaChatClientConfig(null, null) : gigaChat;
        this.moduleOptions = moduleOptions == null ? Map.of() : Map.copyOf(moduleOptions);
        this.promptConfig = PromptConfig.from(this.moduleOptions);
        this.analysisConfig = AnalysisConfig.from(this.moduleOptions);
    }

    public AgentMode getMode() {
        return mode;
    }

    public Path getProjectPath() {
        return projectPath;
    }

    public List<String> getIncludeModules() {
        return includeModules;
    }

    public List<String> getIncludeClasses() {
        return includeClasses;
    }

    public boolean isParallelExecution() {
        return parallelExecution;
    }

    public boolean isScanWholeProject() {
        return scanWholeProject;
    }

    public List<String> getTargetClasses() {
        return targetClasses;
    }

    public GigaChatClientConfig getGigaChat() {
        return gigaChat;
    }

    public Map<String, Object> getModuleOptions() {
        return moduleOptions;
    }

    public PipelineModuleConfig getPipelineModuleConfig() {
        return PipelineModuleConfig.from(moduleOptions);
    }

    public PromptConfig getPromptConfig() {
        return promptConfig;
    }

    public AnalysisConfig getAnalysisConfig() {
        return analysisConfig;
    }

    public AgentConfigBuilder toBuilder() {
        AgentConfigBuilder builder = new AgentConfigBuilder();
        builder.mode(mode);
        builder.projectPath(projectPath);
        builder.includeModules(new ArrayList<>(includeModules));
        builder.includeClasses(new ArrayList<>(includeClasses));
        builder.parallelExecution(parallelExecution);
        builder.scanWholeProject(scanWholeProject);
        builder.targetClasses(new ArrayList<>(targetClasses));
        builder.gigaChat(gigaChat);
        builder.moduleOptions(new LinkedHashMap<>(moduleOptions));
        return builder;
    }

    public String toJson() {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        builder.append("  \"mode\": \"").append(mode).append("\",\n");
        builder.append("  \"projectPath\": \"").append(projectPath).append("\",\n");
        builder.append("  \"includeModules\": ").append(renderArray(includeModules)).append(",\n");
        builder.append("  \"includeClasses\": ").append(renderArray(includeClasses)).append(",\n");
        builder.append("  \"parallelExecution\": ").append(parallelExecution).append(",\n");
        builder.append("  \"scanWholeProject\": ").append(scanWholeProject).append(",\n");
        builder.append("  \"targetClasses\": ").append(renderArray(targetClasses)).append(",\n");
        builder.append("  \"gigaChat\": {");
        builder.append("\n    \"token\": ").append(renderNullable(gigaChat.tokenOptional().orElse(null))).append(",");
        builder.append("\n    \"endpoint\": ").append(renderNullable(gigaChat.endpointOptional().map(Object::toString).orElse(null))).append("\n  },\n");
        builder.append("  \"moduleOptions\": {");
        if (moduleOptions.isEmpty()) {
            builder.append("}\n");
        } else {
            builder.append('\n');
            int index = 0;
            for (Map.Entry<String, Object> entry : moduleOptions.entrySet()) {
                builder.append("    \"").append(entry.getKey()).append("\": ")
                        .append(renderNullable(entry.getValue()))
                        .append(index + 1 < moduleOptions.size() ? ",\n" : "\n");
                index++;
            }
            builder.append("  }\n");
        }
        builder.append(",\n  \"prompt\": {")
                .append("\n    \"mode\": \"").append(promptConfig.mode()).append("\",")
                .append("\n    \"verbosity\": \"").append(promptConfig.verbosity()).append("\"\n  },\n")
                .append("  \"analysis\": {")
                .append("\n    \"includeStatic\": ").append(analysisConfig.includeStatic()).append(',')
                .append("\n    \"includeVerificationPolicy\": ").append(analysisConfig.includeVerificationPolicy()).append(',')
                .append("\n    \"maxChainDepth\": ").append(analysisConfig.maxChainDepth()).append(',')
                .append("\n    \"excludePackages\": ").append(renderArray(analysisConfig.excludePackages()))
                .append("\n  }\n");
        builder.append('}');
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
        builder.append("scanWholeProject: ").append(scanWholeProject).append('\n');
        builder.append("targetClasses:").append(targetClasses.isEmpty() ? " []\n" : '\n');
        targetClasses.forEach(className -> builder.append("  - ").append(className).append('\n'));
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
        builder.append("prompt:\n");
        builder.append("  mode: ").append(promptConfig.mode()).append('\n');
        builder.append("  verbosity: ").append(promptConfig.verbosity()).append('\n');
        builder.append("analysis:\n");
        builder.append("  includeStatic: ").append(analysisConfig.includeStatic()).append('\n');
        builder.append("  includeVerificationPolicy: ").append(analysisConfig.includeVerificationPolicy()).append('\n');
        builder.append("  maxChainDepth: ").append(analysisConfig.maxChainDepth()).append('\n');
        builder.append("  excludePackages:");
        if (analysisConfig.excludePackages().isEmpty()) {
            builder.append(" []\n");
        } else {
            builder.append('\n');
            analysisConfig.excludePackages().forEach(packageName -> builder.append("    - ")
                    .append(packageName)
                    .append('\n'));
        }
        return builder.toString();
    }

    private List<String> sanitise(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.toUnmodifiableList());
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
}
