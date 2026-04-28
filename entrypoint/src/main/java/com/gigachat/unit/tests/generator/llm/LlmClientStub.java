package com.gigachat.unit.tests.generator.llm;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;

import java.util.List;
import java.util.Locale;

/**
 * Predictable stub that emulates a real LLM response.
 */
public class LlmClientStub implements LlmClient {

    @Override
    public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                    TestClassInfo classInfo,
                                                    TestMethodInfo methodInfo,
                                                    MockPlan plan) {
        String methodName = deriveMethodName(methodInfo);
        String body = "@Test" + System.lineSeparator()
                + "    void " + methodName + "() {" + System.lineSeparator()
                + "        // TODO: replace stub with real assertions for " + methodInfo.getSignature() + System.lineSeparator()
                + "        org.junit.jupiter.api.Assertions.assertTrue(true);" + System.lineSeparator()
                + "    }";
        List<String> imports = List.of("org.junit.jupiter.api.Assertions", "org.junit.jupiter.api.Test");
        return new GeneratedTestSnippet(classInfo.getTestClassName(), methodName, body, imports);
    }

    @Override
    public String requestStructuredResponse(String prompt) {
        return """
                {"decision":"STOP","actions":[],"memory_updates":{}}
                """;
    }

    private String deriveMethodName(TestMethodInfo methodInfo) {
        String signature = methodInfo.getSignature();
        String simplified = signature.replaceAll("[^A-Za-z0-9]", " ").trim();
        String[] parts = simplified.split("\\s+");
        StringBuilder builder = new StringBuilder("should");
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            builder.append(capitalise(part));
            if (builder.length() > 40) {
                break;
            }
        }
        return builder.toString();
    }

    private String capitalise(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
}
