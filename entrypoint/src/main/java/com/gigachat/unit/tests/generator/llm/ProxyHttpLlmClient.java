package com.gigachat.unit.tests.generator.llm;

import com.gigachat.unit.tests.generator.config.GigaChatClientConfig;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight proxy client that sends prompt payloads over plain HTTP without auth/certificates.
 */
public class ProxyHttpLlmClient implements LlmClient {
    private static final URI DEFAULT_PROXY_ENDPOINT = URI.create("http://localhost:8080/generate");

    private final HttpClient httpClient;
    private final URI endpoint;
    private final PipelineLogger logger;
    private final LlmClient fallback = new LlmClientStub();

    public ProxyHttpLlmClient(GigaChatClientConfig config, PipelineLogger logger) {
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.endpoint = config == null
                ? DEFAULT_PROXY_ENDPOINT
                : config.endpointOptional().orElse(DEFAULT_PROXY_ENDPOINT);
    }

    @Override
    public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                    TestClassInfo classInfo,
                                                    TestMethodInfo methodInfo,
                                                    MockPlan plan) {
        try {
            String payload = "{\"prompt\":\"" + quote(prompt)
                    + "\",\"className\":\"" + quote(classInfo.getClassName())
                    + "\",\"methodSignature\":\"" + quote(methodInfo.getSignature())
                    + "\"}";
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.warn("Proxy LLM returned status " + response.statusCode() + "; falling back to stub.");
                return fallback.generateTestSnippet(prompt, classInfo, methodInfo, plan);
            }
            String responseBody = extractCode(response.body());
            String methodName = deriveMethodName(methodInfo);
            return new GeneratedTestSnippet(classInfo.getTestClassName(), methodName, responseBody, List.of());
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("Proxy LLM call failed; falling back to stub.");
            return fallback.generateTestSnippet(prompt, classInfo, methodInfo, plan);
        }
    }

    private String deriveMethodName(TestMethodInfo methodInfo) {
        String signature = methodInfo == null ? "generated" : methodInfo.getSignature();
        String simplified = signature.replaceAll("[^A-Za-z0-9]", " ").trim();
        String[] parts = simplified.isEmpty() ? new String[]{"Generated"} : simplified.split("\\s+");
        StringBuilder builder = new StringBuilder("should");
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            builder.append(capitalise(part));
            if (builder.length() > 40) {
                break;
            }
        }
        return builder.toString();
    }

    private String capitalise(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private String extractCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        int startFence = raw.indexOf("```");
        if (startFence < 0) {
            return raw;
        }
        int nextLine = raw.indexOf('\n', startFence);
        int endFence = raw.indexOf("```", nextLine + 1);
        if (nextLine > 0 && endFence > nextLine) {
            return raw.substring(nextLine + 1, endFence).trim();
        }
        return raw;
    }

    private String quote(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
