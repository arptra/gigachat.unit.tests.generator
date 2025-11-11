package com.gigachat.unit.tests.generator.llm;

import chat.giga.client.GigaChatClient;
import chat.giga.client.auth.AuthClient;
import chat.giga.client.auth.AuthClientBuilder;
import chat.giga.model.Scope;
import com.gigachat.unit.tests.generator.config.GigaChatClientConfig;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

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

    private static String removeTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    static String resolveApiUrl(String apiBaseUrl) {
        String base = Optional.ofNullable(apiBaseUrl)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse(DEFAULT_API_URL);
        String sanitized = removeTrailingSlash(base);
        if (sanitized.matches(".*/api(/v\\d+)?$")) {
            if (sanitized.endsWith("/api")) {
                return sanitized + "/v1";
            }
            return sanitized;
        }
        return sanitized + "/api/v1";
    }

    @Override
    protected GigaChatClient createClient() {
        GigaChatClientConfig config = clientConfig();
        String token = config.tokenOptional()
                .orElseThrow(() -> new IllegalStateException("Token authentication requires a non-empty token"));
        URI authUrl = config.authUrlOptional()
                .orElseThrow(() -> new IllegalStateException("Token authentication requires a non-empty authUrl"));
        AuthClient authClient = AuthClient.builder()
                .withOAuth(AuthClientBuilder.OAuthBuilder.builder()
                        .scope(Scope.GIGACHAT_API_PERS)
                        .authApiUrl(authUrl.toString())
                        .verifySslCerts(config.verifySslCerts())
                        .authKey(token)
                        .build())
                .build();
        String apiUrl = resolveApiUrl(config.endpointOptional().map(Object::toString).orElse(null));
        return GigaChatClient.builder()
                .apiUrl(apiUrl)
                .authClient(authClient)
                .verifySslCerts(config.verifySslCerts())
                .build();
    }
}
