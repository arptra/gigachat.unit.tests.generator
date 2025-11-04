package com.gigachat.unit.tests.generator.llm;

import chat.giga.client.GigaChatClient;
import chat.giga.client.auth.AuthClient;
import com.gigachat.unit.tests.generator.config.GigaChatClientConfig;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;

/**
 * LLM client that authenticates with GigaChat using a pre-issued bearer token.
 */
public class GigaChatTokenClient extends BaseGigaChatLlmClient {
    public GigaChatTokenClient(GigaChatClientConfig config, PipelineLogger logger) {
        super(config, logger);
        if (!config.isTokenAuthConfigured()) {
            throw new IllegalStateException("Token authentication was requested without providing a token");
        }
    }

    @Override
    protected GigaChatClient createClient() {
        GigaChatClientConfig config = clientConfig();
        String token = config.tokenOptional()
                .orElseThrow(() -> new IllegalStateException("Token authentication requires a non-empty token"));
        AuthClient authClient = AuthClient.builder()
                .withProvidedTokenAuth(token)
                .build();
        return GigaChatClient.builder()
                .apiUrl(config.endpointOptional().map(Object::toString).orElse(null))
                .authClient(authClient)
                .verifySslCerts(config.verifySslCerts())
                .build();
    }
}
