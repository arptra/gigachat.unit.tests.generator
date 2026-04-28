package com.gigachat.unit.tests.generator.pipeline.helpers.generation;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.llm.LlmClient;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.pipeline.helpers.PromptBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.AutoCorrectionStage;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.GenerationValidationRetryBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.validation.GeneratedSnippetValidator;
import com.gigachat.unit.tests.generator.resources.GenerationPatternCatalog;
import com.gigachat.unit.tests.generator.resources.StateModelCatalog;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodMetadata;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationSnippetRequesterTest {

    @TempDir
    Path tempDir;

    @Test
    void importCorrectionRetryUsesChainedConstructorValidationReason() throws Exception {
        Path dateSource = tempDir.resolve("src/main/java/bd/Date.java");
        Files.createDirectories(dateSource.getParent());
        Files.writeString(dateSource, """
                package bd;

                public class Date {
                }
                """);

        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerConstructor("NEW_AUTO", "NEW_AUTO()");
        registry.registerMethod("NEW_AUTO", "void NEW_AUTO_VALIDATE(Varchar2 PLP$CLASS)");
        registry.registerMethodsIfAbsent("Varchar2");

        Analyze analyze = new Analyze(registry);
        PipelineLogger logger = new PipelineLogger(tempDir);
        CapturingLlmClient llmClient = new CapturingLlmClient();
        GeneratedSnippetValidator validator = new GeneratedSnippetValidator(logger, analyze, registry);
        GenerationValidationRetryBuilder retryBuilder = new GenerationValidationRetryBuilder(
                logger,
                new GenerationPatternCatalog(),
                new StateModelCatalog(),
                validator::buildTargetConstructionRetryConstraints);
        GenerationSnippetRequester requester = new GenerationSnippetRequester(
                logger,
                new PromptBuilder(),
                llmClient,
                new AutoCorrectionStage(),
                retryBuilder,
                validator);

        AgentConfig config = new AgentConfigBuilder()
                .projectPath(tempDir)
                .build();
        TestMethodInfo methodInfo = new TestMethodInfo(
                "public final void NEW_AUTO_VALIDATE(final Varchar2 PLP$CLASS)",
                "void",
                "{}");
        TestClassInfo classInfo = new TestClassInfo(
                "NEW_AUTO",
                "NEW_AUTOTest",
                tempDir.resolve("src/test/java/mtd/abonent/NEW_AUTOTest.java"),
                List.of(),
                List.of(methodInfo));
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                new MethodAnalysisResult(
                        new MethodMetadata("NEW_AUTO_VALIDATE",
                                "public final void NEW_AUTO_VALIDATE(final Varchar2 PLP$CLASS)",
                                "void"),
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
                Map.of(
                        "NEW_AUTO", List.of("void NEW_AUTO_VALIDATE(Varchar2 PLP$CLASS)"),
                        "Varchar2", List.of("Varchar2 of(String value)", "boolean isNull()")
                ),
                Set.of("Varchar2"),
                Set.of());

        requester.requestSnippet(config,
                classInfo,
                methodInfo,
                summary.mockPlan(),
                new JSONObject().put("goal", "Generate a NEW_AUTO_VALIDATE test"),
                summary,
                config.getPipelineModuleConfig(),
                false);

        assertTrue(llmClient.prompts.size() >= 2);
        String retryPrompt = llmClient.prompts.get(1);
        assertTrue(retryPrompt.contains("E104: Missing constructor metadata for Varchar2(\"ABONENT\")"));
        assertTrue(retryPrompt.contains("Do not call new Varchar2")
                || retryPrompt.contains("Do not call Varchar2("));
    }

    private static final class CapturingLlmClient implements LlmClient {
        private final List<String> prompts = new ArrayList<>();

        @Override
        public GeneratedTestSnippet generateTestSnippet(String prompt,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        MockPlan plan) {
            prompts.add(prompt);
            if (prompts.size() > 1) {
                return null;
            }
            String fullSource = """
                    import org.junit.jupiter.api.Test;
                    import rt.Date;

                    class NEW_AUTOTest {
                        @Test
                        void shouldValidateAbonent() {
                            NEW_AUTO o = new NEW_AUTO();
                            Varchar2 plpClass = new Varchar2("ABONENT");
                            o.NEW_AUTO_VALIDATE(plpClass);
                        }
                    }
                    """;
            return new GeneratedTestSnippet(
                    "NEW_AUTOTest",
                    "shouldValidateAbonent",
                    """
                            @Test
                            void shouldValidateAbonent() {
                                NEW_AUTO o = new NEW_AUTO();
                                Varchar2 plpClass = new Varchar2("ABONENT");
                                o.NEW_AUTO_VALIDATE(plpClass);
                            }
                            """,
                    List.of("import org.junit.jupiter.api.Test;", "import rt.Date;"),
                    List.of(),
                    List.of(),
                    List.of(),
                    fullSource);
        }
    }
}
