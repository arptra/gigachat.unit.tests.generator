package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.llm.LlmClient;
import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.reasoning.model.ProjectContextSummary;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningMemory;
import com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilationReasoningServiceTest {

    @Test
    void shouldCallLlmAndParseResponse() {
        String responseJson = """
                {
                  "decision": "APPLY_FIX",
                  "actions": [
                    {"type": "ADD_IMPORT", "target": "src/test/java/TestFile.java", "details": "org.junit.jupiter.api.Test"}
                  ],
                  "memory_updates": {}
                }
                """;
        CapturingLlmClient llmClient = new CapturingLlmClient(responseJson);
        CompilationReasoningService service = new CompilationReasoningService(
                llmClient,
                new CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser()
        );

        CompilationErrorInfo errorInfo = new CompilationErrorInfo("out", "msg", "fqcn", "path", 1, null);
        ProjectContextSummary summary = new ProjectContextSummary(List.of("src"), List.of("test"), List.of("dep"));

        ReasoningResponse response = service.reasonAboutError(new ReasoningLoopContext(errorInfo, summary, ActionExecutionResult.empty(), null, new ReasoningMemory()));

        assertEquals("APPLY_FIX", response.getDecision());
        assertEquals(1, response.getActions().size());
    }

    @Test
    void shouldRetryParsingWhenInitialResponseIsInvalid() {
        String validJson = """
                {
                  "decision": "STOP",
                  "actions": [],
                  "memory_updates": {}
                }
                """;
        RetryingLlmClient llmClient = new RetryingLlmClient(List.of("not-a-json", validJson));
        CompilationReasoningService service = new CompilationReasoningService(
                llmClient,
                new CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser()
        );

        CompilationErrorInfo errorInfo = new CompilationErrorInfo("out", "msg", "fqcn", "path", 1, null);
        ProjectContextSummary summary = new ProjectContextSummary(List.of("src"), List.of("test"), List.of("dep"));

        ReasoningResponse response = service.reasonAboutError(new ReasoningLoopContext(errorInfo, summary, ActionExecutionResult.empty(), null, new ReasoningMemory()));

        assertEquals("STOP", response.getDecision());
        assertEquals(2, llmClient.callCount);
    }

    @Test
    void shouldApplyPromptSuffixAndInvokeAttemptListener() {
        String responseJson = """
                {
                  "decision": "STOP",
                  "actions": [],
                  "memory_updates": {}
                }
                """;
        CapturingLlmClient llmClient = new CapturingLlmClient(responseJson);
        CompilationReasoningService service = new CompilationReasoningService(
                llmClient,
                new CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser()
        );
        AtomicInteger beforeSendCalls = new AtomicInteger();
        AtomicInteger afterParseCalls = new AtomicInteger();

        ReasoningResponse response = service.reasonAboutError(
                new ReasoningLoopContext(
                        new CompilationErrorInfo("out", "msg", "fqcn", "path", 1, null),
                        new ProjectContextSummary(List.of("src"), List.of("test"), List.of("dep")),
                        ActionExecutionResult.empty(),
                        null,
                        new ReasoningMemory()),
                new CompilationReasoningService.ReasoningOptions(
                        "Execution-only hard constraint:\n- Prefer APPLY_FIX before STOP.",
                        new CompilationReasoningService.AttemptListener() {
                            @Override
                            public void beforeSend(int attempt, String prompt) {
                                beforeSendCalls.incrementAndGet();
                            }

                            @Override
                            public void afterParse(int attempt, ReasoningResponse parsedResponse) {
                                afterParseCalls.incrementAndGet();
                            }
                        }));

        assertEquals("STOP", response.getDecision());
        assertTrue(llmClient.lastPrompt.contains("Execution-only hard constraint"));
        assertEquals(1, beforeSendCalls.get());
        assertEquals(1, afterParseCalls.get());
    }

    private static class CapturingLlmClient implements LlmClient {

        private final String responseJson;
        private String lastPrompt;

        CapturingLlmClient(String responseJson) {
            this.responseJson = responseJson;
        }

        @Override
        public String requestStructuredResponse(String prompt) {
            this.lastPrompt = prompt;
            return responseJson;
        }

        @Override
        public com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet generateTestSnippet(String prompt,
                                                                                              com.gigachat.unit.tests.generator.dto.TestClassInfo classInfo,
                                                                                              com.gigachat.unit.tests.generator.dto.TestMethodInfo methodInfo,
                                                                                              com.gigachat.unit.tests.generator.dto.MockPlan plan) {
            throw new UnsupportedOperationException("Not used in this test");
        }
    }

    private static class RetryingLlmClient implements LlmClient {

        private final List<String> responses;
        private int index;
        private int callCount;

        RetryingLlmClient(List<String> responses) {
            this.responses = responses;
        }

        @Override
        public String requestStructuredResponse(String prompt) {
            callCount++;
            String payload = responses.get(Math.min(index, responses.size() - 1));
            index++;
            return payload;
        }

        @Override
        public com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet generateTestSnippet(String prompt,
                                                                                              com.gigachat.unit.tests.generator.dto.TestClassInfo classInfo,
                                                                                              com.gigachat.unit.tests.generator.dto.TestMethodInfo methodInfo,
                                                                                              com.gigachat.unit.tests.generator.dto.MockPlan plan) {
            throw new UnsupportedOperationException("Not used in this test");
        }
    }
}
