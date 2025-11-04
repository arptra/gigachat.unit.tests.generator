package com.gigachat.unit.tests.generator.config;

public interface ConfigurableModule {

    String name();

    void configure(AgentConfig.Builder builder);
}
