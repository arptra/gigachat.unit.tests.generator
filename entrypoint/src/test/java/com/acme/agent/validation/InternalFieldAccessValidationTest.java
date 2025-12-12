package com.acme.agent.validation;

import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.llm.LlmClient;
import com.gigachat.unit.tests.generator.pipeline.InitialGenerationStep;
import com.gigachat.unit.tests.generator.pipeline.InvalidLLMResponseException;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.DiffEngine;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.pipeline.helpers.PromptBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.SkeletonPromptBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.SnapshotStorage;
import com.gigachat.unit.tests.generator.pipeline.helpers.TestClassWriter;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze.AnalysisSummary;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze.TestTargetContext;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodMetadata;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticAnalysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InternalFieldAccessValidationTest {

    @TempDir
    Path tempDir;

    private InitialGenerationStep generationStep;
    private AnalysisSummary analysisSummary;
    private Method validator;

    @BeforeEach
    void setUp() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        PipelineLogger logger = new PipelineLogger(tempDir);
        TestClassWriter writer = new TestClassWriter(logger);
        SkeletonPromptBuilder skeletonPromptBuilder = new SkeletonPromptBuilder();
        Analyze analyze = new Analyze(registry);
        PromptBuilder promptBuilder = new PromptBuilder();
        LlmClient llmClient = (prompt, classInfo, methodInfo, plan) -> null;
        DiffEngine diffEngine = new DiffEngine(writer, logger);
        CompilerInvoker compilerInvoker = (projectRoot, testClassFile, methodName) -> new CompileResult(true, List.of(), "", "");
        ExecutionInvoker executionInvoker = (projectRoot, testClassFile, methodName) -> new ExecuteResult(true, List.of(), "", "");
        SnapshotStorage snapshotStorage = new SnapshotStorage(tempDir, logger);

        generationStep = new InitialGenerationStep(logger,
                writer,
                skeletonPromptBuilder,
                analyze,
                promptBuilder,
                llmClient,
                diffEngine,
                compilerInvoker,
                executionInvoker,
                snapshotStorage,
                registry);

        MethodAnalysisResult methodAnalysis = new MethodAnalysisResult(new MethodMetadata("method", "method()", "void"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                SemanticAnalysis.empty());

        analysisSummary = new AnalysisSummary(new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                methodAnalysis,
                "{}",
                Map.of(),
                new TestTargetContext("LibraryComponent", "component", true, false),
                false,
                List.of(),
                Set.of(),
                Set.of("configuration", "users"),
                Map.of(),
                Map.of("LibraryComponent", List.of("configuration()")),
                Set.of(),
                Set.of());

        validator = InitialGenerationStep.class.getDeclaredMethod("ensureNoInternalFieldAccess",
                String.class,
                AnalysisSummary.class);
        validator.setAccessible(true);
    }

    @Test
    void methodInvocationOnPublicApiDoesNotTriggerE103() {
        assertDoesNotThrow(() -> invokeValidator("component.configuration();"));
        assertDoesNotThrow(() -> invokeValidator("component.configuration().size();"));
    }

    @Test
    void directInternalFieldAccessStillFailsValidation() {
        assertThrows(InvalidLLMResponseException.class,
                () -> invokeValidator("component.configuration.put(\"mode\", \"test\");"));
        assertThrows(InvalidLLMResponseException.class,
                () -> invokeValidator("component.users.clear();"));
    }

    private void invokeValidator(String code) {
        try {
            validator.invoke(generationStep, code, analysisSummary);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(cause);
        } catch (IllegalAccessException exception) {
            throw new RuntimeException(exception);
        }
    }
}
