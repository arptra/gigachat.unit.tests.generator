package com.gigachat.unit.tests.generator.llm;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;

import java.nio.file.Path;
import java.util.List;

/**
 * Abstraction for calling an LLM to generate test snippets.
 */
public interface LlmClient {

    GeneratedTestSnippet generateTestSnippet(String prompt,
                                             TestClassInfo classInfo,
                                             TestMethodInfo methodInfo,
                                             MockPlan plan);

    default String requestStructuredResponse(String prompt) {
        TestClassInfo placeholderClass = new TestClassInfo(
                "ReasoningPlaceholder",
                "ReasoningPlaceholderTest",
                Path.of("."),
                List.of(),
                List.of()
        );
        TestMethodInfo placeholderMethod = new TestMethodInfo("reason()", "void", "");
        MockPlan mockPlan = new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of());
        GeneratedTestSnippet snippet = generateTestSnippet(prompt, placeholderClass, placeholderMethod, mockPlan);
        return snippet == null ? "" : snippet.methodBody();
    }
}
