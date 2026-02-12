package com.gigachat.unit.tests.generator.plugin

import com.gigachat.unit.tests.generator.api.EntryPointTaskApi

class GenAiTaskRunner {
    static void main(String[] args) {
        GeneratorTaskProperties properties = GeneratorTaskProperties.fromSystemProperties()
        EntryPointTaskApi api = new EntryPointTaskApi()
        api.execute(properties.toEntryPointProperties())
    }
}
