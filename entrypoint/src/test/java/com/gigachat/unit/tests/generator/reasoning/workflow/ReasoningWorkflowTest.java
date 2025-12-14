package com.gigachat.unit.tests.generator.reasoning.workflow;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
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
import com.gigachat.unit.tests.generator.reasoning.orchestrator.CompilationReasoningOrchestrator;
import com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder;
import com.gigachat.unit.tests.generator.reasoning.service.CompilationReasoningService;
import com.gigachat.unit.tests.generator.reasoning.service.ReasoningResponseParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReasoningWorkflowTest {

    @Test
    void shouldDelegateToOrchestrator() {
        String payload = """
                {"reasoning":["step"],"action":{"type":"RECOMPILE","steps":[],"singleStep":{"type":"RECOMPILE","arguments":{}}}}
                """;
        CompilationReasoningService service = new CompilationReasoningService(
                new StubLlmClient(payload),
                new CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser()
        );
        ReasoningWorkflow workflow = new ReasoningWorkflow(new CompilationReasoningOrchestrator(service));

        ReasoningResponse actual = workflow.process(new ReasoningLoopContext(new CompilationErrorInfo(), new ProjectContextSummary(), ActionExecutionResult.empty()));
        ReasoningResponse expected = new ReasoningResponse(
                List.of("step"),
                new ToolAction(ToolActionType.RECOMPILE, List.of(), new ToolActionStep(ToolActionType.RECOMPILE, Map.of()))
        );
        assertEquals(expected, actual);
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
