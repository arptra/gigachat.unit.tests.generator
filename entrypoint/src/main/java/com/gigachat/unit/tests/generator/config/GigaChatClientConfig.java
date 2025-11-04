package com.gigachat.unit.tests.generator.config;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

public class GigaChatClientConfig {
    private final String token;
    private final URI endpoint;

    public GigaChatClientConfig(String token, URI endpoint) {
        this.token = normaliseToken(token);
        this.endpoint = endpoint;
    }

    private String normaliseToken(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GigaChatClientConfig that)) {
            return false;
        }
        return Objects.equals(token, that.token) && Objects.equals(endpoint, that.endpoint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(token, endpoint);
    }

    @Override
    public String toString() {
        return "GigaChatClientConfig{" +
                "token='" + token + '\'' +
                ", endpoint=" + endpoint +
                '}';
    }
}
