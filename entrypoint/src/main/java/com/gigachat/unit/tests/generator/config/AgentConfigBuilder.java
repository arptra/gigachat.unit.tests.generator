package com.gigachat.unit.tests.generator.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentConfigBuilder {
    private AgentMode mode;
    private Path projectPath;
    private final List<String> includeModules = new ArrayList<>();
    private final List<String> includeClasses = new ArrayList<>();
    private boolean parallelExecution;
    private boolean scanWholeProject;
    private String targetClass;
    private GigaChatClientConfig gigaChat = new GigaChatClientConfig(null, null);
    private final Map<String, Object> moduleOptions = new LinkedHashMap<>();

    public AgentConfigBuilder mode(AgentMode mode) {
        this.mode = mode;
        return this;
    }

    public AgentConfigBuilder projectPath(Path projectPath) {
        this.projectPath = projectPath;
        return this;
    }

    public AgentConfigBuilder includeModules(List<String> modules) {
        this.includeModules.clear();
        if (modules != null) {
            this.includeModules.addAll(modules);
        }
        return this;
    }

    public AgentConfigBuilder includeClasses(List<String> classes) {
        this.includeClasses.clear();
        if (classes != null) {
            this.includeClasses.addAll(classes);
        }
        return this;
    }

    public AgentConfigBuilder parallelExecution(boolean parallel) {
        this.parallelExecution = parallel;
        return this;
    }

    public AgentConfigBuilder scanWholeProject(boolean scanWholeProject) {
        this.scanWholeProject = scanWholeProject;
        return this;
    }

    public AgentConfigBuilder targetClass(String targetClass) {
        this.targetClass = targetClass;
        return this;
    }

    public AgentConfigBuilder gigaChat(GigaChatClientConfig config) {
        if (config != null) {
            this.gigaChat = config;
        }
        return this;
    }

    public AgentConfigBuilder gigaChat(String token, URI endpoint) {
        this.gigaChat = new GigaChatClientConfig(token, endpoint);
        return this;
    }

    public AgentConfigBuilder moduleOption(String key, Object value) {
        if (key != null) {
            this.moduleOptions.put(key, value);
        }
        return this;
    }

    public AgentConfigBuilder moduleOptions(Map<String, Object> options) {
        this.moduleOptions.clear();
        if (options != null) {
            this.moduleOptions.putAll(options);
        }
        return this;
    }

    public AgentConfig build() {
        if (mode == null) {
            throw new IllegalStateException("Mode must be defined");
        }
        if (projectPath == null) {
            throw new IllegalStateException("Project path must be defined");
        }
        return new AgentConfig(
                mode,
                projectPath,
                includeModules,
                includeClasses,
                parallelExecution,
                scanWholeProject,
                targetClass,
                gigaChat,
                moduleOptions
        );
    }
}
