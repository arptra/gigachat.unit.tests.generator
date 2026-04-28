package com.gigachat.unit.tests.generator.pipeline.helpers.validation;

import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.InvalidLLMResponseException;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

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
}
