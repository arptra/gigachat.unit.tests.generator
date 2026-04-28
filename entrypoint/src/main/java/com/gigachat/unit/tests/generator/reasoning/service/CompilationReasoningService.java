package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.llm.LlmClient;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;

import java.util.Objects;

public class CompilationReasoningService {
    private static final int MAX_ATTEMPTS = 3;

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
        return reasonAboutError(loopContext, ReasoningOptions.DEFAULT);
    }

    public ReasoningResponse reasonAboutError(ReasoningLoopContext loopContext,
                                              ReasoningOptions options) {
        ReasoningOptions effectiveOptions = options == null ? ReasoningOptions.DEFAULT : options;
        String basePrompt = appendPromptSuffix(promptBuilder.buildPrompt(loopContext), effectiveOptions.promptSuffix());
        String prompt = basePrompt;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            effectiveOptions.listener().beforeSend(attempt, prompt);
            String raw = llmClient.requestStructuredResponse(prompt);
            effectiveOptions.listener().afterReceive(attempt, prompt, raw);
            try {
                ReasoningResponse parsed = parser.parseStrict(raw);
                effectiveOptions.listener().afterParse(attempt, parsed);
                return parsed;
            } catch (RuntimeException exception) {
                effectiveOptions.listener().onParseFailure(attempt, raw, exception);
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }
                prompt = buildRetryPrompt(basePrompt, raw, exception.getMessage(), attempt + 1);
            }
        }
        ReasoningResponse response = new ReasoningResponse();
        response.setDecision(ToolActionType.STOP.name());
        return response;
    }

    private String appendPromptSuffix(String basePrompt, String promptSuffix) {
        if (promptSuffix == null || promptSuffix.isBlank()) {
            return basePrompt;
        }
        return basePrompt + System.lineSeparator() + System.lineSeparator() + promptSuffix.strip();
    }

    private String buildRetryPrompt(String basePrompt,
                                    String rawResponse,
                                    String failureReason,
                                    int nextAttempt) {
        return basePrompt
                + System.lineSeparator()
                + System.lineSeparator()
                + "Previous JSON response was invalid."
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
                + ": return only valid JSON matching the required schema.";
    }

    public record ReasoningOptions(String promptSuffix, AttemptListener listener) {
        public static final ReasoningOptions DEFAULT = new ReasoningOptions("", AttemptListener.NO_OP);

        public ReasoningOptions {
            listener = listener == null ? AttemptListener.NO_OP : listener;
            promptSuffix = promptSuffix == null ? "" : promptSuffix;
        }
    }

    public interface AttemptListener {
        AttemptListener NO_OP = new AttemptListener() {
        };

        default void beforeSend(int attempt, String prompt) {
        }

        default void afterReceive(int attempt, String prompt, String rawResponse) {
        }

        default void afterParse(int attempt, ReasoningResponse response) {
        }

        default void onParseFailure(int attempt, String rawResponse, RuntimeException exception) {
        }
    }
}
