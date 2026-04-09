package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.llm.LlmClient;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;

import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

public class CompilationReasoningService {
    private static final int MAX_REASONING_ATTEMPTS = 3;

    private final LlmClient llmClient;
    private final CompilationReasoningPromptBuilder promptBuilder;
    private final ReasoningResponseParser parser;
    private final PipelineLogger logger;

    public CompilationReasoningService(LlmClient llmClient,
                                       CompilationReasoningPromptBuilder promptBuilder,
                                       ReasoningResponseParser parser) {
        this(llmClient, promptBuilder, parser, null);
    }

    public CompilationReasoningService(LlmClient llmClient,
                                       CompilationReasoningPromptBuilder promptBuilder,
                                       ReasoningResponseParser parser,
                                       PipelineLogger logger) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.logger = logger;
    }

    public ReasoningResponse reasonAboutError(ReasoningLoopContext loopContext) {
        String basePrompt = promptBuilder.buildPrompt(loopContext);
        TestClassInfo classInfo = new TestClassInfo(
                "ReasoningPlaceholder",
                "ReasoningPlaceholderTest",
                Paths.get("."),
                List.of(),
                List.of()
        );
        TestMethodInfo methodInfo = new TestMethodInfo("reason()", "void", "");
        MockPlan mockPlan = new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of());

        String prompt = basePrompt;
        for (int attempt = 1; attempt <= MAX_REASONING_ATTEMPTS; attempt++) {
            logInfo("[REASONING_LLM] Sending prompt attempt " + attempt + ":\n" + prompt);
            GeneratedTestSnippet snippet = llmClient.generateTestSnippet(prompt, classInfo, methodInfo, mockPlan);
            String raw = snippet == null ? "" : snippet.methodBody();
            logInfo("[REASONING_LLM] Raw response attempt " + attempt + ":\n" + raw);
            try {
                ReasoningResponse parsed = parser.parseStrict(raw);
                logInfo("[REASONING_LLM] Parsed decision attempt "
                        + attempt
                        + ": "
                        + parsed.getDecision()
                        + " actions="
                        + (parsed.getActions() == null ? 0 : parsed.getActions().size()));
                return parsed;
            } catch (RuntimeException exception) {
                logWarn("[REASONING_LLM] Failed to parse reasoning response on attempt "
                        + attempt
                        + ": "
                        + exception.getMessage());
                if (attempt == MAX_REASONING_ATTEMPTS) {
                    break;
                }
                prompt = buildRetryPrompt(basePrompt, raw, exception.getMessage(), attempt + 1);
            }
        }
        ReasoningResponse response = new ReasoningResponse();
        response.setDecision(ToolActionType.STOP.name());
        logWarn("[REASONING_LLM] Falling back to STOP after exhausting reasoning retries.");
        return response;
    }

    private String buildRetryPrompt(String basePrompt, String rawResponse, String failureReason, int nextAttempt) {
        return basePrompt
                + System.lineSeparator()
                + System.lineSeparator()
                + "Previous response was invalid for attempt "
                + (nextAttempt - 1)
                + "."
                + System.lineSeparator()
                + "Reason: "
                + failureReason
                + System.lineSeparator()
                + "Invalid response:"
                + System.lineSeparator()
                + rawResponse
                + System.lineSeparator()
                + "Retry attempt "
                + nextAttempt
                + ": return ONLY valid JSON matching the required schema. Do not include Java code fences, explanations, or prose.";
    }

    private void logInfo(String message) {
        if (logger != null) {
            logger.info(message);
        }
    }

    private void logWarn(String message) {
        if (logger != null) {
            logger.warn(message);
        }
    }
}
