package com.gigachat.unit.tests.generator.reasoning.prompt;

import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.reasoning.model.ProjectContextSummary;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningMemory;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningStage;
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
        String prompt = builder.buildPrompt(new ReasoningLoopContext(errorInfo,
                summary,
                executionResult,
                null,
                memory,
                ReasoningStage.COMPILATION));

        assertTrue(prompt.contains("deterministic test-repair agent"));
        assertTrue(prompt.contains("STAGE: COMPILATION"));
        assertTrue(prompt.contains("Deterministic protocol"));
        assertTrue(prompt.contains("Resource-driven reasoning rules"));
        assertTrue(prompt.contains("Operate on generated tests only."));
        assertTrue(prompt.contains("Stage-specific resource directives"));
        assertTrue(prompt.contains("Allowed tool actions"));
        assertTrue(prompt.contains("RECOMPILE"));
        assertTrue(prompt.contains("Allowed decisions"));
        assertTrue(prompt.contains("decision"));
        assertTrue(prompt.contains("Failure-specific heuristics"));
        assertTrue(prompt.contains("SEARCH_SYMBOL or SHOW_IMPORTS before adding imports"));
        assertTrue(prompt.contains("Matched catalog patterns"));
        assertTrue(prompt.contains("C001_MISSING_IMPORT_OR_SYMBOL"));
        assertTrue(prompt.contains("executorActions: search_symbol"));
        assertTrue(prompt.contains("Failure info"));
        assertTrue(prompt.contains("cannot find symbol"));
        assertTrue(prompt.contains("src/test/java/com/example/TestClass.java"));
        assertTrue(prompt.contains("src/main/java"));
        assertTrue(prompt.contains("junit:junit:5.0"));
    }

    @Test
    void executionStageShouldExposeRunTestAction() {
        CompilationReasoningPromptBuilder builder = new CompilationReasoningPromptBuilder();
        String prompt = builder.buildPrompt(new ReasoningLoopContext(
                new CompilationErrorInfo(
                        "org.mockito.exceptions.misusing.NotAMockException: Argument passed to when() is not a mock!",
                        "assertion failed",
                        "fqcn",
                        "path",
                        null,
                        "org.mockito.exceptions.misusing.NotAMockException"
                ),
                new ProjectContextSummary(),
                new ActionExecutionResult(Map.of(
                        "mockContext", Map.of(
                                "reasoningHints", List.of("Inspect the collaborator before patching assertions.")
                        ))),
                null,
                new ReasoningMemory(),
                ReasoningStage.EXECUTION
        ));

        assertTrue(prompt.contains("STAGE: EXECUTION"));
        assertTrue(prompt.contains("RUN_TEST"));
        assertTrue(prompt.contains("NotAMockException"));
        assertTrue(prompt.contains("Inspect the collaborator before patching assertions."));
        assertTrue(prompt.contains("R001_NOT_A_MOCK"));
    }

    @Test
    void executionStageShouldExposeDeterministicRecipes() {
        CompilationReasoningPromptBuilder builder = new CompilationReasoningPromptBuilder();
        ReasoningMemory memory = new ReasoningMemory();
        memory.addForbiddenAction("APPLY_PATCH");
        String prompt = builder.buildPrompt(new ReasoningLoopContext(
                new CompilationErrorInfo(
                        "Wanted but not invoked",
                        "Wanted but not invoked",
                        "fqcn",
                        "path",
                        null,
                        null
                ),
                new ProjectContextSummary(),
                new ActionExecutionResult(Map.of(
                        "deterministicRepairRecipes", List.of(Map.of(
                                "id", "ALIGN_HIDDENFEATURE_RUNTIME_CONTRACT_1",
                                "summary", "Align constructor-created collaborator expectations.",
                                "operations", List.of(Map.of(
                                        "type", "replace_string_literal_argument",
                                        "mock", "featureToggleService",
                                        "method", "isEnabled",
                                        "literal", "hidden"
                                ))
                        ))
                )),
                null,
                memory,
                ReasoningStage.EXECUTION
        ));

        assertTrue(prompt.contains("Deterministic repair recipes"));
        assertTrue(prompt.contains("ALIGN_HIDDENFEATURE_RUNTIME_CONTRACT_1"));
        assertTrue(prompt.contains("APPLY_RECIPE"));
        assertTrue(prompt.contains("Use the exact recipeId from the supplied catalog."));
    }
}
