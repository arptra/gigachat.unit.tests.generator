package com.gigachat.unit.tests.generator.pipeline.helpers.validation;

import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.config.AgentMode;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.github.javaparser.StaticJavaParser;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedSnippetApiUsageValidatorTest {

    @TempDir
    Path projectRoot;

    @Test
    void constructorAndMethodValidationUsesGradleClasspathMetadata() throws Exception {
        compileMainClass("rt/Varchar2.java", """
                package rt;

                public class Varchar2 {
                    public Varchar2(String value) {}
                    public String getValue() {
                        return "";
                    }
                }
                """);
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        GeneratedSnippetApiUsageValidator validator = new GeneratedSnippetApiUsageValidator(
                new PipelineLogger(projectRoot),
                new Analyze(registry),
                registry);
        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(projectRoot)
                .build();
        TestMethodInfo methodInfo = new TestMethodInfo("public void target()", "void", "");
        TestClassInfo classInfo = new TestClassInfo(
                "NEW_AUTO",
                "NEW_AUTOTest",
                testClassFile(),
                List.of(),
                List.of(methodInfo));

        assertDoesNotThrow(() -> validator.ensureMethodAndConstructorUsageIsValid(
                config,
                StaticJavaParser.parse("""
                        package mtd.abonent;

                        import rt.Varchar2;

                        class NEW_AUTOTest {
                            void testTarget() {
                                Varchar2 value = new Varchar2("ABONENT");
                                value.getValue();
                            }
                        }
                        """),
                analysisSummary(),
                classInfo,
                methodInfo));

        assertTrue(registry.constructorExists("Varchar2", 1));
        assertTrue(registry.methodExists("Varchar2", "getValue", 0));
    }

    private Analyze.AnalysisSummary analysisSummary() {
        return new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                new MethodAnalysisResult(
                        new MethodMetadata("target", "public void target()", "void"),
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
                Map.of(),
                Map.of(),
                Set.of(),
                Set.of());
    }

    private void compileMainClass(String relativePath, String source) throws Exception {
        Path sourceFile = projectRoot.resolve("compile-src").resolve(relativePath);
        Path classesDir = projectRoot.resolve("build/classes/java/main");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(classesDir);
        Files.writeString(sourceFile, source);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for this test");
        int result = compiler.run(null, null, null, "-d", classesDir.toString(), sourceFile.toString());
        assertTrue(result == 0, "helper class compilation failed");
    }

    private Path testClassFile() throws Exception {
        Path testClassFile = projectRoot.resolve("src/test/java/mtd/abonent/NEW_AUTOTest.java");
        Files.createDirectories(testClassFile.getParent());
        return testClassFile;
    }
}
