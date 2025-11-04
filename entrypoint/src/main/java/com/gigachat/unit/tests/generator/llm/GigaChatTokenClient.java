package com.gigachat.unit.tests.generator.llm;

import chat.giga.client.GigaChatClient;
import chat.giga.client.auth.AuthClient;
import com.gigachat.unit.tests.generator.config.GigaChatClientConfig;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;

import java.util.Objects;

/**
 * LLM client that authenticates with GigaChat using a pre-issued bearer token.
 */
public class GigaChatTokenClient extends BaseGigaChatLlmClient {
    private final GigaChatClientConfig config;

    public GigaChatTokenClient(GigaChatClientConfig config, PipelineLogger logger) {
        super(logger);
        this.config = Objects.requireNonNull(config, "config");
        if (!config.isTokenAuthConfigured()) {
            throw new IllegalStateException("Token authentication was requested without providing a token");
        }
    }

    @Override
    protected GigaChatClient createClient() {
        String token = config.tokenOptional()
                .orElseThrow(() -> new IllegalStateException("Token authentication requires a non-empty token"));
        AuthClient authClient = AuthClient.builder()
                .withProvidedTokenAuth(token)
                .build();
        return GigaChatClient.builder()
                .apiUrl(config.endpointOptional().map(Object::toString).orElse(null))
                .authClient(authClient)
                .build();
    }
}
