package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Detects whether a generated test method already exists to avoid duplicate generation.
 */
public class ExistingTestDetector {

    private final TestGenerationRegistry registry;

    public ExistingTestDetector(Path projectRoot) {
        this(new TestGenerationRegistry(projectRoot));
    }

    public ExistingTestDetector(TestGenerationRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Returns {@code true} when the given test method already exists inside the resolved test class file.
     */
    public boolean isTestMethodPresent(TestClassInfo classInfo, TestMethodInfo methodInfo) {
        String methodSignature = extractMethodName(methodInfo.getSignature());
        if (methodSignature == null || methodSignature.isBlank()) {
            return false;
        }
        return registry.hasEntry(classInfo.resolveTestFile(), methodSignature);
    }

    public void recordSuccessfulTest(TestClassInfo classInfo, TestMethodInfo methodInfo) {
        String methodSignature = extractMethodName(methodInfo.getSignature());
        if (methodSignature == null || methodSignature.isBlank()) {
            return;
        }
        registry.record(classInfo.resolveTestFile(), methodSignature);
    }

    private String extractMethodName(String signature) {
        if (signature == null || signature.isBlank()) {
            return null;
        }
        int parenIndex = signature.indexOf('(');
        String candidate = parenIndex >= 0 ? signature.substring(0, parenIndex) : signature;
        candidate = candidate.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        if (candidate.contains(" ")) {
            String[] parts = candidate.split("\\s+");
            candidate = parts[parts.length - 1];
        }
        return candidate.isEmpty() ? null : candidate;
    }
}
