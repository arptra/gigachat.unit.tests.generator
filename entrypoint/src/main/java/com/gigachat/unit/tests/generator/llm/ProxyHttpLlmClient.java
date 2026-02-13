package com.gigachat.unit.tests.generator.llm;

import chat.giga.client.GigaChatClient;
import chat.giga.model.BalanceResponse;
import chat.giga.model.ModelResponse;
import chat.giga.model.TokenCount;
import chat.giga.model.TokenCountRequest;
import chat.giga.model.completion.Choice;
import chat.giga.model.completion.ChoiceFinishReason;
import chat.giga.model.completion.ChoiceMessage;
import chat.giga.model.completion.CompletionRequest;
import chat.giga.model.completion.CompletionResponse;
import chat.giga.model.embedding.EmbeddingRequest;
import chat.giga.model.embedding.EmbeddingResponse;
import chat.giga.model.file.AvailableFilesResponse;
import chat.giga.model.file.FileDeletedResponse;
import chat.giga.model.file.FileResponse;
import chat.giga.model.file.UploadFileRequest;
import com.gigachat.unit.tests.generator.config.GigaChatClientConfig;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Proxy-backed client that keeps {@link BaseGigaChatLlmClient} flow unchanged,
 * but sends completion requests over plain HTTP without auth/certificates.
 */
public class ProxyHttpLlmClient extends BaseGigaChatLlmClient {
    private static final URI DEFAULT_PROXY_ENDPOINT = URI.create("http://localhost:8080/generate");

    private final URI endpoint;
    private final PipelineLogger logger;

    public ProxyHttpLlmClient(GigaChatClientConfig config, PipelineLogger logger) {
        super(config, logger);
        this.logger = Objects.requireNonNull(logger, "logger");
        this.endpoint = config == null
                ? DEFAULT_PROXY_ENDPOINT
                : config.endpointOptional().orElse(DEFAULT_PROXY_ENDPOINT);
    }

    @Override
    protected GigaChatClient createClient() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        return new GigaChatClient() {
            @Override
            public ModelResponse models() {
                throw unsupported("models");
            }

            @Override
            public CompletionResponse completions(CompletionRequest request, String sessionId) {
                String prompt = extractPrompt(request);
                String payload = "{\"prompt\":\"" + quote(prompt) + "\"}";
                HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                try {
                    HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("Proxy returned status " + response.statusCode());
                    }
                    String content = extractContent(response.body());
                    return CompletionResponse.builder()
                            .model(request == null ? null : request.model())
                            .object("chat.completion")
                            .choices(List.of(Choice.builder()
                                    .index(0)
                                    .finishReason(ChoiceFinishReason.STOP)
                                    .message(ChoiceMessage.builder()
                                            .content(content)
                                            .build())
                                    .build()))
                            .build();
                } catch (IOException exception) {
                    throw new IllegalStateException("Proxy HTTP client failed", exception);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Proxy HTTP client interrupted", exception);
                }
            }

            @Override
            public EmbeddingResponse embeddings(EmbeddingRequest request) {
                throw unsupported("embeddings");
            }

            @Override
            public FileResponse uploadFile(UploadFileRequest uploadFileRequest) {
                throw unsupported("uploadFile");
            }

            @Override
            public byte[] downloadFile(String fileId, String outputFilePath) {
                throw unsupported("downloadFile");
            }

            @Override
            public AvailableFilesResponse availableFileList() {
                throw unsupported("availableFileList");
            }

            @Override
            public FileResponse fileInfo(String fileId) {
                throw unsupported("fileInfo");
            }

            @Override
            public FileDeletedResponse deleteFile(String fileId) {
                throw unsupported("deleteFile");
            }

            @Override
            public List<TokenCount> tokensCount(TokenCountRequest tokenCountRequest) {
                throw unsupported("tokensCount");
            }

            @Override
            public BalanceResponse balance() {
                throw unsupported("balance");
            }

            private UnsupportedOperationException unsupported(String method) {
                return new UnsupportedOperationException("Proxy client supports only completions, method: " + method);
            }
        };
    }

    private String extractPrompt(CompletionRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return "";
        }
        return request.messages().stream()
                .filter(Objects::nonNull)
                .map(message -> message.content() == null ? "" : message.content())
                .reduce((first, second) -> second)
                .orElse("");
    }

    private String extractContent(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int startFence = trimmed.indexOf("```");
        if (startFence < 0) {
            return trimmed;
        }
        int nextLine = trimmed.indexOf('\n', startFence);
        int endFence = trimmed.indexOf("```", nextLine + 1);
        if (nextLine > 0 && endFence > nextLine) {
            return trimmed.substring(nextLine + 1, endFence).trim();
        }
        return trimmed;
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
