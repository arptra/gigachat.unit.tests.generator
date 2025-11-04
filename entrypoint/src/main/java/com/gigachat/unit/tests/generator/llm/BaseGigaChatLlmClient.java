package com.gigachat.unit.tests.generator.llm;

import chat.giga.client.GigaChatClient;
import chat.giga.http.client.HttpClientException;
import chat.giga.model.ModelName;
import chat.giga.model.completion.ChatMessage;
import chat.giga.model.completion.ChatMessageRole;
import chat.giga.model.completion.Choice;
import chat.giga.model.completion.CompletionRequest;
import chat.giga.model.completion.CompletionResponse;
import com.gigachat.unit.tests.generator.config.GigaChatClientConfig;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared infrastructure for LLM clients that delegate to the official GigaChat SDK.
 */
abstract class BaseGigaChatLlmClient implements LlmClient {
    private static final String SYSTEM_PROMPT = "You are an AI agent that generates Java JUnit 5 unit tests using Mockito.";

    private final PipelineLogger logger;
    private final GigaChatClientConfig config;
    private final LlmClient fallback;
    private volatile GigaChatClient client;

    protected BaseGigaChatLlmClient(GigaChatClientConfig config, PipelineLogger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.fallback = new LlmClientStub();
    }

    protected abstract GigaChatClient createClient() throws Exception;

