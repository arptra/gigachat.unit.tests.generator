package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(1, llmClient.callCount);
    }

    private static class CapturingLlmClient implements LlmClient {

        private final String responseJson;
        private String lastPrompt;

        CapturingLlmClient(String responseJson) {
            this.responseJson = responseJson;
        }

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        MockPlan plan) {
            this.lastPrompt = prompt;
            return new GeneratedTestSnippet(
                    classInfo.getTestClassName(),
                    methodInfo.getSignature(),
                    responseJson,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    ""
            );
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
        public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        MockPlan plan) {
            callCount++;
            String payload = responses.get(Math.min(index, responses.size() - 1));
            index++;
            return new GeneratedTestSnippet(
                    classInfo.getTestClassName(),
                    methodInfo.getSignature(),
                    payload,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    ""
            );
        }
    }
}
