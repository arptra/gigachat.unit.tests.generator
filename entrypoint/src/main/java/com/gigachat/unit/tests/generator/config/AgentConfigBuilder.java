package com.gigachat.unit.tests.generator.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentConfigBuilder {
    private AgentMode mode = AgentMode.SCAN;
    private Path projectPath = Path.of("").toAbsolutePath().normalize();
    private final List<String> includeModules = new ArrayList<>();
    private final List<String> includeClasses = new ArrayList<>();
    private boolean parallelExecution;
    private boolean scanWholeProject;
    private final List<String> targetClasses = new ArrayList<>();
    private GigaChatClientConfig gigaChat = GigaChatClientConfig.empty();
    private final Map<String, Object> moduleOptions = new LinkedHashMap<>();
    private boolean verifySslCerts;
    private String modelName;

    public AgentConfigBuilder mode(AgentMode mode) {
        if (mode != null) {
            this.mode = mode;
        }
        return this;
    }

    public AgentConfigBuilder projectPath(Path projectPath) {
        if (projectPath != null) {
            this.projectPath = projectPath.toAbsolutePath().normalize();
        }
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
        this.targetClasses.clear();
        if (targetClass != null) {
            this.targetClasses.add(targetClass);
        }
        return this;
    }

    public AgentConfigBuilder targetClasses(List<String> targetClasses) {
        this.targetClasses.clear();
        if (targetClasses != null) {
            this.targetClasses.addAll(targetClasses);
        }
        return this;
    }

    public AgentConfigBuilder gigaChat(GigaChatClientConfig config) {
        if (config != null) {
            this.gigaChat = config;
            this.verifySslCerts = config.verifySslCerts();
            this.modelName = config.modelNameOptional().orElse(null);
        }
        return this;
    }

    public AgentConfigBuilder gigaChat(String token, URI endpoint, Path certificate, Path rootCertificate, Path privateKey) {
        return gigaChat(token, endpoint, certificate, rootCertificate, privateKey, this.verifySslCerts, this.modelName);
    }

    public AgentConfigBuilder gigaChat(String token,
                                       URI endpoint,
                                       Path certificate,
                                       Path rootCertificate,
                                       Path privateKey,
                                       boolean verifySslCerts,
                                       String modelName) {
        this.verifySslCerts = verifySslCerts;
        this.modelName = modelName;
        this.gigaChat = new GigaChatClientConfig(token,
                endpoint,
                certificate,
                rootCertificate,
                privateKey,
                verifySslCerts,
                modelName);
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

    public AgentConfigBuilder promptMode(PromptMode mode) {
        if (mode != null) {
            this.moduleOptions.put("prompt.mode", mode.name());
        }
        return this;
    }

    public AgentConfigBuilder promptVerbosity(PromptVerbosity verbosity) {
        if (verbosity != null) {
            this.moduleOptions.put("prompt.verbosity", verbosity.name());
        }
        return this;
    }

    public AgentConfigBuilder promptIncludeInstructionHeader(boolean includeHeader) {
        this.moduleOptions.put("prompt.includeInstructionHeader", includeHeader);
        return this;
    }

    public AgentConfigBuilder promptInstructionTemplate(String template) {
        if (template != null) {
            this.moduleOptions.put("prompt.instructionTemplate", template);
        }
        return this;
    }

    public AgentConfigBuilder promptResponseFormat(String responseFormat) {
        if (responseFormat != null) {
            this.moduleOptions.put("prompt.responseFormat", responseFormat);
        }
        return this;
    }

    public AgentConfigBuilder analysisIncludeStatic(boolean includeStatic) {
        this.moduleOptions.put("analysis.includeStatic", includeStatic);
        return this;
    }

    public AgentConfigBuilder analysisIncludeVerificationPolicy(boolean include) {
        this.moduleOptions.put("analysis.includeVerificationPolicy", include);
        return this;
    }

    public AgentConfigBuilder analysisMaxChainDepth(int depth) {
        this.moduleOptions.put("analysis.maxChainDepth", depth);
        return this;
    }

    public AgentConfigBuilder analysisExcludePackages(List<String> packages) {
        if (packages == null) {
            return this;
        }
        this.moduleOptions.put("analysis.excludePackages", new ArrayList<>(packages));
        return this;
    }

    public AgentConfig build() {
        return new AgentConfig(
                mode,
                projectPath,
                includeModules,
                includeClasses,
                parallelExecution,
                scanWholeProject,
                targetClasses,
                gigaChat,
                moduleOptions
        );
    }
}
