package com.gigachat.unit.tests.generator.plugin

import com.gigachat.unit.tests.generator.api.EntryPointTaskProperties

class GeneratorTaskProperties {
    String mode
    String path
    List<String> includeModules = []
    List<String> includeClasses = []
    List<String> targetClasses = []
    boolean singleFile
    boolean parallel
    boolean project
    boolean compile
    boolean execute
    String sourceBranch
    String targetBranch
    String token
    String endpoint
    String authUrl
    String cert
    String rootCert
    String key
    boolean ssl
    String model

    static GeneratorTaskProperties fromSystemProperties() {
        GeneratorTaskProperties props = new GeneratorTaskProperties()
        props.mode = text(System.getProperty('gigachat.mode')) ?: 'scan'
        props.path = text(System.getProperty('gigachat.path'))
        props.includeModules = splitCsv(System.getProperty('gigachat.includeModules'))
        props.includeClasses = splitCsv(System.getProperty('gigachat.includeClasses'))
        props.targetClasses = splitCsv(System.getProperty('gigachat.class'))
        props.singleFile = bool(System.getProperty('gigachat.singleFile'))
        props.parallel = bool(System.getProperty('gigachat.parallel'))
        props.project = bool(System.getProperty('gigachat.project'))
        props.compile = bool(System.getProperty('gigachat.compile'))
        props.execute = bool(System.getProperty('gigachat.execute'))
        props.sourceBranch = text(System.getProperty('gigachat.sourceBranch'))
        props.targetBranch = text(System.getProperty('gigachat.targetBranch'))
        props.token = text(System.getProperty('gigachat.token'))
        props.endpoint = text(System.getProperty('gigachat.endpoint'))
        props.authUrl = text(System.getProperty('gigachat.authUrl'))
        props.cert = text(System.getProperty('gigachat.cert'))
        props.rootCert = text(System.getProperty('gigachat.rootCert'))
        props.key = text(System.getProperty('gigachat.key'))
        props.ssl = bool(System.getProperty('gigachat.ssl'))
        props.model = text(System.getProperty('gigachat.model'))
        return props
    }

    EntryPointTaskProperties toEntryPointProperties() {
        EntryPointTaskProperties props = new EntryPointTaskProperties()
        props.setMode(mode)
        props.setPath(path)
        props.setIncludeModules(includeModules)
        props.setIncludeClasses(includeClasses)
        props.setTargetClasses(targetClasses)
        props.setSingleFile(singleFile)
        props.setParallel(parallel)
        props.setProject(project)
        props.setCompile(compile)
        props.setExecute(execute)
        props.setSourceBranch(sourceBranch)
        props.setTargetBranch(targetBranch)
        props.setToken(token)
        props.setEndpoint(endpoint)
        props.setAuthUrl(authUrl)
        props.setCert(cert)
        props.setRootCert(rootCert)
        props.setKey(key)
        props.setSsl(ssl)
        props.setModel(model)
        return props
    }

    private static boolean bool(String value) {
        return value != null && Boolean.parseBoolean(value)
    }

    private static String text(String value) {
        if (value == null) {
            return null
        }
        String trimmed = value.trim()
        return trimmed.isEmpty() ? null : trimmed
    }

    private static List<String> splitCsv(String value) {
        String cleaned = text(value)
        if (cleaned == null) {
            return []
        }
        cleaned.split(',').collect { it.trim() }.findAll { !it.isEmpty() }
    }
}
