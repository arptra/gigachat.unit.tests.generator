package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds a skeleton prompt describing a single method that requires a generated test.
 */
public class SkeletonPromptBuilder {

    public String build(TestClassInfo classInfo, TestMethodInfo methodInfo) {
        Objects.requireNonNull(classInfo, "classInfo");
        Objects.requireNonNull(methodInfo, "methodInfo");
        List<String> imports = classInfo.getImports();
        String importsJson = imports.stream()
                .map(importEntry -> "\"" + escape(importEntry) + "\"")
                .collect(Collectors.joining(", "));
        return "{\n"
                + "  \"testClass\": \"" + escape(classInfo.getTestClassName()) + "\",\n"
                + "  \"originalClass\": \"" + escape(classInfo.getClassName()) + "\",\n"
                + "  \"methodSignature\": \"" + escape(methodInfo.getSignature()) + "\",\n"
                + "  \"context\": \"" + escape("Generate a focused unit test for the specified method") + "\",\n"
                + "  \"imports\": [" + importsJson + "]\n"
                + "}";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
