package com.gigachat.unit.tests.generator.llm;

import chat.giga.client.GigaChatClient;
import com.gigachat.unit.tests.generator.config.GigaChatClientConfig;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;

import java.net.URI;

/**
 * Proxy-backed client that reuses {@link BaseGigaChatLlmClient} flow and creates
 * a plain HTTP GigaChat SDK client without token/mTLS inputs.
 */
public class ProxyHttpLlmClient extends BaseGigaChatLlmClient {
    private static final URI DEFAULT_PROXY_ENDPOINT = URI.create("http://localhost:8080");

    private final URI endpoint;

    public ProxyHttpLlmClient(GigaChatClientConfig config, PipelineLogger logger) {
        super(config, logger);
        this.endpoint = config == null
                ? DEFAULT_PROXY_ENDPOINT
                : config.endpointOptional().orElse(DEFAULT_PROXY_ENDPOINT);
    }

    @Override
    protected GigaChatClient createClient() {
        String apiUrl = GigaChatTokenClient.resolveApiUrl(endpoint.toString());
        return GigaChatClient.builder()
                .apiUrl(apiUrl)
                .verifySslCerts(false)
                .build();
    }
}