    private GigaChatClient ensureClient() {
        GigaChatClient current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                try {
                    client = createClient();
                } catch (Exception exception) {
                    logger.error("Unable to initialise GigaChat client", exception);
                    client = null;
                }
            }
            return client;
        }
    }

    @Override
    public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                    TestClassInfo classInfo,
                                                    TestMethodInfo methodInfo,
                                                    MockPlan plan) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(classInfo, "classInfo");
        Objects.requireNonNull(methodInfo, "methodInfo");
        GigaChatClient delegate = ensureClient();
        if (delegate == null) {
            logger.warn("Falling back to stubbed LLM client due to initialisation failure.");
            return fallback.generateTestSnippet(prompt, classInfo, methodInfo, plan);
        }
        try {
            CompletionRequest request = CompletionRequest.builder()
                    .model(resolveModel())
                    .message(ChatMessage.builder()
                            .role(ChatMessageRole.SYSTEM)
                            .content(SYSTEM_PROMPT)
                            .build())
                    .message(ChatMessage.builder()
                            .role(ChatMessageRole.USER)
                            .content(prompt)
                            .build())
                    .build();
            CompletionResponse response = delegate.completions(request);
            String content = extractContent(response);
            Optional<GeneratedTestSnippet> snippet = mapContentToSnippet(content, classInfo);
            if (snippet.isPresent()) {
                return snippet.get();
            }
            logger.warn("Unable to parse GigaChat response for method " + methodInfo.getSignature() + "; using stub fallback.");
        } catch (HttpClientException exception) {
            logger.error("GigaChat request failed with status " + exception.statusCode() + ": " + exception.bodyAsString());
        } catch (Exception exception) {
            logger.error("Unexpected error while invoking GigaChat", exception);
        }
        return fallback.generateTestSnippet(prompt, classInfo, methodInfo, plan);
    }

    protected GigaChatClientConfig clientConfig() {
        return config;
    }

    private String resolveModel() {
        String configured = config.resolvedModelName();
        if (configured == null || configured.isBlank()) {
            return ModelName.GIGA_CHAT_MAX_2;
        }
        String candidate = findModelConstant(configured);
        if (candidate != null) {
            return candidate;
        }
        logger.warn("Unknown GigaChat model '" + configured + "', falling back to GIGA_CHAT_MAX_2.");
        return ModelName.GIGA_CHAT_MAX_2;
    }

    private String findModelConstant(String configured) {
        try {
            Field field = ModelName.class.getField(configured);
            Object value = field.get(null);
            if (value instanceof String string && !string.isBlank()) {
                return string;
            }
        } catch (NoSuchFieldException ignored) {
            // fall through to search by value
        } catch (IllegalAccessException exception) {
            logger.error("Unable to access GigaChat model constant '" + configured + "'", exception);
        }
        try {
            for (Field field : ModelName.class.getFields()) {
                if (field.getType() != String.class) {
                    continue;
                }
                Object value = field.get(null);
                if (value instanceof String string && !string.isBlank()) {
                    if (string.equalsIgnoreCase(configured) || field.getName().equalsIgnoreCase(configured)) {
                        return string;
                    }
                }
            }
        } catch (IllegalAccessException exception) {
            logger.error("Failed to inspect available GigaChat model constants", exception);
        }
        return null;
    }

    private String extractContent(CompletionResponse response) {
        if (response == null || response.choices() == null) {
            return null;
        }
        for (Choice choice : response.choices()) {
            if (choice == null || choice.message() == null) {
                continue;
            }
            String content = choice.message().content();
            if (content != null && !content.isBlank()) {
                return content;
            }
        }
        return null;
    }

    private Optional<GeneratedTestSnippet> mapContentToSnippet(String content, TestClassInfo classInfo) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        String prepared = prepareContent(content, classInfo.getTestClassName());
        if (prepared.isBlank()) {
            logger.warn("GigaChat response did not contain parsable Java code.");
            return Optional.empty();
        }
        JavaParser parser = new JavaParser();
        ParseResult<CompilationUnit> result = parser.parse(prepared);
        if (result.getResult().isEmpty()) {
            return Optional.empty();
        }
        CompilationUnit unit = result.getResult().get();
        ClassOrInterfaceDeclaration classDeclaration = unit.findFirst(ClassOrInterfaceDeclaration.class,
                declaration -> declaration.getNameAsString().equals(classInfo.getTestClassName()))
                .orElse(unit.findFirst(ClassOrInterfaceDeclaration.class).orElse(null));
        if (classDeclaration == null) {
            return Optional.empty();
        }
        MethodDeclaration method = locateTestMethod(classDeclaration);
        if (method == null) {
            return Optional.empty();
        }
        MethodDeclaration copy = method.clone();
        if (copy.getAnnotations().stream().noneMatch(annotation -> annotation.getNameAsString().equals("Test"))) {
            copy.addAnnotation("Test");
        }
        String methodSource = normaliseLineEndings(copy.toString());
        List<String> imports = new ArrayList<>();
        for (ImportDeclaration declaration : unit.getImports()) {
            String line = declaration.toString().trim();
            if (!line.isEmpty()) {
                imports.add(line);
            }
        }
        return Optional.of(new GeneratedTestSnippet(classInfo.getTestClassName(),
                copy.getNameAsString(),
                methodSource,
                imports));
    }

    private String prepareContent(String content, String expectedClassName) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String withoutFence = stripCodeFences(trimmed);
        String normalised = withoutFence.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalised.isEmpty()) {
            return "";
        }
        int anchor = findSourceAnchor(normalised, expectedClassName);
        String candidate = anchor >= 0 ? normalised.substring(anchor) : normalised;
        return candidate.trim();
    }

    private String stripCodeFences(String content) {
        String value = content;
        int firstFence = value.indexOf("```");
        if (firstFence >= 0) {
            if (firstFence > 0) {
                value = value.substring(firstFence);
            }
            int firstLineBreak = value.indexOf('\n');
            if (firstLineBreak >= 0) {
                value = value.substring(firstLineBreak + 1);
            } else {
                value = value.substring(3);
            }
            int closingFence = value.lastIndexOf("```");
            if (closingFence >= 0) {
                value = value.substring(0, closingFence);
            }
        }
        if (value.startsWith("java\n")) {
            value = value.substring("java\n".length());
        }
        if (value.endsWith("```")) {
            value = value.substring(0, value.length() - 3);
        }
        return value;
    }

    private int findSourceAnchor(String content, String expectedClassName) {
        int packageIndex = content.indexOf("package ");
        if (packageIndex >= 0) {
            return packageIndex;
        }
        int importIndex = content.indexOf("import ");
        if (importIndex >= 0) {
            return importIndex;
        }
        int annotationIndex = content.indexOf("@");
        String publicClass = "public class " + expectedClassName;
        int classIndex = content.indexOf(publicClass);
        if (classIndex >= 0) {
            if (annotationIndex >= 0 && annotationIndex < classIndex) {
                return annotationIndex;
            }
            return classIndex;
        }
        int genericClassIndex = content.indexOf("class ");
        if (genericClassIndex >= 0 && annotationIndex >= 0 && annotationIndex < genericClassIndex) {
            return annotationIndex;
        }
        return genericClassIndex;
    }

    private MethodDeclaration locateTestMethod(ClassOrInterfaceDeclaration declaration) {
        return declaration.getMethods().stream()
                .filter(method -> method.getAnnotations().stream()
                        .anyMatch(annotation -> annotation.getNameAsString().equals("Test")))
                .findFirst()
                .orElseGet(() -> declaration.getMethods().stream().findFirst().orElse(null));
    }

    private String normaliseLineEndings(String source) {
        String unix = source.replace("\r\n", "\n").replace("\r", "\n");
        String lineSeparator = System.lineSeparator();
        if ("\n".equals(lineSeparator)) {
            return unix;
        }
        return unix.replace("\n", lineSeparator);
    }
}
