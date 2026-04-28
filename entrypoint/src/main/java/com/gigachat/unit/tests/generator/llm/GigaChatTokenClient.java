package com.gigachat.unit.tests.generator.llm;

import chat.giga.client.GigaChatClient;
import chat.giga.client.auth.AuthClient;
import chat.giga.client.auth.AuthClientBuilder;
import chat.giga.model.Scope;
import com.gigachat.unit.tests.generator.config.GigaChatClientConfig;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/**
 * LLM client that authenticates with GigaChat using an OAuth authorization key.
 */
public class GigaChatTokenClient extends BaseGigaChatLlmClient {
    static final String DEFAULT_AUTH_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final long TOKEN_REFRESH_SKEW_MILLIS = 30_000L;

    private final HttpClient rawHttpClient;
    private volatile AccessToken cachedAccessToken;

    public GigaChatTokenClient(GigaChatClientConfig config, PipelineLogger logger) {
        super(config, logger);
        if (!config.isTokenAuthConfigured()) {
            throw new IllegalStateException("Token authentication was requested without providing a token");
        }
        this.rawHttpClient = createRawHttpClient(config);
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

    static URI resolveAuthUrl(String authUrl) {
        String base = Optional.ofNullable(authUrl)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse(DEFAULT_AUTH_URL);
        return URI.create(base);
    }

    static String resolveCompletionUrl(String apiBaseUrl) {
        return removeTrailingSlash(resolveApiUrl(apiBaseUrl)) + "/chat/completions";
    }

    @Override
    protected GigaChatClient createClient() {
        GigaChatClientConfig config = clientConfig();
        String token = config.tokenOptional()
                .orElseThrow(() -> new IllegalStateException("Token authentication requires a non-empty token"));
        URI authUrl = resolveAuthUrl(config.authUrlOptional().map(Object::toString).orElse(null));
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

    @Override
    protected Optional<String> requestContentViaRawHttp(String prompt) {
        return requestRawCompletion(prompt, false);
    }

    @Override
    protected Optional<String> requestStructuredResponseViaRawHttp(String prompt) {
        return requestRawCompletion(prompt, true);
    }

    @Override
    public String requestStructuredResponse(String prompt) {
        Optional<String> rawResponse = requestStructuredResponseViaRawHttp(prompt);
        if (rawResponse.isPresent()) {
            return rawResponse.get();
        }
        return requestStructuredResponseWithSdk(prompt, false);
    }

    private Optional<String> requestRawCompletion(String prompt, boolean deterministicMode) {
        try {
            if (deterministicMode) {
                logger().info("Sending deterministic reasoning request via raw HTTP token client.");
            } else {
                logger().info("Retrying GigaChat request via raw HTTP token client.");
            }
            String responseBody = requestCompletion(prompt, deterministicMode);
            JSONObject root = new JSONObject(responseBody);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                logger().warn("Raw HTTP response did not contain choices array.");
                return Optional.empty();
            }
            JSONObject firstChoice = choices.optJSONObject(0);
            if (firstChoice == null) {
                logger().warn("Raw HTTP response did not contain the first choice object.");
                return Optional.empty();
            }
            JSONObject message = firstChoice.optJSONObject("message");
            if (message == null) {
                logger().warn("Raw HTTP response did not contain a message object.");
                return Optional.empty();
            }
            String content = message.optString("content", "").trim();
            return content.isEmpty() ? Optional.empty() : Optional.of(content);
        } catch (Exception exception) {
            logger().error("Raw HTTP fallback request failed", exception);
            return Optional.empty();
        }
    }

    private String requestCompletion(String prompt, boolean deterministicMode) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(resolveCompletionUrl(
                        clientConfig().endpointOptional().map(Object::toString).orElse(null))))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + resolveBearerToken())
                .header("X-Request-ID", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(buildCompletionPayload(prompt, deterministicMode)))
                .build();
        HttpResponse<String> response = rawHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("Raw completion request failed with status "
                    + statusCode
                    + ": "
                    + response.body());
        }
        return response.body();
    }

    private String buildCompletionPayload(String prompt, boolean deterministicMode) {
        JSONObject request = new JSONObject();
        request.put("model", resolveModel());
        request.put("messages", new JSONArray()
                .put(new JSONObject()
                        .put("role", "user")
                        .put("content", prompt)));
        if (deterministicMode) {
            request.put("temperature", 0.0);
            request.put("top_p", 1.0);
            request.put("repetition_penalty", 1.0);
            request.put("max_tokens", 1400);
        }
        return request.toString();
    }

    private String resolveBearerToken() throws Exception {
        AccessToken cached = cachedAccessToken;
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMillis() - TOKEN_REFRESH_SKEW_MILLIS > now) {
            return cached.value();
        }
        synchronized (this) {
            cached = cachedAccessToken;
            now = System.currentTimeMillis();
            if (cached != null && cached.expiresAtMillis() - TOKEN_REFRESH_SKEW_MILLIS > now) {
                return cached.value();
            }
            AccessToken refreshed = requestAccessToken();
            cachedAccessToken = refreshed;
            return refreshed.value();
        }
    }

    private AccessToken requestAccessToken() throws Exception {
        String authKey = clientConfig().tokenOptional()
                .orElseThrow(() -> new IllegalStateException("Token authentication requires a non-empty token"));
        URI authUrl = resolveAuthUrl(clientConfig().authUrlOptional().map(Object::toString).orElse(null));
        HttpRequest request = HttpRequest.newBuilder(authUrl)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("RqUID", UUID.randomUUID().toString())
                .header("Authorization", "Basic " + authKey)
                .POST(HttpRequest.BodyPublishers.ofString("scope=GIGACHAT_API_PERS"))
                .build();
        HttpResponse<String> response = rawHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("OAuth request failed with status "
                    + statusCode
                    + ": "
                    + response.body());
        }
        JSONObject authResponse = new JSONObject(response.body());
        String accessToken = authResponse.optString("access_token", "").trim();
        if (accessToken.isEmpty()) {
            throw new IllegalStateException("OAuth response did not include access_token");
        }
        long expiresAt = authResponse.optLong("expires_at", System.currentTimeMillis() + 25 * 60_000L);
        return new AccessToken(accessToken, expiresAt);
    }

    private record AccessToken(String value, long expiresAtMillis) {
    }

    private HttpClient createRawHttpClient(GigaChatClientConfig config) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT);
        if (!config.verifySslCerts()) {
            builder.sslContext(createInsecureSslContext());
        }
        return builder.build();
    }

    private SSLContext createInsecureSslContext() {
        try {
            TrustManager[] trustAllManagers = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllManagers, new SecureRandom());
            return sslContext;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to initialise insecure SSL context for raw HTTP fallback", exception);
        }
    }
}
