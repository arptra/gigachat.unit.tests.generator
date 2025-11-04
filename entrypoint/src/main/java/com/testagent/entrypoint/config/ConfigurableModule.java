package com.testagent.entrypoint.config;

public interface ConfigurableModule {

    String name();

    void configure(AgentConfig.Builder builder);
}
