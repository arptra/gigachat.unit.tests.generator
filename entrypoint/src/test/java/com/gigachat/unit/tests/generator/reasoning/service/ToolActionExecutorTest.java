package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionStep;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolActionExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void searchSymbolStopsAtFirstMatchAndSkipsTests() throws IOException {
        Path mainDir = tempDir.resolve("src/main/java/example");
        Files.createDirectories(mainDir);
        Path testDir = tempDir.resolve("src/test/java/example");
        Files.createDirectories(testDir);

        Path mainFile = mainDir.resolve("Main.java");
        Files.writeString(mainFile, "class Main { void m() { TargetSymbol(); } }");
        Path anotherMain = mainDir.resolve("Secondary.java");
        Files.writeString(anotherMain, "class Secondary { void m() { /* no symbol here */ } }");
        Path testFile = testDir.resolve("MainTest.java");
        Files.writeString(testFile, "class MainTest { void t() { TargetSymbol(); } }");

        ToolActionExecutor executor = new ToolActionExecutor(
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(false, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "example.MainTest",
                "test"
        );

        ToolActionStep step = new ToolActionStep(ToolActionType.SEARCH_SYMBOL, Map.of("symbol", "Main"));

        Map<String, Object> info = executor.executeStep(step).getInformation();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) info.get("symbolSearchResults");
        Map<String, Object> result = results.get(0);
        assertEquals("FOUND_ONE", result.get("searchStatus"));
        assertEquals("PROJECT_SOURCE", result.get("source"));
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) result.get("candidates");
        assertEquals(1, candidates.size());
        assertEquals("Main", candidates.get(0));
        assertTrue(executor.symbolExistsInProjectOrClasspath("Main"));
        assertTrue(executor.symbolExistsInProjectOrClasspath("example.Main"));
    }

    @Test
    void symbolExistsInProjectOrClasspathReturnsFalseWhenAgentCannotResolveIt() throws IOException {
        Path mainDir = tempDir.resolve("src/main/java/example");
        Files.createDirectories(mainDir);
        Files.writeString(mainDir.resolve("Main.java"), "package example; class Main {}");
        Path testFile = tempDir.resolve("src/test/java/example/MainTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "class MainTest {}");

        ToolActionExecutor executor = new ToolActionExecutor(
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(false, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "example.MainTest",
                "test"
        );

        assertTrue(executor.symbolExistsInProjectOrClasspath("Main"));
        assertEquals(false, executor.symbolExistsInProjectOrClasspath("DefinitelyInventedSymbol"));
    }

    @Test
    void symbolExistsInProjectOrClasspathReturnsTrueForKnownStaticTestDslSymbols() throws IOException {
        Path testFile = tempDir.resolve("src/test/java/example/MainTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "class MainTest {}");

        ToolActionExecutor executor = new ToolActionExecutor(
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(false, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "example.MainTest",
                "test"
        );

        assertTrue(executor.symbolExistsInProjectOrClasspath("assertEquals"));
        assertTrue(executor.symbolExistsInProjectOrClasspath("verify"));
        assertTrue(executor.symbolExistsInProjectOrClasspath("refEq"));
    }

    @Test
    void readClassShouldAcceptFqcnArgumentAlias() throws IOException {
        Path mainDir = tempDir.resolve("src/main/java/com/example/app/feature");
        Files.createDirectories(mainDir);
        Path sourceFile = mainDir.resolve("HiddenFeature.java");
        Files.writeString(sourceFile, "package com.example.app.feature; class HiddenFeature {}");
        Path testFile = tempDir.resolve("src/test/java/com/example/app/feature/HiddenFeatureTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "package com.example.app.feature; class HiddenFeatureTest {}");

        ToolActionExecutor executor = new ToolActionExecutor(
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(false, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "com.example.app.feature.HiddenFeatureTest",
                "test"
        );

        ToolActionStep step = new ToolActionStep(ToolActionType.READ_CLASS, Map.of("fqcn", "com.example.app.feature.HiddenFeature"));

        Map<String, Object> info = executor.executeStep(step).getInformation();
        @SuppressWarnings("unchecked")
        Map<String, String> cache = (Map<String, String>) info.get("contextCacheUpdates");
        assertEquals(1, cache.size());
        assertTrue(cache.values().iterator().next().contains("HiddenFeature"));
    }

    @Test
    void readClassShouldResolveGeneratedTestClassFromTestSources() throws IOException {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                class UserServiceTest {
                }
                """);

        ToolActionExecutor executor = new ToolActionExecutor(
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(false, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "com.example.app.service.UserServiceTest",
                "test"
        );

        Map<String, Object> info = executor.executeStep(new ToolActionStep(
                ToolActionType.READ_CLASS,
                Map.of("fqn", "com.example.app.service.UserServiceTest"))).getInformation();

        @SuppressWarnings("unchecked")
        Map<String, String> cache = (Map<String, String>) info.get("contextCacheUpdates");
        assertEquals(1, cache.size());
        assertTrue(cache.values().iterator().next().contains("UserServiceTest"));
    }

    @Test
    void readClassShouldResolveNestedProjectTypeFromOwningSourceFile() throws IOException {
        Path sourceFile = tempDir.resolve("src/main/java/bd/Abonent.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package bd;

                public class Abonent {
                    public static class Ref {
                    }
                }
                """);
        Path testFile = tempDir.resolve("src/test/java/mtd/abonent/NEW_AUTOTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "package mtd.abonent; class NEW_AUTOTest {}");

        ToolActionExecutor executor = new ToolActionExecutor(
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(false, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "mtd.abonent.NEW_AUTOTest",
                "test"
        );

        Map<String, Object> info = executor.executeStep(new ToolActionStep(
                ToolActionType.READ_CLASS,
                Map.of("fqcn", "bd.Abonent.Ref"))).getInformation();

        @SuppressWarnings("unchecked")
        Map<String, String> cache = (Map<String, String>) info.get("contextCacheUpdates");
        assertEquals(1, cache.size());
        assertTrue(cache.keySet().iterator().next().endsWith("/src/main/java/bd/Abonent.java"));
        assertTrue(cache.values().iterator().next().contains("public static class Ref"));
    }

    @Test
    void readMethodShouldResolveSimpleProjectClassName() throws IOException {
        Path mainDir = tempDir.resolve("src/main/java/com/example/app/service");
        Files.createDirectories(mainDir);
        Path sourceFile = mainDir.resolve("CoverageGoalWorkflowService.java");
        Files.writeString(sourceFile, """
                package com.example.app.service;

                class CoverageGoalWorkflowService {
                    String classifySignal(int score, boolean priorityAccount) {
                        if (score >= 10 && priorityAccount) {
                            return "priority";
                        }
                        return "rejected";
                    }
                }
                """);
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/CoverageGoalWorkflowServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "package com.example.app.service; class CoverageGoalWorkflowServiceTest {}");

        ToolActionExecutor executor = new ToolActionExecutor(
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(false, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "com.example.app.service.CoverageGoalWorkflowServiceTest",
                "test"
        );

        ToolActionStep step = new ToolActionStep(
                ToolActionType.READ_METHOD,
                Map.of("className", "CoverageGoalWorkflowService", "methodName", "classifySignal"));

        Map<String, Object> info = executor.executeStep(step).getInformation();
        @SuppressWarnings("unchecked")
        Map<String, String> cache = (Map<String, String>) info.get("contextCacheUpdates");
        assertEquals(1, cache.size());
        assertTrue(cache.keySet().iterator().next().contains("#classifySignal"));
        assertTrue(cache.values().iterator().next().contains("String classifySignal"));
        assertTrue(cache.values().iterator().next().contains("return \"rejected\";"));
        assertTrue(cache.values().iterator().next().contains("return \"priority\";"));
    }

    @Test
    void runTestShouldExecuteWholeClassWhenRequestedTestClassMatchesCurrentFile() throws IOException {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/CoverageGoalWorkflowServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "package com.example.app.service; class CoverageGoalWorkflowServiceTest {}");
        java.util.concurrent.atomic.AtomicReference<String> executedMethod = new java.util.concurrent.atomic.AtomicReference<>();

        ToolActionExecutor executor = new ToolActionExecutor(
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(false, List.of(), "", "");
                    }
                },
                (projectRoot, targetTestFile, methodName) -> {
                    executedMethod.set(methodName);
                    return new com.gigachat.unit.tests.generator.execute.ExecuteResult(true, List.of(), "", "");
                },
                tempDir,
                testFile,
                "com.example.app.service.CoverageGoalWorkflowServiceTest",
                "currentGeneratedMethod"
        );

        executor.executeStep(new ToolActionStep(
                ToolActionType.RUN_TEST,
                Map.of("testClass", "CoverageGoalWorkflowServiceTest")));

        assertEquals("", executedMethod.get());
    }

    @Test
    void applyPatchShouldReportErrorWhenNoPersistentChangeWasMade() throws IOException {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/CoverageGoalWorkflowServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                class CoverageGoalWorkflowServiceTest {
                    void testPriority() {
                    }
                }
                """);

        ToolActionExecutor executor = new ToolActionExecutor(
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(false, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "com.example.app.service.CoverageGoalWorkflowServiceTest",
                "currentGeneratedMethod"
        );

        ActionExecutionResult result = executor.executeStep(new ToolActionStep(
                ToolActionType.APPLY_PATCH,
                Map.of(
                        "path", testFile.toString(),
                        "patch", "@@ -99,1 +99,1 @@ impossible context\n-void missing() {}\n+void stillMissing() {}"
                )));

        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) result.getInformation().get("errors");
        assertFalse(result.getPerformedActions().contains("APPLY_PATCH " + testFile));
        assertTrue(errors.get(0).contains("no persisted change"));
    }

    @Test
    void applyPatchShouldAcceptLiveExecutionRepairPatchWithFilePathAlias() throws IOException {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/CoverageGoalWorkflowServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.BeforeEach;
                import static org.mockito.Mockito.*;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                public class CoverageGoalWorkflowServiceTest {

                    private AuditTrailService auditTrailService;

                    private CoverageGoalWorkflowService service;

                    @BeforeEach
                    void setUp() {
                        auditTrailService = mock(AuditTrailService.class);
                        service = new CoverageGoalWorkflowService(auditTrailService);
                    }

                    @Test
                    void testPriorityClassification() {
                    // Arrange
                    int score = 10;
                    boolean priorityAccount = true;
                    // Act
                    String result = service.classifySignal(score, priorityAccount);
                    // Assert
                    assertEquals("priority", result);
                    verify(auditTrailService).recordEvent("priority-signal");
                    }

                    @Test
                    void testPriorityClassificationCoverageVariant2() {
                    // Arrange
                    int score = 10;
                    boolean priorityAccount = false;
                    // Act
                    String result = service.classifySignal(score, priorityAccount);
                    // Assert
                    assertEquals("priority", result);
                    verify(auditTrailService).recordEvent("priority-signal");
                    }
                }
                """);

        ToolActionExecutor executor = new ToolActionExecutor(
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(true, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "com.example.app.service.CoverageGoalWorkflowServiceTest",
                "testPriorityClassification"
        );

        ActionExecutionResult result = executor.executeStep(new ToolActionStep(
                ToolActionType.APPLY_PATCH,
                Map.of(
                        "filePath", testFile.toString(),
                        "patch", """
                                @@ -33,7 +33,7 @@ public class CoverageGoalWorkflowServiceTest {
                                         // Arrange
                                         int score = 10;
                                         boolean priorityAccount = false;
                                -        // Act
                                +        // Act (score >= 10 but priorityAccount is false, so result should be "standard")
                                         String result = service.classifySignal(score, priorityAccount);
                                         // Assert
                                -        assertEquals("priority", result);
                                +        assertEquals("standard", result);
                                         verify(auditTrailService).recordEvent("standard-signal");
                                     }
                                """
                )));

        String updated = Files.readString(testFile);
        assertTrue(result.getPerformedActions().stream().anyMatch(action -> action.contains("APPLY_PATCH")));
        assertTrue(updated.contains("assertEquals(\"standard\", result);"));
        assertTrue(updated.contains("recordEvent(\"standard-signal\")"));
    }

    @Test
    void alignImportShouldUseUniqueClasspathCandidateWhenProjectSourceHasNoMatch() throws IOException {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                class UserServiceTest {
                    private Optional<String> value;
                }
                """);

        ToolActionExecutor executor = new ToolActionExecutor(
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(false, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "com.example.app.service.UserServiceTest",
                "test"
        );

        ActionExecutionResult result = executor.alignImportWithUniqueProjectSymbol(testFile, "Optional");
        String updated = Files.readString(testFile);

        assertTrue(result.getPerformedActions().stream().anyMatch(action -> action.contains("java.util.Optional")));
        assertTrue(updated.contains("import java.util.Optional;"));
    }

    @Test
    void alignImportShouldResolveCommonJdkCollectionTypes() throws IOException {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/legacy/LegacyTelemetryTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.legacy;

                import java.util.List;

                class LegacyTelemetryTest {
                    void test() {
                        List<String> events = new ArrayList<>();
                    }
                }
                """);

        ToolActionExecutor executor = new ToolActionExecutor(
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(false, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "com.example.app.legacy.LegacyTelemetryTest",
                "test"
        );

        ActionExecutionResult result = executor.alignImportWithUniqueProjectSymbol(testFile, "ArrayList");
        String updated = Files.readString(testFile);

        assertTrue(executor.symbolExistsInProjectOrClasspath("ArrayList"));
        assertTrue(result.getPerformedActions().stream().anyMatch(action -> action.contains("java.util.ArrayList")));
        assertTrue(updated.contains("import java.util.ArrayList;"));
    }

    @Test
    void alignImportShouldPreferNestedProjectTypeWhenOwnerTypeIsAlreadyImported() throws IOException {
        Path abonentFile = tempDir.resolve("src/main/java/bd/Abonent.java");
        Files.createDirectories(abonentFile.getParent());
        Files.writeString(abonentFile, """
                package bd;

                public class Abonent {
                    public static class Ref {
                    }
                }
                """);
        Path objectFile = tempDir.resolve("src/main/java/cls/Object.java");
        Files.createDirectories(objectFile.getParent());
        Files.writeString(objectFile, """
                package cls;

                public class Object {
                    public static class Ref {
                    }
                }
                """);
        Path testFile = tempDir.resolve("src/test/java/mtd/abonent/NEW_AUTOTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package mtd.abonent;

                import bd.Abonent;

                class NEW_AUTOTest {
                    void test() {
                        Abonent abonent = new Abonent();
                        Ref ref = new Ref();
                    }
                }
                """);

        ToolActionExecutor executor = new ToolActionExecutor(
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(false, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "mtd.abonent.NEW_AUTOTest",
                "test"
        );

        ActionExecutionResult result = executor.alignImportWithUniqueProjectSymbol(testFile, "Ref");
        String updated = Files.readString(testFile);

        assertTrue(result.getPerformedActions().stream().anyMatch(action -> action.contains("bd.Abonent.Ref")));
        assertTrue(updated.contains("import bd.Abonent.Ref;"));
        assertFalse(updated.contains("import java.sql.Ref;"));
    }

    @Test
    void applyRecipeShouldRewriteMockLiteralsAndMatchers() throws IOException {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/ApplicationTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app;

                import static org.mockito.Mockito.*;

                class ApplicationTest {
                    void anotherTest() {
                        verify(auditTrailService, never()).recordEvent("Should stay untouched");
                        verify(auditTrailService, times(1)).countEvents();
                    }

                    void test() {
                        when(featureToggleService.isEnabled("HIDDEN_FEATURE")).thenReturn(true);
                        verify(auditTrailService).recordEvent("Hidden feature activated");
                        verify(auditTrailService, times(1)).countEvents();
                    }
                }
                """);

        ToolActionExecutor executor = new ToolActionExecutor(
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(false, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "com.example.app.ApplicationTest",
                "test"
        );
        executor.setAvailableRecipes(List.of(Map.of(
                "id", "ALIGN_HIDDENFEATURE_RUNTIME_CONTRACT_1",
                "operations", List.of(
                        Map.of(
                                "type", "replace_string_literal_argument",
                                "mock", "featureToggleService",
                                "method", "isEnabled",
                                "literal", "hidden"
                        ),
                        Map.of(
                                "type", "rewrite_verify_block_with_prefixes",
                                "mock", "auditTrailService",
                                "method", "recordEvent",
                                "prefixes", List.of(
                                        "Activated hidden feature with value ",
                                        "Recalibrated feature with factor "
                                )
                        )
                ),
                "requiredImports", List.of("static org.mockito.ArgumentMatchers.startsWith")
        )));

        executor.executeStep(new ToolActionStep(ToolActionType.APPLY_RECIPE, Map.of("recipeId", "ALIGN_HIDDENFEATURE_RUNTIME_CONTRACT_1")));

        String updated = Files.readString(testFile);
        assertTrue(updated.contains("isEnabled(\"hidden\")"));
        assertTrue(updated.contains("recordEvent(startsWith(\"Activated hidden feature with value \"))"));
        assertTrue(updated.contains("recordEvent(startsWith(\"Recalibrated feature with factor \"))"));
        assertTrue(updated.contains("verify(auditTrailService, times(1)).countEvents();"));
        assertTrue(updated.contains("verify(auditTrailService, never()).recordEvent(\"Should stay untouched\");"));
        assertTrue(updated.contains("import static org.mockito.ArgumentMatchers.startsWith;"));
    }

    @Test
    void applyRecipeShouldWrapActPhaseWithMockStaticForStaticVoidBlocker() throws IOException {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/InheritedStaticVoidWorkflowServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                class InheritedStaticVoidWorkflowServiceTest {
                    void testExecuteInheritedShadowUpgradeWhenUpgradeIsSuccessful() {
                        boolean result = service.executeInheritedShadowUpgrade(user, 123);
                        assertTrue(result);
                    }
                }
                """);

        ToolActionExecutor executor = new ToolActionExecutor(
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(false, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "com.example.app.service.InheritedStaticVoidWorkflowServiceTest",
                "testExecuteInheritedShadowUpgradeWhenUpgradeIsSuccessful"
        );
        executor.setAvailableRecipes(List.of(Map.of(
                "id", "MOCK_STATIC_VOID_BLOCKER_LEGACYCONNECTIONGATEWAY",
                "operations", List.of(Map.of(
                        "type", "wrap_act_with_static_void_mock",
                        "ownerClass", "LegacyConnectionGateway",
                        "staticMethod", "openRequiredChannel",
                        "stringLiteral", "legacy-shadow-db",
                        "sutMethod", "executeInheritedShadowUpgrade"
                )),
                "requiredImports", List.of(
                        "com.example.app.legacy.LegacyConnectionGateway",
                        "org.mockito.MockedStatic",
                        "static org.mockito.Mockito.mockStatic"
                )
        )));

        executor.executeStep(new ToolActionStep(ToolActionType.APPLY_RECIPE, Map.of("recipeId", "MOCK_STATIC_VOID_BLOCKER_LEGACYCONNECTIONGATEWAY")));

        String updated = Files.readString(testFile);
        assertTrue(updated.contains("import com.example.app.legacy.LegacyConnectionGateway;"));
        assertTrue(updated.contains("import org.mockito.MockedStatic;"));
        assertTrue(updated.contains("import static org.mockito.Mockito.mockStatic;"));
        assertTrue(updated.contains("boolean result;"));
        assertTrue(updated.contains("try (MockedStatic<LegacyConnectionGateway> legacyConnectionGatewayMock = mockStatic(LegacyConnectionGateway.class))"));
        assertTrue(updated.contains("legacyConnectionGatewayMock.when(() -> LegacyConnectionGateway.openRequiredChannel(\"legacy-shadow-db\"))"));
        assertTrue(updated.contains("result = service.executeInheritedShadowUpgrade(user, 123);"));
    }

    @Test
    void applyRecipeShouldPersistImportOnlyRecipes() throws IOException {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                class UserServiceTest {
                    private Optional<String> value;
                }
                """);

        ToolActionExecutor executor = new ToolActionExecutor(
                new SourceFileEditor(),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return new CompileResult(false, List.of(), "", "");
                    }
                },
                null,
                tempDir,
                testFile,
                "com.example.app.service.UserServiceTest",
                "test"
        );
        executor.setAvailableRecipes(List.of(Map.of(
                "id", "ADD_OPTIONAL_IMPORT",
                "operations", List.of(),
                "requiredImports", List.of("java.util.Optional")
        )));

        ActionExecutionResult result = executor.executeStep(new ToolActionStep(
                ToolActionType.APPLY_RECIPE,
                Map.of("recipeId", "ADD_OPTIONAL_IMPORT")));

        String updated = Files.readString(testFile);
        assertTrue(result.getPerformedActions().contains("APPLY_RECIPE ADD_OPTIONAL_IMPORT"));
        assertTrue(updated.contains("import java.util.Optional;"));
    }
}
