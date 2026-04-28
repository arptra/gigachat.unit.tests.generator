package com.gigachat.unit.tests.generator.plugin

import org.gradle.api.Project

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
    boolean coverage
    String coverageGoals
    String coverageThreshold
    String sourceBranch
    String targetBranch
    String token
    String endpoint
    String authUrl
    String cert
    String rootCert
    String key
    boolean ssl
    boolean proxy
    String model

    void loadFromProjectProperties(Project projectRef, String defaultMode) {
        mode = text(mode) ?: defaultMode
        path = text(path) ?: text(projectRef.findProperty('gigachat.path'))
        includeModules = includeModules ?: splitCsv(projectRef.findProperty('gigachat.includeModules'))
        includeClasses = includeClasses ?: splitCsv(projectRef.findProperty('gigachat.includeClasses'))
        targetClasses = targetClasses ?: splitCsv(projectRef.findProperty('gigachat.class'))
        singleFile = singleFile || bool(projectRef.findProperty('gigachat.singleFile'))
        parallel = parallel || bool(projectRef.findProperty('gigachat.parallel'))
        project = project || bool(projectRef.findProperty('gigachat.project'))
        compile = compile || bool(projectRef.findProperty('gigachat.compile'))
        execute = execute || bool(projectRef.findProperty('gigachat.execute'))
        coverage = coverage || bool(projectRef.findProperty('gigachat.coverage'))
        coverageGoals = text(coverageGoals) ?: text(projectRef.findProperty('gigachat.coverageGoals'))
        coverageThreshold = text(coverageThreshold) ?: text(projectRef.findProperty('gigachat.coverageThreshold'))
        sourceBranch = text(sourceBranch) ?: text(projectRef.findProperty('gigachat.sourceBranch'))
        targetBranch = text(targetBranch) ?: text(projectRef.findProperty('gigachat.targetBranch'))
        token = text(token) ?: text(projectRef.findProperty('gigachat.token'))
        endpoint = text(endpoint) ?: text(projectRef.findProperty('gigachat.endpoint'))
        authUrl = text(authUrl) ?: text(projectRef.findProperty('gigachat.authUrl'))
        cert = text(cert) ?: text(projectRef.findProperty('gigachat.cert'))
        rootCert = text(rootCert) ?: text(projectRef.findProperty('gigachat.rootCert'))
        key = text(key) ?: text(projectRef.findProperty('gigachat.key'))
        ssl = ssl || bool(projectRef.findProperty('gigachat.ssl'))
        proxy = proxy || bool(projectRef.findProperty('gigachat.proxy'))
        model = text(model) ?: text(projectRef.findProperty('gigachat.model'))
    }

    private static boolean bool(Object value) { value != null && Boolean.parseBoolean(value.toString()) }
    private static String text(Object value) {
        if (value == null) return null
        String t = value.toString().trim()
        t.isEmpty() ? null : t
    }
    private static List<String> splitCsv(Object value) {
        String cleaned = text(value)
        if (cleaned == null) return []
        cleaned.split(',').collect { it.trim() }.findAll { !it.isEmpty() }
    }
}
