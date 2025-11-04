package com.gigachat.unit.tests.generator.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds configuration required to initialise a GigaChat client.
 */
public class GigaChatClientConfig {

    public enum AuthMode {
        NONE,
        TOKEN,
        MTLS
    }

    private final String token;
    private final URI endpoint;
    private final Path certificatePath;
    private final Path rootCertificatePath;
    private final Path privateKeyPath;
    private final boolean verifySslCerts;
    private final String modelName;

    public GigaChatClientConfig(String token,
                                URI endpoint,
                                Path certificatePath,
                                Path rootCertificatePath,
                                Path privateKeyPath,
                                boolean verifySslCerts,
                                String modelName) {
        this.token = normaliseToken(token);
        this.endpoint = endpoint;
        this.certificatePath = normalisePath(certificatePath);
        this.rootCertificatePath = normalisePath(rootCertificatePath);
        this.privateKeyPath = normalisePath(privateKeyPath);
        this.verifySslCerts = verifySslCerts;
        this.modelName = normaliseModelName(modelName);
    }

    private String normaliseToken(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Path normalisePath(Path value) {
        if (value == null) {
            return null;
        }
        return value.toAbsolutePath().normalize();
    }

    private String normaliseModelName(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase();
    }

    public static GigaChatClientConfig empty() {
        return new GigaChatClientConfig(null, null, null, null, null, false, null);
    }

    public static GigaChatClientConfig forToken(String token, URI endpoint) {
        return new GigaChatClientConfig(token, endpoint, null, null, null, false, null);
    }

    public static GigaChatClientConfig forMtls(Path certificate,
                                               Path rootCertificate,
                                               Path privateKey,
                                               URI endpoint) {
        return new GigaChatClientConfig(null, endpoint, certificate, rootCertificate, privateKey, false, null);
    }

    public Optional<String> tokenOptional() {
        return Optional.ofNullable(token);
    }

    public Optional<URI> endpointOptional() {
        return Optional.ofNullable(endpoint);
    }

    public Optional<Path> certificatePathOptional() {
        return Optional.ofNullable(certificatePath);
    }

    public Optional<Path> rootCertificatePathOptional() {
        return Optional.ofNullable(rootCertificatePath);
    }

    public Optional<Path> privateKeyPathOptional() {
        return Optional.ofNullable(privateKeyPath);
    }

    public boolean verifySslCerts() {
        return verifySslCerts;
    }

    public Optional<String> modelNameOptional() {
        return Optional.ofNullable(modelName);
    }

    public String resolvedModelName() {
        return modelName != null ? modelName : "GIGA_CHAT_MAX_2";
    }

    public AuthMode authMode() {
        if (isTokenAuthConfigured()) {
            return AuthMode.TOKEN;
        }
        if (isMtlsConfigured()) {
            return AuthMode.MTLS;
        }
        return AuthMode.NONE;
    }

    public boolean isTokenAuthConfigured() {
        return token != null;
    }

    public boolean isMtlsConfigured() {
        return certificatePath != null && rootCertificatePath != null && privateKeyPath != null;
    }

    public boolean isConfigured() {
        return isTokenAuthConfigured() || isMtlsConfigured();
    }

    public GigaChatClientConfig merge(GigaChatClientConfig other) {
        if (other == null) {
            return this;
        }
        String mergedToken = other.tokenOptional().orElse(token);
        URI mergedEndpoint = other.endpointOptional().orElse(endpoint);
        Path mergedCert = other.certificatePathOptional().orElse(certificatePath);
        Path mergedRoot = other.rootCertificatePathOptional().orElse(rootCertificatePath);
        Path mergedKey = other.privateKeyPathOptional().orElse(privateKeyPath);
        boolean mergedVerifySsl = other.verifySslCerts() || (!other.isConfigured() && this.verifySslCerts);
        String mergedModel = other.modelNameOptional().orElse(modelName);
        return new GigaChatClientConfig(mergedToken,
                mergedEndpoint,
                mergedCert,
                mergedRoot,
                mergedKey,
                mergedVerifySsl,
                mergedModel);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GigaChatClientConfig that)) {
            return false;
        }
        return Objects.equals(token, that.token)
                && Objects.equals(endpoint, that.endpoint)
                && Objects.equals(certificatePath, that.certificatePath)
                && Objects.equals(rootCertificatePath, that.rootCertificatePath)
                && Objects.equals(privateKeyPath, that.privateKeyPath)
                && verifySslCerts == that.verifySslCerts
                && Objects.equals(modelName, that.modelName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(token, endpoint, certificatePath, rootCertificatePath, privateKeyPath, verifySslCerts, modelName);
    }

    @Override
    public String toString() {
        return "GigaChatClientConfig{"
                + "token='" + token + '\''
                + ", endpoint=" + endpoint
                + ", certificatePath=" + certificatePath
                + ", rootCertificatePath=" + rootCertificatePath
                + ", privateKeyPath=" + privateKeyPath
                + ", verifySslCerts=" + verifySslCerts
                + ", modelName='" + modelName + '\''
                + '}';
    }
}
