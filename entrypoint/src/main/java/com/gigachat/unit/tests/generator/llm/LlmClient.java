package com.gigachat.unit.tests.generator.llm;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;

/**
 * Abstraction for calling an LLM to generate test snippets.
 */
public interface LlmClient {

    GeneratedTestSnippet generateTestSnippet(String prompt,
                                             TestClassInfo classInfo,
                                             TestMethodInfo methodInfo,
                                             MockPlan plan);
}
