package com.gigachat.unit.tests.generator.config;

import java.net.URI;
import java.util.Optional;

public record GigaChatClientConfig(String token, URI endpoint) {

    public GigaChatClientConfig {
        token = token == null || token.isBlank() ? null : token;
        endpoint = endpoint;
    }

    public Optional<String> tokenOptional() {
        return Optional.ofNullable(token);
    }

    public Optional<URI> endpointOptional() {
        return Optional.ofNullable(endpoint);
    }

    public boolean isConfigured() {
        return tokenOptional().isPresent() && endpointOptional().isPresent();
    }

    public GigaChatClientConfig merge(GigaChatClientConfig other) {
        if (other == null) {
            return this;
        }
        String mergedToken = other.tokenOptional().orElse(token);
        URI mergedEndpoint = other.endpointOptional().orElse(endpoint);
        return new GigaChatClientConfig(mergedToken, mergedEndpoint);
    }
}
