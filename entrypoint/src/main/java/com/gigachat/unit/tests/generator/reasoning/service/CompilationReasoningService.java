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
import java.util.Objects;

public class CompilationReasoningService {

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
        TestClassInfo classInfo = new TestClassInfo(
                "ReasoningPlaceholder",
                "ReasoningPlaceholderTest",
                Paths.get("."),
                List.of(),
                List.of()
        );
        TestMethodInfo methodInfo = new TestMethodInfo("reason()", "void", "");
        MockPlan mockPlan = new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of());

        GeneratedTestSnippet snippet = llmClient.generateTestSnippet(prompt, classInfo, methodInfo, mockPlan);
        String raw = snippet == null ? "" : snippet.methodBody();
        try {
            return parser.parse(raw);
        } catch (RuntimeException firstFailure) {
            GeneratedTestSnippet retrySnippet = llmClient.generateTestSnippet(prompt, classInfo, methodInfo, mockPlan);
            String retryRaw = retrySnippet == null ? "" : retrySnippet.methodBody();
            try {
                return parser.parse(retryRaw);
            } catch (RuntimeException ignored) {
                ReasoningResponse response = new ReasoningResponse();
                response.setDecision(ToolActionType.STOP.name());
                return response;
            }
        }
    }
}
