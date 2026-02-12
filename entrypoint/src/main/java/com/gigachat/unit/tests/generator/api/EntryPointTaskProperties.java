package com.gigachat.unit.tests.generator.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable task configuration object for launching {@code MainAgentEntry} from external Gradle modules.
 */
public class EntryPointTaskProperties {
    private String mode = "scan";
    private String path;
    private List<String> includeModules = new ArrayList<>();
    private List<String> includeClasses = new ArrayList<>();
    private List<String> targetClasses = new ArrayList<>();
    private boolean singleFile;
    private boolean parallel;
    private boolean project;
    private boolean compile;
    private boolean execute;
    private String sourceBranch;
    private String targetBranch;
    private String token;
    private String endpoint;
    private String authUrl;
    private String cert;
    private String rootCert;
    private String key;
    private boolean ssl;
    private String model;
    private boolean proxy;

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public List<String> getIncludeModules() { return includeModules; }
    public void setIncludeModules(List<String> includeModules) { this.includeModules = includeModules == null ? new ArrayList<>() : new ArrayList<>(includeModules); }
    public List<String> getIncludeClasses() { return includeClasses; }
    public void setIncludeClasses(List<String> includeClasses) { this.includeClasses = includeClasses == null ? new ArrayList<>() : new ArrayList<>(includeClasses); }
    public List<String> getTargetClasses() { return targetClasses; }
    public void setTargetClasses(List<String> targetClasses) { this.targetClasses = targetClasses == null ? new ArrayList<>() : new ArrayList<>(targetClasses); }
    public boolean isSingleFile() { return singleFile; }
    public void setSingleFile(boolean singleFile) { this.singleFile = singleFile; }
    public boolean isParallel() { return parallel; }
    public void setParallel(boolean parallel) { this.parallel = parallel; }
    public boolean isProject() { return project; }
    public void setProject(boolean project) { this.project = project; }
    public boolean isCompile() { return compile; }
    public void setCompile(boolean compile) { this.compile = compile; }
    public boolean isExecute() { return execute; }
    public void setExecute(boolean execute) { this.execute = execute; }
    public String getSourceBranch() { return sourceBranch; }
    public void setSourceBranch(String sourceBranch) { this.sourceBranch = sourceBranch; }
    public String getTargetBranch() { return targetBranch; }
    public void setTargetBranch(String targetBranch) { this.targetBranch = targetBranch; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getAuthUrl() { return authUrl; }
    public void setAuthUrl(String authUrl) { this.authUrl = authUrl; }
    public String getCert() { return cert; }
    public void setCert(String cert) { this.cert = cert; }
    public String getRootCert() { return rootCert; }
    public void setRootCert(String rootCert) { this.rootCert = rootCert; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public boolean isSsl() { return ssl; }
    public void setSsl(boolean ssl) { this.ssl = ssl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public boolean isProxy() { return proxy; }
    public void setProxy(boolean proxy) { this.proxy = proxy; }
}
