package com.gigachat.unit.tests.generator.reasoning.prompt;

import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.reasoning.model.ProjectContextSummary;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningMemory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilationReasoningPromptBuilderTest {

    @Test
    void shouldEmbedInstructionsAndContext() {
        CompilationErrorInfo errorInfo = new CompilationErrorInfo(
                "Compilation failed",
                "cannot find symbol",
                "com.example.TestClass",
                "src/test/java/com/example/TestClass.java",
                42,
                "java.lang.Error"
        );
        ProjectContextSummary summary = new ProjectContextSummary(
                List.of("src/main/java"),
                List.of("src/test/java"),
                List.of("junit:junit:5.0")
        );

        CompilationReasoningPromptBuilder builder = new CompilationReasoningPromptBuilder();
        ActionExecutionResult executionResult = new ActionExecutionResult(Map.of("hint", "value"), List.of("ADD_DEPENDENCY junit"));
        ReasoningMemory memory = new ReasoningMemory();
        memory.addForbiddenAction("ADD_DEPENDENCY");
        String prompt = builder.buildPrompt(new ReasoningLoopContext(errorInfo, summary, executionResult, null, memory));

        assertTrue(prompt.contains("deterministic agent"));
        assertTrue(prompt.contains("Allowed tool actions"));
        assertTrue(prompt.contains("decision"));
        assertTrue(prompt.contains("Compilation error info"));
        assertTrue(prompt.contains("cannot find symbol"));
        assertTrue(prompt.contains("src/test/java/com/example/TestClass.java"));
        assertTrue(prompt.contains("src/main/java"));
        assertTrue(prompt.contains("junit:junit:5.0"));
    }
}
