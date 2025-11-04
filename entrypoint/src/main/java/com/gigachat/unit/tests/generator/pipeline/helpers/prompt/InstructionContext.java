package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import com.gigachat.unit.tests.generator.config.PromptConfig;
import com.gigachat.unit.tests.generator.config.PromptMode;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;

import java.util.Map;
import java.util.Objects;

/**
 * Aggregates relevant information for composing prompt instructions.
 */
public record InstructionContext(TestClassInfo classInfo,
                                 TestMethodInfo methodInfo,
                                 MethodAnalysisResult analysis,
                                 MockPlan mockPlan,
                                 Map<String, String> verificationPolicy,
                                 PromptConfig promptConfig) {

    public InstructionContext {
        Objects.requireNonNull(classInfo, "classInfo");
        Objects.requireNonNull(methodInfo, "methodInfo");
        Objects.requireNonNull(analysis, "analysis");
        Objects.requireNonNull(mockPlan, "mockPlan");
        Objects.requireNonNull(promptConfig, "promptConfig");
        verificationPolicy = verificationPolicy == null ? Map.of() : Map.copyOf(verificationPolicy);
    }

    public boolean isPureFunction() {
        return mockPlan.shouldMock().isEmpty()
                && mockPlan.shouldNotMock().isEmpty()
                && analysis.staticUsages().isEmpty()
                && analysis.invocations().isEmpty();
    }

    public boolean isStaticMethod() {
        String signature = methodInfo.getSignature();
        return signature != null && signature.contains(" static ");
    }

    public boolean isRepositoryLike() {
        String className = classInfo.getClassName();
        return className.endsWith("Repository")
                || className.endsWith("Dao")
                || className.endsWith("Gateway")
                || className.contains("Repository");
    }

    public boolean isUtilityLike() {
        String className = classInfo.getClassName();
        return className.endsWith("Util")
                || className.endsWith("Utils")
                || isStaticMethod();
    }

    public boolean hasMocks() {
        return !mockPlan.shouldMock().isEmpty();
    }

    public boolean hasVerificationPolicy() {
        return !verificationPolicy.isEmpty();
    }

    public PromptMode mode() {
        return promptConfig.mode();
    }
}
