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
import com.gigachat.unit.tests.generator.reasoning.model.ToolAction;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionStep;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;
import com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilationReasoningServiceTest {

    @Test
    void shouldCallLlmAndParseResponse() {
        String responseJson = """
                {
                  "reasoning": ["inspect error", "apply fix"],
                  "action": {
                    "type": "COMPOSITE",
                    "steps": [
                      {"type": "SHOW_FILE", "arguments": {"path": "TestFile.java"}}
                    ],
                    "singleStep": {"type": "RECOMPILE", "arguments": {}}
                  }
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

        ReasoningResponse response = service.reasonAboutError(new ReasoningLoopContext(errorInfo, summary, ActionExecutionResult.empty()));

        assertEquals(List.of("inspect error", "apply fix"), response.getReasoning());
        ToolAction expected = new ToolAction(
                ToolActionType.COMPOSITE,
                List.of(new ToolActionStep(ToolActionType.SHOW_FILE, Map.of("path", "TestFile.java"))),
                new ToolActionStep(ToolActionType.RECOMPILE, Map.of())
        );
        assertEquals(expected, response.getAction());
        assertTrue(llmClient.lastPrompt.contains("Compilation error info"));
    }

    @Test
    void shouldRetryParsingWhenInitialResponseIsInvalid() {
        String validJson = """
                {
                  "reasoning": ["second attempt parsed"],
                  "action": {"type": "RECOMPILE", "singleStep": {"type": "RECOMPILE", "arguments": {}}}
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

        ReasoningResponse response = service.reasonAboutError(new ReasoningLoopContext(errorInfo, summary, ActionExecutionResult.empty()));

        assertEquals(List.of("second attempt parsed"), response.getReasoning());
        assertEquals(2, llmClient.callCount);
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
