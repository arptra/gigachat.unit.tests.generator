package com.gigachat.unit.tests.generator.reasoning.orchestrator;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.llm.LlmClient;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.reasoning.model.ProjectContextSummary;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.model.ToolAction;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionStep;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;
import com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder;
import com.gigachat.unit.tests.generator.reasoning.service.CompilationReasoningService;
import com.gigachat.unit.tests.generator.reasoning.service.ReasoningResponseParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompilationReasoningOrchestratorTest {

    @Test
    void shouldDelegateToService() {
        String raw = """
                {"reasoning":["a"],"action":{"type":"RECOMPILE","steps":[],"singleStep":{"type":"RECOMPILE","arguments":{}}}}
                """;
        LlmClient llmClient = new StubLlmClient(raw);
        CompilationReasoningService service = new CompilationReasoningService(
                llmClient,
                new CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser()
        );
        CompilationReasoningOrchestrator orchestrator = new CompilationReasoningOrchestrator(service);

        ReasoningResponse response = orchestrator.handle(new ReasoningLoopContext(new CompilationErrorInfo(), new ProjectContextSummary(), Map.of()));
        ReasoningResponse expected = new ReasoningResponse(
                List.of("a"),
                new ToolAction(ToolActionType.RECOMPILE, List.of(), new ToolActionStep(ToolActionType.RECOMPILE, Map.of()))
        );
        assertEquals(expected, response);
    }

    private static class StubLlmClient implements LlmClient {
        private final String payload;

        StubLlmClient(String payload) {
            this.payload = payload;
        }

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        MockPlan plan) {
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
