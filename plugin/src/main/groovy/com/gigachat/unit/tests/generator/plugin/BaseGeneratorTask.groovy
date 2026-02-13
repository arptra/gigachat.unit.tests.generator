package com.gigachat.unit.tests.generator.plugin

import com.gigachat.unit.tests.generator.api.EntryPointTaskApi
import com.gigachat.unit.tests.generator.api.EntryPointTaskProperties
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class BaseGeneratorTask extends DefaultTask {
    @Internal
    final GeneratorTaskProperties propertiesConfig = new GeneratorTaskProperties()

    protected abstract String defaultMode()

    void properties(Closure<?> closure) {
        closure.delegate = propertiesConfig
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure.call()
    }

    @TaskAction
    void runGeneration() {
        propertiesConfig.loadFromProjectProperties(project, defaultMode())

        EntryPointTaskProperties props = new EntryPointTaskProperties()
        props.setMode(propertiesConfig.mode)
        props.setPath(propertiesConfig.path)
        props.setIncludeModules(propertiesConfig.includeModules)
        props.setIncludeClasses(propertiesConfig.includeClasses)
        props.setTargetClasses(propertiesConfig.targetClasses)
        props.setSingleFile(propertiesConfig.singleFile)
        props.setParallel(propertiesConfig.parallel)
        props.setProject(propertiesConfig.project)
        props.setCompile(propertiesConfig.compile)
        props.setExecute(propertiesConfig.execute)
        props.setSourceBranch(propertiesConfig.sourceBranch)
        props.setTargetBranch(propertiesConfig.targetBranch)
        props.setToken(propertiesConfig.token)
        props.setEndpoint(propertiesConfig.endpoint)
        props.setAuthUrl(propertiesConfig.authUrl)
        props.setCert(propertiesConfig.cert)
        props.setRootCert(propertiesConfig.rootCert)
        props.setKey(propertiesConfig.key)
        props.setSsl(propertiesConfig.ssl)
        props.setProxy(propertiesConfig.proxy)
        props.setModel(propertiesConfig.model)

        new EntryPointTaskApi().execute(props)
    }
}
