package com.gigachat.unit.tests.generator.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project

class GigaChatGeneratorPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.tasks.register('genAiTest', GenAiTask) {
            group = 'verification'
            description = 'Groovy DefaultTask wrapper running scan generation through entrypoint API.'
            dependsOn(':entrypoint:classes')
        }
        project.tasks.register('diffGenUnitTest', DiffGenUnitTestTask) {
            group = 'verification'
            description = 'Groovy DefaultTask wrapper running diff generation through entrypoint API.'
            dependsOn(':entrypoint:classes')
        }
        project.defaultTasks('genAiTest')
    }
}
