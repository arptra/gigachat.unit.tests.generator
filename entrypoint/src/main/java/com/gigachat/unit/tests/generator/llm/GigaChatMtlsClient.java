package com.gigachat.unit.tests.generator.llm;

import chat.giga.client.GigaChatClient;
import chat.giga.client.auth.AuthClient;
import chat.giga.http.client.HttpClient;
import chat.giga.http.client.JdkHttpClientBuilder;
import com.gigachat.unit.tests.generator.config.GigaChatClientConfig;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * LLM client that authenticates with GigaChat using mutual TLS credentials.
 */
public class GigaChatMtlsClient extends BaseGigaChatLlmClient {
    private static final char[] KEYSTORE_PASSWORD = "changeit".toCharArray();

    private final GigaChatClientConfig config;

    public GigaChatMtlsClient(GigaChatClientConfig config, PipelineLogger logger) {
        super(config, logger);
        this.config = Objects.requireNonNull(config, "config");
        if (!config.isMtlsConfigured()) {
            throw new IllegalStateException("mTLS authentication requires certificate, root certificate and private key");
        }
    }

    @Override
    protected GigaChatClient createClient() throws Exception {
        SSLContext sslContext = createSslContext();
        HttpClient httpClient = new JdkHttpClientBuilder()
                .httpClientBuilder(java.net.http.HttpClient.newBuilder().sslContext(sslContext))
                .build();
        AuthClient authClient = AuthClient.builder()
                .withCertificatesAuth(httpClient)
                .build();
        return GigaChatClient.builder()
                .apiUrl(config.endpointOptional().map(Object::toString).orElse(null))
                .authClient(authClient)
                .verifySslCerts(config.verifySslCerts())
                .build();
    }

    private SSLContext createSslContext() throws GeneralSecurityException, IOException {
        X509Certificate clientCertificate = loadCertificate(config.certificatePathOptional()
                .orElseThrow(() -> new IllegalStateException("Client certificate path is missing")));
        X509Certificate rootCertificate = loadCertificate(config.rootCertificatePathOptional()
                .orElseThrow(() -> new IllegalStateException("Root certificate path is missing")));
        PrivateKey privateKey = loadPrivateKey(config.privateKeyPathOptional()
                .orElseThrow(() -> new IllegalStateException("Private key path is missing")),
                clientCertificate.getPublicKey().getAlgorithm());

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, KEYSTORE_PASSWORD);
        Certificate[] chain = new Certificate[]{clientCertificate, rootCertificate};
        keyStore.setKeyEntry("client", privateKey, KEYSTORE_PASSWORD, chain);

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, KEYSTORE_PASSWORD);
        trustStore.setCertificateEntry("root", rootCertificate);

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, KEYSTORE_PASSWORD);

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
        return sslContext;
    }

    private X509Certificate loadCertificate(Path path) throws CertificateException, IOException {
        try (InputStream stream = Files.newInputStream(path)) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(stream);
        }
    }

    private PrivateKey loadPrivateKey(Path path, String algorithmHint) throws IOException, GeneralSecurityException {
        String pem = Files.readString(path, StandardCharsets.UTF_8);
        String normalised = pem.replaceAll("-----BEGIN ([A-Z ]+)-----", "")
                .replaceAll("-----END ([A-Z ]+)-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getMimeDecoder().decode(normalised);
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            decoded = convertPkcs1ToPkcs8(decoded, "1.2.840.113549.1.1.1");
            algorithmHint = "RSA";
        }
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
        List<String> algorithms = new ArrayList<>();
        if (algorithmHint != null && !algorithmHint.isBlank()) {
            algorithms.add(algorithmHint);
        }
        algorithms.add("RSA");
        algorithms.add("EC");
        GeneralSecurityException lastError = null;
        for (String algorithm : algorithms) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(keySpec);
            } catch (GeneralSecurityException exception) {
                lastError = exception;
            }
        }
        throw lastError == null ? new InvalidKeySpecException("Unsupported key format") : lastError;
    }

    private byte[] convertPkcs1ToPkcs8(byte[] pkcs1, String algorithmOid) {
        try {
            byte[] version = new byte[]{0x02, 0x01, 0x00};
            byte[] algorithmIdentifier = buildAlgorithmIdentifier(algorithmOid);
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            inner.write(version);
            inner.write(algorithmIdentifier);
            inner.write(0x04);
            writeLength(inner, pkcs1.length);
            inner.write(pkcs1);
            byte[] innerBytes = inner.toByteArray();
            ByteArrayOutputStream outer = new ByteArrayOutputStream();
            outer.write(0x30);
            writeLength(outer, innerBytes.length);
            outer.write(innerBytes);
            return outer.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to convert PKCS#1 key", exception);
        }
    }

    private byte[] buildAlgorithmIdentifier(String oid) throws IOException {
        String[] parts = oid.split("\\.");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x06);
        ByteArrayOutputStream encodedOid = new ByteArrayOutputStream();
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        encodedOid.write(first * 40 + second);
        for (int i = 2; i < parts.length; i++) {
            long value = Long.parseLong(parts[i]);
            List<Byte> bytes = new ArrayList<>();
            bytes.add((byte) (value & 0x7F));
            value >>= 7;
            while (value > 0) {
                bytes.add(0, (byte) ((value & 0x7F) | 0x80));
                value >>= 7;
            }
            for (Byte b : bytes) {
                encodedOid.write(b);
            }
        }
        byte[] oidBytes = encodedOid.toByteArray();
        writeLength(body, oidBytes.length);
        body.write(oidBytes);
        byte[] oidBody = body.toByteArray();

        ByteArrayOutputStream sequence = new ByteArrayOutputStream();
        sequence.write(0x30);
        writeLength(sequence, oidBody.length + 2);
        sequence.write(oidBody);
        sequence.write(0x05);
        sequence.write(0x00);
        return sequence.toByteArray();
    }

    private void writeLength(ByteArrayOutputStream stream, int length) throws IOException {
        if (length < 0x80) {
            stream.write(length);
            return;
        }
        byte[] bytes = intToBytes(length);
        stream.write(0x80 | bytes.length);
        stream.write(bytes);
    }

    private byte[] intToBytes(int value) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        boolean significant = false;
        for (int i = 3; i >= 0; i--) {
            int shift = i * 8;
            int current = (value >> shift) & 0xFF;
            if (current != 0 || significant) {
                stream.write(current);
                significant = true;
            }
        }
        byte[] bytes = stream.toByteArray();
        return bytes.length == 0 ? new byte[]{0} : bytes;
    }
}
