package com.acme.agent.validation;

import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.pipeline.InvalidLLMResponseException;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze.AnalysisSummary;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze.TestTargetContext;
import com.gigachat.unit.tests.generator.pipeline.helpers.validation.GeneratedSnippetValidator;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodMetadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InternalFieldAccessValidationTest {

    @TempDir
    Path tempDir;

    private GeneratedSnippetValidator validator;
    private AnalysisSummary analysisSummary;

    @BeforeEach
    void setUp() {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        PipelineLogger logger = new PipelineLogger(tempDir);
        Analyze analyze = new Analyze(registry);
        validator = new GeneratedSnippetValidator(logger, analyze, registry);

        MethodAnalysisResult methodAnalysis = new MethodAnalysisResult(new MethodMetadata("method", "method()", "void"),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        analysisSummary = createSummary(methodAnalysis, Set.of("configuration", "users"));
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

    @Test
    void importPathContainingInternalFieldNameDoesNotTriggerE103() {
        AnalysisSummary importSummary = createSummary(
                new MethodAnalysisResult(new MethodMetadata("method", "method()", "void"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                Set.of("repository"));

        assertDoesNotThrow(() -> validator.ensureNoInternalFieldAccess("""
                package com.example.app.service;

                import com.example.app.repository.UserRepository;

                class SampleTest {
                    private UserRepository repository;
                }
                """, importSummary));
    }

    private void invokeValidator(String code) {
        validator.ensureNoInternalFieldAccess(code, analysisSummary);
    }

    private AnalysisSummary createSummary(MethodAnalysisResult methodAnalysis, Set<String> internalFields) {
        return new AnalysisSummary(new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                methodAnalysis,
                "{}",
                Map.of(),
                new TestTargetContext("LibraryComponent", "component", true, false),
                false,
                List.of(),
                Set.of(),
                internalFields,
                Map.of(),
                Map.of("LibraryComponent", List.of("configuration()")),
                Set.of(),
                Set.of());
    }
}
