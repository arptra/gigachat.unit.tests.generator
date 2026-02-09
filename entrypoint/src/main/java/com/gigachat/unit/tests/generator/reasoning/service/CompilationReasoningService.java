package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.llm.LlmClient;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;

import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public class CompilationReasoningService {

    private static final Pattern EXPLICIT_STOP_PATTERN = Pattern.compile("\"decision\"\\s*:\\s*\"stop\"", Pattern.CASE_INSENSITIVE);

    private final LlmClient llmClient;
    private final CompilationReasoningPromptBuilder promptBuilder;
    private final ReasoningResponseParser parser;

    public CompilationReasoningService(LlmClient llmClient,
                                       CompilationReasoningPromptBuilder promptBuilder,
                                       ReasoningResponseParser parser) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public ReasoningResponse reasonAboutError(ReasoningLoopContext loopContext) {
        String prompt = promptBuilder.buildPrompt(loopContext);
        TestClassInfo classInfo = placeholderClassInfo();
        TestMethodInfo methodInfo = placeholderMethodInfo();
        MockPlan mockPlan = placeholderPlan();

        String raw = callModel(prompt, classInfo, methodInfo, mockPlan);
        ReasoningResponse firstAttempt = parser.parse(raw);
        if (!shouldRetry(raw, firstAttempt)) {
            return firstAttempt;
        }

        String retryRaw = callModel(prompt, classInfo, methodInfo, mockPlan);
        ReasoningResponse secondAttempt = parser.parse(retryRaw);
        if (!shouldRetry(retryRaw, secondAttempt)) {
            return secondAttempt;
        }
        return stopResponse();
    }

    private String callModel(String prompt, TestClassInfo classInfo, TestMethodInfo methodInfo, MockPlan mockPlan) {
        GeneratedTestSnippet snippet = llmClient.generateTestSnippet(prompt, classInfo, methodInfo, mockPlan);
        return snippet == null ? "" : snippet.methodBody();
    }

    private boolean shouldRetry(String rawResponse, ReasoningResponse response) {
        if (response == null) {
            return true;
        }
        String decision = response.getDecision() == null ? "" : response.getDecision().toUpperCase(Locale.ROOT);
        if (decision.equals(ToolActionType.STOP.name())) {
            return !isExplicitStop(rawResponse);
        }
        if (decision.equals("REQUEST_CONTEXT") || decision.equals("APPLY_FIX")) {
            return response.toToolAction() == null;
        }
        return false;
    }

    private boolean isExplicitStop(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return false;
        }
        return EXPLICIT_STOP_PATTERN.matcher(rawResponse).find();
    }

    private ReasoningResponse stopResponse() {
        ReasoningResponse response = new ReasoningResponse();
        response.setDecision(ToolActionType.STOP.name());
        return response;
    }

    private TestClassInfo placeholderClassInfo() {
        return new TestClassInfo(
                "ReasoningPlaceholder",
                "ReasoningPlaceholderTest",
                Paths.get("."),
                List.of(),
                List.of()
        );
    }

    private TestMethodInfo placeholderMethodInfo() {
        return new TestMethodInfo("reason()", "void", "");
    }

    private MockPlan placeholderPlan() {
        return new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of());
    }
}
