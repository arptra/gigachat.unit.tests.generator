package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.dto.ClassMetadata;
import com.gigachat.unit.tests.generator.dto.FieldMetadata;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.testagent.entrypoint.pipeline.helpers.analyze.AnalysisFormatter;
import com.testagent.entrypoint.pipeline.helpers.analyze.DependencyInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.InvocationInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalyzer;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodMetadata;
import com.testagent.entrypoint.pipeline.helpers.analyze.MockStrategyResolver;
import com.testagent.entrypoint.pipeline.helpers.analyze.MockType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AnalyzeCollaboratorInferenceTest {

    @Test
    void shouldKeepCollaboratorFieldNamesInVerificationPolicyAndMockPlan() {
        MethodAnalyzer methodAnalyzer = new MethodAnalyzer(null) {
            @Override
            public MethodAnalysisResult analyze(TestClassInfo classInfo,
                                                TestMethodInfo methodInfo,
                                                com.gigachat.unit.tests.generator.config.AgentConfig config,
                                                com.gigachat.unit.tests.generator.config.PipelineModuleConfig pipelineConfig) {
                return new MethodAnalysisResult(
                        new MethodMetadata("activate", "public boolean activate()", "boolean"),
                        List.of(),
                        List.of(
                                new InvocationInfo("featureToggleService", "isEnabled", List.of("String")),
                                new InvocationInfo("auditTrailService", "recordEvent", List.of("String"))
                        ),
                        List.of("MathUtil.sum"),
                        List.of()
                );
            }
        };
        Analyze analyze = new Analyze(
                methodAnalyzer,
                new AnalysisFormatter(),
                new MockStrategyResolver(),
                null,
                new com.gigachat.unit.tests.generator.analyzer.ExternalCollaboratorDetector(),
                new com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry()
        );

        TestClassInfo classInfo = new TestClassInfo(
                "HiddenFeature",
                "HiddenFeatureTest",
                Path.of("/tmp/HiddenFeatureTest.java"),
                List.of(),
                List.of(new TestMethodInfo("public boolean activate()", "boolean", "{ return false; }")),
                new ClassMetadata("HiddenFeature", List.of(
                        new FieldMetadata("featureToggleService", "FeatureToggleService", true),
                        new FieldMetadata("auditTrailService", "AuditTrailService", true)
                ))
        );

        Analyze.AnalysisSummary summary = analyze.analyze(null,
                classInfo,
                new TestMethodInfo("public boolean activate()", "boolean", "{ return false; }"));

        assertTrue(summary.mockPlan().shouldMock().contains("featureToggleService"));
        assertTrue(summary.mockPlan().shouldMock().contains("auditTrailService"));
        assertTrue(summary.verificationPolicy().containsKey("featureToggleService.isEnabled"));
        assertTrue(summary.verificationPolicy().containsKey("auditTrailService.recordEvent"));
        assertTrue(summary.verificationPolicy().keySet().stream().noneMatch(key -> key.startsWith("internal_state")));
    }

    @Test
    void shouldInferConstructorArgumentCollaboratorsAndSkipVerificationForConstructorLocalObject() {
        MethodAnalyzer methodAnalyzer = new MethodAnalyzer(null) {
            @Override
            public MethodAnalysisResult analyze(TestClassInfo classInfo,
                                                TestMethodInfo methodInfo,
                                                com.gigachat.unit.tests.generator.config.AgentConfig config,
                                                com.gigachat.unit.tests.generator.config.PipelineModuleConfig pipelineConfig) {
                return new MethodAnalysisResult(
                        new MethodMetadata("deployHiddenFeature", "public void deployHiddenFeature()", "void"),
                        List.of(
                                new DependencyInfo(
                                        "HiddenFeature",
                                        "hiddenFeature",
                                        MockType.CONSTRUCTOR,
                                        "new HiddenFeature(featureToggleService, auditTrailService)",
                                        false,
                                        true,
                                        2
                                )
                        ),
                        List.of(
                                new InvocationInfo("hiddenFeature", "activate", List.of()),
                                new InvocationInfo("hiddenFeature", "recalibrate", List.of())
                        ),
                        List.of(),
                        List.of()
                );
            }
        };

        Analyze analyze = new Analyze(
                methodAnalyzer,
                new AnalysisFormatter(),
                new MockStrategyResolver(),
                null,
                new com.gigachat.unit.tests.generator.analyzer.ExternalCollaboratorDetector(),
                new com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry()
        );

        TestClassInfo classInfo = new TestClassInfo(
                "Application",
                "ApplicationTest",
                Path.of("/tmp/ApplicationTest.java"),
                List.of(),
                List.of(new TestMethodInfo("public void deployHiddenFeature()", "void", "{ }")),
                new ClassMetadata("Application", List.of(
                        new FieldMetadata("featureToggleService", "FeatureToggleService", true),
                        new FieldMetadata("auditTrailService", "AuditTrailService", true)
                ))
        );

        Analyze.AnalysisSummary summary = analyze.analyze(null,
                classInfo,
                new TestMethodInfo("public void deployHiddenFeature()", "void", "{ }"));

        assertTrue(summary.mockPlan().shouldMock().contains("featureToggleService"));
        assertTrue(summary.mockPlan().shouldMock().contains("auditTrailService"));
        assertFalse(summary.verificationPolicy().containsKey("hiddenFeature.activate"));
        assertFalse(summary.verificationPolicy().containsKey("hiddenFeature.recalibrate"));
    }

    @Test
    void shouldExposeConstructorsAndMethodsForImportedTypeUsedByMethodReference() {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerConstructor("User", new ConstructorMetadata("User(String username, String email)", List.of()));
        registry.registerMethod("User", "boolean isActive()");
        registry.registerMethod("User", "String getUsername()");

        Analyze analyze = new Analyze(
                new MethodAnalyzer(null),
                new AnalysisFormatter(),
                new MockStrategyResolver(),
                null,
                new com.gigachat.unit.tests.generator.analyzer.ExternalCollaboratorDetector(),
                registry
        );

        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration("""
                public List<String> activeUsernames() {
                    return repository.findAll().stream()
                            .filter(User::isActive)
                            .map(User::getUsername)
                            .collect(Collectors.toList());
                }
                """);
        TestMethodInfo methodInfo = new TestMethodInfo(
                declaration.getDeclarationAsString(true, true, true),
                declaration.getType().asString(),
                declaration.getBody().orElseThrow().toString(),
                declaration);
        TestClassInfo classInfo = new TestClassInfo(
                "UserService",
                "UserServiceTest",
                Path.of("/tmp/UserServiceTest.java"),
                List.of(
                        "import com.example.app.model.User;",
                        "import java.util.List;",
                        "import java.util.stream.Collectors;"
                ),
                List.of(methodInfo),
                new ClassMetadata("UserService", List.of(
                        new FieldMetadata("repository", "UserRepository", true)
                ))
        );

        Analyze.AnalysisSummary summary = analyze.analyze(null, classInfo, methodInfo);

        assertTrue(summary.availableConstructors().containsKey("User"));
        assertTrue(summary.availableConstructors().get("User").stream()
                .anyMatch(constructor -> constructor.signature().equals("User(String username, String email)")));
        assertTrue(summary.availableMethods().getOrDefault("User", List.of()).contains("boolean isActive()"));
        assertTrue(summary.availableMethods().getOrDefault("User", List.of()).contains("String getUsername()"));
    }
}
