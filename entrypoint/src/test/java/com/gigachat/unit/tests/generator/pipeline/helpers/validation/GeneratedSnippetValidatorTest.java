package com.gigachat.unit.tests.generator.pipeline.helpers.validation;

import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.InvalidLLMResponseException;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeneratedSnippetValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRejectUnparseableGeneratedSourceAsRetryableValidationFailure() {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        Analyze analyze = new Analyze(registry);
        GeneratedSnippetValidator validator = new GeneratedSnippetValidator(
                new PipelineLogger(tempDir),
                analyze,
                registry);
        AgentConfig config = new AgentConfigBuilder()
                .projectPath(tempDir)
                .build();
        TestClassInfo classInfo = new TestClassInfo(
                "SampleService",
                "SampleServiceTest",
                tempDir.resolve("src/test/java/com/example/SampleServiceTest.java"),
                List.of(),
                List.of());
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "SampleServiceTest",
                "shouldRun",
                """
                        @Test
                        void shouldRun() {
                        """,
                List.of("import //Corrected import"));
        TestMethodInfo methodInfo = new TestMethodInfo("public void run()", "void", "{}");

        assertThrows(InvalidLLMResponseException.class, () ->
                validator.validateGeneratedSnippet(config,
                        classInfo,
                        snippet,
                        methodInfo,
                        null,
                        config.getPipelineModuleConfig()));
    }

    @Test
    void shouldAcceptMethodReferenceAsRealTargetInvocation() {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerConstructor("NEW_AUTO", "NEW_AUTO()");
        registry.registerMethod("NEW_AUTO", "void initialize()");
        Analyze analyze = new Analyze(registry);
        GeneratedSnippetValidator validator = new GeneratedSnippetValidator(
                new PipelineLogger(tempDir),
                analyze,
                registry);
        AgentConfig config = new AgentConfigBuilder()
                .projectPath(tempDir)
                .build();
        TestMethodInfo methodInfo = new TestMethodInfo("public void initialize()", "void", "{}");
        TestClassInfo classInfo = new TestClassInfo(
                "NEW_AUTO",
                "NEW_AUTOTest",
                tempDir.resolve("src/test/java/mtd/abonent/NEW_AUTOTest.java"),
                List.of(),
                List.of(methodInfo));
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                new MethodAnalysisResult(
                        new MethodMetadata("initialize", "public void initialize()", "void"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext("NEW_AUTO", "o", true, false),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of("NEW_AUTO", List.of(new ConstructorMetadata("NEW_AUTO()", List.of()))),
                Map.of("NEW_AUTO", List.of("void initialize()")),
                Set.of(),
                Set.of());
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "NEW_AUTOTest",
                "testInitialize",
                """
                        @Test
                        void testInitialize() {
                            NEW_AUTO o = new NEW_AUTO();

                            assertDoesNotThrow(o::initialize);
                        }
                        """,
                List.of());

        assertDoesNotThrow(() -> validator.validateGeneratedSnippet(config,
                classInfo,
                snippet,
                methodInfo,
                summary,
                config.getPipelineModuleConfig()));
    }
}
