package com.gigachat.unit.tests.generator.reasoning.orchestrator;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.coverage.CoverageResult;
import com.gigachat.unit.tests.generator.coverage.CoverageSummary;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.llm.LlmClient;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.service.CompilationReasoningService;
import com.gigachat.unit.tests.generator.reasoning.service.ReasoningResponseParser;
import com.gigachat.unit.tests.generator.reasoning.service.ProjectContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.ToolActionExecutor;
import com.gigachat.unit.tests.generator.reasoning.service.action.ProjectModificationAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoveragePipelineOrchestratorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReportCoverageSuccessWhenMethodIsFullyCovered() throws Exception {
        Path testFile = createTestFile();
        CoveragePipelineOrchestrator orchestrator = new CoveragePipelineOrchestrator(
                new PipelineLogger(tempDir),
                successCompiler(),
                successExecutor(),
                stopReasoningService());

        CoverageResult coverageResult = new CoverageResult(
                true,
                true,
                new CoverageSummary("NotificationService", "sendWelcome", 2, 0, 0, 0),
                tempDir.resolve("jacoco.xml"),
                "",
                "");

        CoveragePipelineOrchestrator.CoverageStageResult result = orchestrator.run(
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError",
                List.of(100),
                () -> coverageResult,
                noOpExecutor(testFile),
                new ProjectContextCollector(tempDir),
                noOpFixingOrchestrator(testFile),
                null);

        assertTrue(result.success());
        assertNotNull(result.coverageResult());
        assertTrue(result.coverageResult().fullyCovered());
    }

    @Test
    void shouldApplyCoverageFixAndAdvanceAcrossConfiguredGoals() throws Exception {
        Path testFile = createTestFile();
        AtomicInteger patchCount = new AtomicInteger();
        AtomicInteger reasoningCalls = new AtomicInteger();
        CompilationReasoningService reasoningService = new CompilationReasoningService(
                new LlmClient() {
                    @Override
                    public String requestStructuredResponse(String prompt) {
                        reasoningCalls.incrementAndGet();
                        return """
                                {
                                  "decision": "APPLY_FIX",
                                  "actions": [
                                    { "type": "APPLY_PATCH", "args": { "path": "%s", "patch": "@@\\n-assertTrue(false);\\n+assertTrue(true);\\n" } }
                                  ],
                                  "memory_updates": {}
                                }
                                """.formatted(testFile.toString().replace("\\", "\\\\"));
                    }

                    @Override
                    public com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet generateTestSnippet(String prompt,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestClassInfo classInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestMethodInfo methodInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.MockPlan plan) {
                        throw new UnsupportedOperationException();
                    }
                },
                new com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser());

        CoveragePipelineOrchestrator orchestrator = new CoveragePipelineOrchestrator(
                new PipelineLogger(tempDir),
                successCompiler(),
                successExecutor(),
                reasoningService);

        CoveragePipelineOrchestrator.CoverageStageResult result = orchestrator.run(
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError",
                List.of(40, 60, 80),
                () -> coverageResultForPatchCount(patchCount.get()),
                patchCountingExecutor(testFile, patchCount),
                new ProjectContextCollector(tempDir),
                noOpFixingOrchestrator(testFile),
                null);

        assertTrue(result.success());
        assertTrue(patchCount.get() >= 3, "Expected coverage loop to apply patches until the 80% goal");
        assertTrue(reasoningCalls.get() >= 3, "Expected reasoning service to be used across multiple coverage goals");
        assertTrue(result.coverageResult().meetsGoal(80));
    }

    @Test
    void shouldFailWhenCoverageFixBreaksCompilation() throws Exception {
        Path testFile = createTestFile();
        AtomicBoolean patched = new AtomicBoolean(false);
        CoveragePipelineOrchestrator orchestrator = new CoveragePipelineOrchestrator(
                new PipelineLogger(tempDir),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        return patched.get()
                                ? new CompileResult(false, List.of("broken compile"), "", "cannot compile")
                                : new CompileResult(true, List.of(), "", "");
                    }
                },
                successExecutor(),
                applyFixReasoningService(testFile));

        CoveragePipelineOrchestrator.CoverageStageResult result = orchestrator.run(
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError",
                List.of(80),
                () -> new CoverageResult(true, true, new CoverageSummary("NotificationService", "sendWelcome", 2, 2, 0, 1), tempDir.resolve("jacoco.xml"), "", ""),
                patchAwareExecutor(testFile, patched),
                new ProjectContextCollector(tempDir),
                compileFailingFixingOrchestrator(testFile, patched),
                null);

        assertFalse(result.success());
        assertTrue(result.coverageResult().describeFailure().contains("Coverage fix broke compilation"));
    }

    @Test
    void shouldResumeCoverageLoopAfterRequestingContext() throws Exception {
        Path testFile = createTestFile();
        AtomicInteger llmCalls = new AtomicInteger();
        AtomicInteger patchCount = new AtomicInteger();
        CompilationReasoningService reasoningService = new CompilationReasoningService(
                new LlmClient() {
                    @Override
                    public String requestStructuredResponse(String prompt) {
                        if (llmCalls.getAndIncrement() == 0) {
                            return """
                                    {
                                      "decision": "REQUEST_CONTEXT",
                                      "actions": [
                                        { "type": "READ_METHOD", "args": { "className": "CoverageGoalWorkflowService", "methodName": "classifySignal" } }
                                      ],
                                      "memory_updates": {}
                                    }
                                    """;
                        }
                        return """
                                {
                                  "decision": "APPLY_FIX",
                                  "actions": [
                                    { "type": "APPLY_PATCH", "args": { "path": "%s", "patch": "@@\\n-assertTrue(false);\\n+assertTrue(true);\\n" } }
                                  ],
                                  "memory_updates": {}
                                }
                                """.formatted(testFile.toString().replace("\\", "\\\\"));
                    }

                    @Override
                    public com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet generateTestSnippet(String prompt,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestClassInfo classInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestMethodInfo methodInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.MockPlan plan) {
                        throw new UnsupportedOperationException();
                    }
                },
                new com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser());

        CoveragePipelineOrchestrator orchestrator = new CoveragePipelineOrchestrator(
                new PipelineLogger(tempDir),
                successCompiler(),
                successExecutor(),
                reasoningService);

        CoveragePipelineOrchestrator.CoverageStageResult result = orchestrator.run(
                tempDir,
                testFile,
                "com.example.CoverageGoalWorkflowServiceTest",
                "classifySignal_returnsPriority",
                List.of(60),
                () -> patchCount.get() >= 1
                        ? new CoverageResult(true, true, new CoverageSummary("CoverageGoalWorkflowService", "classifySignal", 6, 2, 3, 1), tempDir.resolve("jacoco.xml"), "", "")
                        : new CoverageResult(true, true, new CoverageSummary("CoverageGoalWorkflowService", "classifySignal", 3, 5, 2, 4), tempDir.resolve("jacoco.xml"), "", ""),
                contextThenPatchExecutor(testFile, patchCount),
                new ProjectContextCollector(tempDir),
                noOpFixingOrchestrator(testFile),
                null);

        assertTrue(result.success());
        assertTrue(llmCalls.get() >= 2, "Expected a context round and a fix round");
        assertTrue(patchCount.get() >= 1, "Expected coverage patch after context collection");
        assertTrue(result.coverageResult().meetsGoal(60));
    }

    @Test
    void shouldIncludeCurrentGeneratedTestBaselineInCoveragePrompt() throws Exception {
        Path testFile = createTestFile();
        AtomicReference<String> promptRef = new AtomicReference<>();
        CompilationReasoningService reasoningService = new CompilationReasoningService(
                new LlmClient() {
                    @Override
                    public String requestStructuredResponse(String prompt) {
                        promptRef.set(prompt);
                        return """
                                {
                                  "decision": "STOP",
                                  "actions": [],
                                  "memory_updates": {}
                                }
                                """;
                    }

                    @Override
                    public com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet generateTestSnippet(String prompt,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestClassInfo classInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestMethodInfo methodInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.MockPlan plan) {
                        throw new UnsupportedOperationException();
                    }
                },
                new com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser());

        CoveragePipelineOrchestrator orchestrator = new CoveragePipelineOrchestrator(
                new PipelineLogger(tempDir),
                successCompiler(),
                successExecutor(),
                reasoningService);

        CoveragePipelineOrchestrator.CoverageStageResult result = orchestrator.run(
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError",
                List.of(60),
                () -> new CoverageResult(true, true, new CoverageSummary("NotificationService", "sendWelcome", 1, 3, 0, 0), tempDir.resolve("jacoco.xml"), "", ""),
                noOpExecutor(testFile),
                new ProjectContextCollector(tempDir),
                noOpFixingOrchestrator(testFile),
                null);

        assertFalse(result.success());
        assertNotNull(promptRef.get());
        assertTrue(promptRef.get().contains("coverageBaseline"));
        assertTrue(promptRef.get().contains("shouldSendWelcomeEmailWithoutError"));
        assertTrue(promptRef.get().contains("assertTrue(false);"));
    }

    @Test
    void shouldReturnToExecutionRepairWhenCoverageFixBreaksRuntime() throws Exception {
        Path testFile = createTestFile();
        AtomicBoolean patched = new AtomicBoolean(false);
        AtomicBoolean runtimeRepaired = new AtomicBoolean(false);
        AtomicInteger repairCalls = new AtomicInteger();

        CoveragePipelineOrchestrator orchestrator = new CoveragePipelineOrchestrator(
                new PipelineLogger(tempDir),
                successCompiler(),
                (projectRoot, testClassFile, methodName) -> {
                    if (patched.get() && !runtimeRepaired.get()) {
                        return new ExecuteResult(false,
                                List.of("shouldSendWelcomeEmailWithoutError"),
                                "",
                                "AssertionFailedError: expected: <true> but was: <false>");
                    }
                    return new ExecuteResult(true, List.of(), "", "");
                },
                applyFixReasoningService(testFile));

        CoveragePipelineOrchestrator.CoverageStageResult result = orchestrator.run(
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError",
                List.of(60),
                () -> runtimeRepaired.get()
                        ? new CoverageResult(true, true, new CoverageSummary("NotificationService", "sendWelcome", 3, 0, 1, 0), tempDir.resolve("jacoco.xml"), "", "")
                        : new CoverageResult(true, true, new CoverageSummary("NotificationService", "sendWelcome", 1, 2, 0, 1), tempDir.resolve("jacoco.xml"), "", ""),
                patchAwareExecutor(testFile, patched),
                new ProjectContextCollector(tempDir),
                noOpFixingOrchestrator(testFile),
                (compileResult, executeResult) -> {
                    repairCalls.incrementAndGet();
                    runtimeRepaired.set(true);
                    return new CoveragePipelineOrchestrator.RuntimeRegressionResult(
                            true,
                            compileResult,
                            new ExecuteResult(true, List.of(), "", ""),
                            new ActionExecutionResult(Map.of(), List.of("APPLY_RECIPE")));
                });

        assertTrue(result.success());
        assertTrue(runtimeRepaired.get(), "Expected execution repair to be invoked after runtime regression");
        assertEquals(1, repairCalls.get(), "Expected exactly one runtime repair handoff");
        assertTrue(result.coverageResult().meetsGoal(60));
    }

    @Test
    void shouldAllowSecondCoverageReasoningRoundAfterRuntimeRepairWithSameCoverageSignature() throws Exception {
        Path testFile = createTestFile();
        AtomicInteger llmCalls = new AtomicInteger();
        AtomicInteger patchCount = new AtomicInteger();
        AtomicBoolean runtimeRepaired = new AtomicBoolean(false);

        CompilationReasoningService reasoningService = new CompilationReasoningService(
                new LlmClient() {
                    @Override
                    public String requestStructuredResponse(String prompt) {
                        llmCalls.incrementAndGet();
                        return """
                                {
                                  "decision": "APPLY_FIX",
                                  "actions": [
                                    { "type": "APPLY_PATCH", "args": { "path": "%s", "patch": "@@\\n-assertTrue(false);\\n+assertTrue(true);\\n" } }
                                  ],
                                  "memory_updates": {}
                                }
                                """.formatted(testFile.toString().replace("\\", "\\\\"));
                    }

                    @Override
                    public com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet generateTestSnippet(String prompt,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestClassInfo classInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestMethodInfo methodInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.MockPlan plan) {
                        throw new UnsupportedOperationException();
                    }
                },
                new com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser());

        CoveragePipelineOrchestrator orchestrator = new CoveragePipelineOrchestrator(
                new PipelineLogger(tempDir),
                successCompiler(),
                (projectRoot, testClassFile, methodName) -> {
                    if (patchCount.get() == 1 && !runtimeRepaired.get()) {
                        return new ExecuteResult(false,
                                List.of("shouldSendWelcomeEmailWithoutError"),
                                "",
                                "AssertionFailedError: expected: <true> but was: <false>");
                    }
                    return new ExecuteResult(true, List.of(), "", "");
                },
                reasoningService);

        ToolActionExecutor executor = new ToolActionExecutor(
                new com.gigachat.unit.tests.generator.reasoning.service.SourceFileEditor(),
                successCompiler(),
                successExecutor(),
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError") {
            @Override
            public ActionExecutionResult execute(com.gigachat.unit.tests.generator.reasoning.model.ToolAction action) {
                patchCount.incrementAndGet();
                return new ActionExecutionResult(Map.of(), List.of("APPLY_PATCH"));
            }
        };

        CoveragePipelineOrchestrator.CoverageStageResult result = orchestrator.run(
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError",
                List.of(60),
                () -> patchCount.get() >= 2
                        ? new CoverageResult(true, true, new CoverageSummary("NotificationService", "sendWelcome", 3, 0, 1, 0), tempDir.resolve("jacoco.xml"), "", "")
                        : new CoverageResult(true, true, new CoverageSummary("NotificationService", "sendWelcome", 1, 2, 0, 1), tempDir.resolve("jacoco.xml"), "", ""),
                executor,
                new ProjectContextCollector(tempDir),
                noOpFixingOrchestrator(testFile),
                (compileResult, executeResult) -> {
                    runtimeRepaired.set(true);
                    return new CoveragePipelineOrchestrator.RuntimeRegressionResult(
                            true,
                            compileResult,
                            new ExecuteResult(true, List.of(), "", ""),
                            new ActionExecutionResult(Map.of("runtimeRepair", "sibling-fixed"), List.of("RUNTIME_SIBLING_REPAIR")));
                });

        assertTrue(result.success());
        assertEquals(2, llmCalls.get(), "Expected coverage reasoning to get a second round after runtime repair");
        assertEquals(2, patchCount.get(), "Expected a second coverage fix after runtime repair kept the same signature");
        String logs = Files.readString(tempDir.resolve(".agent/logs/pipeline.log"));
        assertTrue(logs.contains("Allowing follow-up reasoning after runtime repair for shouldSendWelcomeEmailWithoutError"));
        assertTrue(logs.contains("Allowing repeated coverage signature after bounded follow-up for shouldSendWelcomeEmailWithoutError"));
    }

    @Test
    void shouldReturnToExecutionRepairWhenCoverageMeasurementSeesFailingSiblingTests() throws Exception {
        Path testFile = createTestFile();
        AtomicBoolean runtimeRepaired = new AtomicBoolean(false);
        AtomicInteger repairCalls = new AtomicInteger();

        CoveragePipelineOrchestrator orchestrator = new CoveragePipelineOrchestrator(
                new PipelineLogger(tempDir),
                successCompiler(),
                (projectRoot, testClassFile, methodName) -> runtimeRepaired.get()
                        ? new ExecuteResult(true, List.of(), "", "")
                        : new ExecuteResult(false,
                        List.of("shouldSendWelcomeEmailWithoutError"),
                        "",
                        "AssertionFailedError: expected: <true> but was: <false>"),
                stopReasoningService());

        CoveragePipelineOrchestrator.CoverageStageResult result = orchestrator.run(
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError",
                List.of(60),
                () -> runtimeRepaired.get()
                        ? new CoverageResult(true, true, new CoverageSummary("NotificationService", "sendWelcome", 3, 0, 1, 0), tempDir.resolve("jacoco.xml"), "", "")
                        : new CoverageResult(false, true, null, tempDir.resolve("jacoco.xml"), "", "There were failing tests. Execution failed for task ':test'"),
                noOpExecutor(testFile),
                new ProjectContextCollector(tempDir),
                noOpFixingOrchestrator(testFile),
                (compileResult, executeResult) -> {
                    repairCalls.incrementAndGet();
                    runtimeRepaired.set(true);
                    return new CoveragePipelineOrchestrator.RuntimeRegressionResult(
                            true,
                            compileResult,
                            new ExecuteResult(true, List.of(), "", ""),
                            ActionExecutionResult.empty());
                });

        assertTrue(result.success());
        assertTrue(runtimeRepaired.get(), "Expected initial failing sibling tests to trigger runtime repair");
        assertEquals(1, repairCalls.get(), "Expected exactly one runtime repair handoff from coverage measurement");
        assertTrue(result.coverageResult().meetsGoal(60));
    }

    @Test
    void shouldCarryRuntimeRepairActionsIntoCoveragePromptAfterSiblingRepair() throws Exception {
        Path testFile = createTestFile();
        AtomicBoolean runtimeRepaired = new AtomicBoolean(false);
        AtomicReference<String> promptRef = new AtomicReference<>();

        CompilationReasoningService reasoningService = new CompilationReasoningService(
                new LlmClient() {
                    @Override
                    public String requestStructuredResponse(String prompt) {
                        promptRef.set(prompt);
                        return """
                                {
                                  "decision": "STOP",
                                  "actions": [],
                                  "memory_updates": {}
                                }
                                """;
                    }

                    @Override
                    public com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet generateTestSnippet(String prompt,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestClassInfo classInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestMethodInfo methodInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.MockPlan plan) {
                        throw new UnsupportedOperationException();
                    }
                },
                new com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser());

        CoveragePipelineOrchestrator orchestrator = new CoveragePipelineOrchestrator(
                new PipelineLogger(tempDir),
                successCompiler(),
                (projectRoot, testClassFile, methodName) -> runtimeRepaired.get()
                        ? new ExecuteResult(true, List.of(), "", "")
                        : new ExecuteResult(false,
                        List.of("shouldSendWelcomeEmailWithoutError"),
                        "",
                        "AssertionFailedError: expected: <true> but was: <false>"),
                reasoningService);

        CoveragePipelineOrchestrator.CoverageStageResult result = orchestrator.run(
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError",
                List.of(80),
                () -> runtimeRepaired.get()
                        ? new CoverageResult(true, true, new CoverageSummary("NotificationService", "sendWelcome", 2, 1, 0, 1), tempDir.resolve("jacoco.xml"), "", "")
                        : new CoverageResult(false, true, null, tempDir.resolve("jacoco.xml"), "", "There were failing tests. Execution failed for task ':test'"),
                noOpExecutor(testFile),
                new ProjectContextCollector(tempDir),
                noOpFixingOrchestrator(testFile),
                (compileResult, executeResult) -> {
                    runtimeRepaired.set(true);
                    return new CoveragePipelineOrchestrator.RuntimeRegressionResult(
                            true,
                            compileResult,
                            new ExecuteResult(true, List.of(), "", ""),
                            new ActionExecutionResult(Map.of("runtimeRepair", "sibling-fixed"), List.of("RUNTIME_SIBLING_REPAIR")));
                });

        assertFalse(result.success());
        assertNotNull(promptRef.get());
        assertTrue(promptRef.get().contains("RUNTIME_SIBLING_REPAIR"));
        assertTrue(promptRef.get().contains("runtimeRepair"));
    }

    @Test
    void shouldApplyDeterministicCoverageRecipeForBooleanBranchGap() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/NotificationServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                class NotificationServiceTest {
                    @Test
                    void shouldSendWelcomeEmailWithoutError() {
                        boolean priorityAccount = false;
                        assertTrue(true);
                    }
                }
                """);
        AtomicReference<String> promptRef = new AtomicReference<>();
        CompilationReasoningService reasoningService = new CompilationReasoningService(
                new LlmClient() {
                    @Override
                    public String requestStructuredResponse(String prompt) {
                        promptRef.set(prompt);
                        return """
                                {
                                  "decision": "APPLY_FIX",
                                  "actions": [
                                    { "type": "APPLY_RECIPE", "args": { "recipeId": "ADD_BOOLEAN_BRANCH_SIBLING_TEST_SENDWELCOME" } }
                                  ],
                                  "memory_updates": {}
                                }
                                """;
                    }

                    @Override
                    public com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet generateTestSnippet(String prompt,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestClassInfo classInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestMethodInfo methodInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.MockPlan plan) {
                        throw new UnsupportedOperationException();
                    }
                },
                new com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser());

        CoveragePipelineOrchestrator orchestrator = new CoveragePipelineOrchestrator(
                new PipelineLogger(tempDir),
                successCompiler(),
                successExecutor(),
                reasoningService);

        ToolActionExecutor executor = new ToolActionExecutor(
                new com.gigachat.unit.tests.generator.reasoning.service.SourceFileEditor(),
                successCompiler(),
                successExecutor(),
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError");

        CoveragePipelineOrchestrator.CoverageStageResult result = orchestrator.run(
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError",
                List.of(80),
                () -> {
                    try {
                        String content = Files.readString(testFile);
                        if (content.contains("shouldSendWelcomeEmailWithoutErrorCoverageVariant")) {
                            return new CoverageResult(true, true, new CoverageSummary("NotificationService", "sendWelcome", 3, 0, 1, 0), tempDir.resolve("jacoco.xml"), "", "");
                        }
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                    return new CoverageResult(true, true, new CoverageSummary("NotificationService", "sendWelcome", 2, 1, 0, 1), tempDir.resolve("jacoco.xml"), "", "");
                },
                executor,
                new ProjectContextCollector(tempDir),
                noOpFixingOrchestrator(testFile),
                null);

        assertTrue(result.success());
        assertNotNull(promptRef.get());
        assertTrue(promptRef.get().contains("ADD_BOOLEAN_BRANCH_SIBLING_TEST_SENDWELCOME"));
        assertTrue(Files.readString(testFile).contains("shouldSendWelcomeEmailWithoutErrorCoverageVariant"));
        assertTrue(result.coverageResult().meetsGoal(80));
    }

    @Test
    void shouldApplyDeterministicCoverageRecipeForNumericBoundaryGap() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/CoverageGoalWorkflowServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.mockito.Mockito.*;
                import org.junit.jupiter.api.BeforeEach;
                import org.mockito.Mock;
                import org.mockito.MockitoAnnotations;

                public class CoverageGoalWorkflowServiceTest {

                    @Mock
                    AuditTrailService auditTrailService;

                    private CoverageGoalWorkflowService service;

                    @BeforeEach
                    void setUp() {
                        MockitoAnnotations.openMocks(this);
                        service = new CoverageGoalWorkflowService(auditTrailService);
                    }

                    @Test
                    void testPriorityClassificationWithHighScoreAndPriorityAccount() {
                        int highScore = 10;
                        boolean priorityAccount = true;
                        String result = service.classifySignal(highScore, priorityAccount);
                        verify(auditTrailService).recordEvent("priority-signal");
                        assertEquals("priority", result);
                    }
                }
                """);
        AtomicReference<String> promptRef = new AtomicReference<>();
        CompilationReasoningService reasoningService = new CompilationReasoningService(
                new LlmClient() {
                    @Override
                    public String requestStructuredResponse(String prompt) {
                        promptRef.set(prompt);
                        return """
                                {
                                  "decision": "APPLY_FIX",
                                  "actions": [
                                    { "type": "APPLY_RECIPE", "args": { "recipeId": "ADD_NUMERIC_BOUNDARY_SIBLING_TEST_CLASSIFYSIGNAL" } }
                                  ],
                                  "memory_updates": {}
                                }
                                """;
                    }

                    @Override
                    public com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet generateTestSnippet(String prompt,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestClassInfo classInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestMethodInfo methodInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.MockPlan plan) {
                        throw new UnsupportedOperationException();
                    }
                },
                new com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser());

        CoveragePipelineOrchestrator orchestrator = new CoveragePipelineOrchestrator(
                new PipelineLogger(tempDir),
                successCompiler(),
                successExecutor(),
                reasoningService);

        ToolActionExecutor executor = new ToolActionExecutor(
                new com.gigachat.unit.tests.generator.reasoning.service.SourceFileEditor(),
                successCompiler(),
                successExecutor(),
                tempDir,
                testFile,
                "com.example.app.service.CoverageGoalWorkflowServiceTest",
                "testPriorityClassificationWithHighScoreAndPriorityAccount");

        CoveragePipelineOrchestrator.CoverageStageResult result = orchestrator.run(
                tempDir,
                testFile,
                "com.example.app.service.CoverageGoalWorkflowServiceTest",
                "testPriorityClassificationWithHighScoreAndPriorityAccount",
                List.of(80),
                () -> {
                    try {
                        String content = Files.readString(testFile);
                        if (content.contains("highScore = 9;")) {
                            return new CoverageResult(true, true, new CoverageSummary("CoverageGoalWorkflowService", "classifySignal", 7, 1, 5, 1), tempDir.resolve("jacoco.xml"), "", "");
                        }
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                    return new CoverageResult(true, true, new CoverageSummary("CoverageGoalWorkflowService", "classifySignal", 3, 5, 2, 4), tempDir.resolve("jacoco.xml"), "", "");
                },
                executor,
                new ProjectContextCollector(tempDir),
                noOpFixingOrchestrator(testFile),
                null);

        assertTrue(result.success());
        assertNotNull(promptRef.get());
        assertTrue(promptRef.get().contains("ADD_NUMERIC_BOUNDARY_SIBLING_TEST_CLASSIFYSIGNAL"));
        assertTrue(Files.readString(testFile).contains("highScore = 9;"));
        assertTrue(result.coverageResult().meetsGoal(80));
    }

    @Test
    void shouldApplyDeterministicCoverageRecipeForExceptionGuardGap() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/FeatureValidationServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                class FeatureValidationServiceTest {
                    private final FeatureValidationService service = new FeatureValidationService();

                    @Test
                    void validateFeature_acceptsKnownFeature() {
                        String featureName = "dark-mode";
                        boolean enabled = service.validateFeature(featureName);
                        assertTrue(enabled);
                    }
                }
                """);
        AtomicReference<String> promptRef = new AtomicReference<>();
        CompilationReasoningService reasoningService = new CompilationReasoningService(
                new LlmClient() {
                    @Override
                    public String requestStructuredResponse(String prompt) {
                        promptRef.set(prompt);
                        return """
                                {
                                  "decision": "APPLY_FIX",
                                  "actions": [
                                    { "type": "APPLY_RECIPE", "args": { "recipeId": "ADD_EXCEPTION_GUARD_SIBLING_TEST_VALIDATEFEATURE" } }
                                  ],
                                  "memory_updates": {}
                                }
                                """;
                    }

                    @Override
                    public com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet generateTestSnippet(String prompt,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestClassInfo classInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestMethodInfo methodInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.MockPlan plan) {
                        throw new UnsupportedOperationException();
                    }
                },
                new com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser());

        CoveragePipelineOrchestrator orchestrator = new CoveragePipelineOrchestrator(
                new PipelineLogger(tempDir),
                successCompiler(),
                successExecutor(),
                reasoningService);

        ToolActionExecutor executor = new ToolActionExecutor(
                new com.gigachat.unit.tests.generator.reasoning.service.SourceFileEditor(),
                successCompiler(),
                successExecutor(),
                tempDir,
                testFile,
                "com.example.FeatureValidationServiceTest",
                "validateFeature_acceptsKnownFeature");

        CoveragePipelineOrchestrator.CoverageStageResult result = orchestrator.run(
                tempDir,
                testFile,
                "com.example.FeatureValidationServiceTest",
                "validateFeature_acceptsKnownFeature",
                List.of(80),
                () -> {
                    try {
                        String content = Files.readString(testFile);
                        if (content.contains("assertThrows(NullPointerException.class")) {
                            return new CoverageResult(true, true, new CoverageSummary("FeatureValidationService", "validateFeature", 3, 0, 1, 0), tempDir.resolve("jacoco.xml"), "", "");
                        }
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                    return new CoverageResult(true, true, new CoverageSummary("FeatureValidationService", "validateFeature", 2, 1, 0, 1), tempDir.resolve("jacoco.xml"), "", "");
                },
                executor,
                new ProjectContextCollector(tempDir),
                noOpFixingOrchestrator(testFile),
                null);

        assertTrue(result.success());
        assertNotNull(promptRef.get());
        assertTrue(promptRef.get().contains("ADD_EXCEPTION_GUARD_SIBLING_TEST_VALIDATEFEATURE"));
        assertTrue(Files.readString(testFile).contains("org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> service.validateFeature(featureName));"));
        assertTrue(result.coverageResult().meetsGoal(80));
    }

    @Test
    void shouldApplySingleDeterministicCoverageRecipeWhenReasoningStops() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/LegacyWorkflowServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import org.junit.jupiter.api.Test;
                import com.example.app.model.User;
                import com.example.lib.LibraryComponent;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.mockito.Mockito.mock;
                import static org.mockito.Mockito.verify;
                import static org.mockito.Mockito.when;

                class LegacyWorkflowServiceTest {
                    @Test
                    void shouldCoordinateShadowRollbackWhenShadowRollbackDisabled() {
                        FeatureToggleService featureToggleService = mock(FeatureToggleService.class);
                        AuditTrailService auditTrailService = mock(AuditTrailService.class);
                        NotificationService notificationService = mock(NotificationService.class);
                        LibraryComponent libraryComponent = mock(LibraryComponent.class);
                        LegacyWorkflowService service = new LegacyWorkflowService(featureToggleService, auditTrailService, notificationService, libraryComponent);
                        User user = new User("John Doe", "john.doe@example.com");
                        when(featureToggleService.isEnabled("shadow-rollback")).thenReturn(false);
                        boolean result = service.coordinateShadowRollback(user);
                        assertFalse(result);
                        verify(auditTrailService).recordEvent("Shadow rollback skipped for John Doe");
                        verify(libraryComponent).load();
                    }
                }
                """);
        AtomicInteger reasoningCalls = new AtomicInteger();
        CompilationReasoningService reasoningService = new CompilationReasoningService(
                new LlmClient() {
                    @Override
                    public String requestStructuredResponse(String prompt) {
                        reasoningCalls.incrementAndGet();
                        return """
                                {
                                  "decision": "STOP",
                                  "actions": [],
                                  "memory_updates": {}
                                }
                                """;
                    }

                    @Override
                    public com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet generateTestSnippet(String prompt,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestClassInfo classInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestMethodInfo methodInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.MockPlan plan) {
                        throw new UnsupportedOperationException();
                    }
                },
                new com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser());

        CoveragePipelineOrchestrator orchestrator = new CoveragePipelineOrchestrator(
                new PipelineLogger(tempDir),
                successCompiler(),
                successExecutor(),
                reasoningService);

        ToolActionExecutor executor = new ToolActionExecutor(
                new com.gigachat.unit.tests.generator.reasoning.service.SourceFileEditor(),
                successCompiler(),
                successExecutor(),
                tempDir,
                testFile,
                "com.example.app.service.LegacyWorkflowServiceTest",
                "shouldCoordinateShadowRollbackWhenShadowRollbackDisabled");

        CoveragePipelineOrchestrator.CoverageStageResult result = orchestrator.run(
                tempDir,
                testFile,
                "com.example.app.service.LegacyWorkflowServiceTest",
                "shouldCoordinateShadowRollbackWhenShadowRollbackDisabled",
                List.of(80),
                () -> {
                    try {
                        String content = Files.readString(testFile);
                        if (content.contains("shouldCoordinateShadowRollbackWhenShadowRollbackDisabledCoverageVariant2")
                                && content.contains("sendDeactivationNotice(user)")) {
                            return new CoverageResult(true, true, new CoverageSummary("LegacyWorkflowService", "coordinateShadowRollback", 7, 0, 2, 0), tempDir.resolve("jacoco.xml"), "", "");
                        }
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                    return new CoverageResult(true, true, new CoverageSummary("LegacyWorkflowService", "coordinateShadowRollback", 2, 1, 1, 1), tempDir.resolve("jacoco.xml"), "", "");
                },
                executor,
                new ProjectContextCollector(tempDir),
                noOpFixingOrchestrator(testFile),
                null);

        assertTrue(result.success());
        assertEquals(1, reasoningCalls.get());
        String updated = Files.readString(testFile);
        assertTrue(updated.contains("shouldCoordinateShadowRollbackWhenShadowRollbackDisabledCoverageVariant2"));
        assertTrue(updated.contains("static org.junit.jupiter.api.Assertions.assertTrue;"));
        assertTrue(result.coverageResult().meetsGoal(80));
    }

    @Test
    void shouldNotRecompileWhenCoverageApplyFixProducesNoPersistedChange() throws Exception {
        Path testFile = createTestFile();
        AtomicInteger compileCalls = new AtomicInteger();

        CoveragePipelineOrchestrator orchestrator = new CoveragePipelineOrchestrator(
                new PipelineLogger(tempDir),
                new CompilerInvoker() {
                    @Override
                    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                        compileCalls.incrementAndGet();
                        return new CompileResult(true, List.of(), "", "");
                    }
                },
                successExecutor(),
                applyFixReasoningService(testFile));

        CoveragePipelineOrchestrator.CoverageStageResult result = orchestrator.run(
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError",
                List.of(60),
                () -> new CoverageResult(true, true, new CoverageSummary("NotificationService", "sendWelcome", 1, 2, 0, 1), tempDir.resolve("jacoco.xml"), "", ""),
                new ToolActionExecutor(
                        new com.gigachat.unit.tests.generator.reasoning.service.SourceFileEditor(),
                        successCompiler(),
                        successExecutor(),
                        tempDir,
                        testFile,
                        "com.example.NotificationServiceTest",
                        "shouldSendWelcomeEmailWithoutError") {
                    @Override
                    public ActionExecutionResult execute(com.gigachat.unit.tests.generator.reasoning.model.ToolAction action) {
                        return ActionExecutionResult.empty();
                    }
                },
                new ProjectContextCollector(tempDir),
                noOpFixingOrchestrator(testFile),
                null);

        assertFalse(result.success());
        assertEquals(0, compileCalls.get(), "Expected no recompile after no-op coverage fix");
    }

    @Test
    void shouldFailGracefullyWhenCoverageIsBlockedBySiblingTestsAndRuntimeRepairFails() throws Exception {
        Path testFile = createTestFile();
        AtomicInteger repairCalls = new AtomicInteger();

        CoveragePipelineOrchestrator orchestrator = new CoveragePipelineOrchestrator(
                new PipelineLogger(tempDir),
                successCompiler(),
                (projectRoot, testClassFile, methodName) -> new ExecuteResult(false,
                        List.of("shouldSendWelcomeEmailWithoutError"),
                        "",
                        "AssertionFailedError: expected: <true> but was: <false>"),
                stopReasoningService());

        CoveragePipelineOrchestrator.CoverageStageResult result = orchestrator.run(
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError",
                List.of(60),
                () -> new CoverageResult(false,
                        true,
                        null,
                        tempDir.resolve("jacoco.xml"),
                        "",
                        "There were failing tests. Execution failed for task ':test'"),
                noOpExecutor(testFile),
                new ProjectContextCollector(tempDir),
                noOpFixingOrchestrator(testFile),
                (compileResult, executeResult) -> {
                    repairCalls.incrementAndGet();
                    return new CoveragePipelineOrchestrator.RuntimeRegressionResult(
                            false,
                            compileResult,
                            executeResult,
                            ActionExecutionResult.empty());
                });

        assertFalse(result.success());
        assertEquals(1, repairCalls.get(), "Expected exactly one runtime repair attempt before failing");
        assertNotNull(result.coverageResult());
        assertTrue(result.coverageResult().describeFailure()
                        .contains("runtime repair failed"),
                "Expected the failure to be reported instead of crashing the state machine");
    }

    @Test
    void shouldFailFastWhenCoverageIsBlockedBySiblingTestsWithoutRuntimeRepairHandler() throws Exception {
        Path testFile = createTestFile();
        AtomicInteger llmCalls = new AtomicInteger();
        CompilationReasoningService reasoningService = new CompilationReasoningService(
                new LlmClient() {
                    @Override
                    public String requestStructuredResponse(String prompt) {
                        llmCalls.incrementAndGet();
                        return """
                                {
                                  "decision": "STOP",
                                  "actions": [],
                                  "memory_updates": {}
                                }
                                """;
                    }

                    @Override
                    public com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet generateTestSnippet(String prompt,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestClassInfo classInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestMethodInfo methodInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.MockPlan plan) {
                        throw new UnsupportedOperationException();
                    }
                },
                new com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser());

        CoveragePipelineOrchestrator orchestrator = new CoveragePipelineOrchestrator(
                new PipelineLogger(tempDir),
                successCompiler(),
                successExecutor(),
                reasoningService);

        CoveragePipelineOrchestrator.CoverageStageResult result = orchestrator.run(
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError",
                List.of(60),
                () -> new CoverageResult(false,
                        true,
                        null,
                        tempDir.resolve("jacoco.xml"),
                        "",
                        "There were failing tests. Execution failed for task ':test'"),
                noOpExecutor(testFile),
                new ProjectContextCollector(tempDir),
                noOpFixingOrchestrator(testFile),
                null);

        assertFalse(result.success());
        assertEquals(0, llmCalls.get(), "Coverage reasoning should not run while sibling tests are already red and no runtime handoff exists");
        assertTrue(result.coverageResult().describeFailure()
                        .contains("no runtime repair handler is configured"),
                "Expected fail-fast message when sibling failures block coverage");
    }

    @Test
    void shouldValidateCoverageFixByExecutingWholeTestClass() throws Exception {
        Path testFile = createTestFile();
        AtomicReference<String> executedMethod = new AtomicReference<>("unset");
        AtomicBoolean patched = new AtomicBoolean(false);

        CoveragePipelineOrchestrator orchestrator = new CoveragePipelineOrchestrator(
                new PipelineLogger(tempDir),
                successCompiler(),
                (projectRoot, testClassFile, methodName) -> {
                    executedMethod.set(methodName);
                    return new ExecuteResult(true, List.of(), "", "");
                },
                applyFixReasoningService(testFile));

        CoveragePipelineOrchestrator.CoverageStageResult result = orchestrator.run(
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError",
                List.of(60),
                () -> patched.get()
                        ? new CoverageResult(true, true, new CoverageSummary("NotificationService", "sendWelcome", 4, 0, 0, 0), tempDir.resolve("jacoco.xml"), "", "")
                        : new CoverageResult(true, true, new CoverageSummary("NotificationService", "sendWelcome", 1, 3, 0, 0), tempDir.resolve("jacoco.xml"), "", ""),
                patchAwareExecutor(testFile, patched),
                new ProjectContextCollector(tempDir),
                noOpFixingOrchestrator(testFile),
                null);

        assertTrue(result.success());
        assertEquals("", executedMethod.get(), "Expected coverage validation to execute the whole test class");
    }

    private Path createTestFile() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/NotificationServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertTrue;

                class NotificationServiceTest {
                    void shouldSendWelcomeEmailWithoutError() {
                        assertTrue(false);
                    }
                }
                """);
        return testFile;
    }

    private ToolActionExecutor noOpExecutor(Path testFile) {
        return patchAwareExecutor(testFile, new AtomicBoolean(false));
    }

    private ToolActionExecutor patchAwareExecutor(Path testFile, AtomicBoolean patched) {
        return new ToolActionExecutor(
                new com.gigachat.unit.tests.generator.reasoning.service.SourceFileEditor(),
                successCompiler(),
                successExecutor(),
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError") {
            @Override
            public ActionExecutionResult execute(com.gigachat.unit.tests.generator.reasoning.model.ToolAction action) {
                patched.set(true);
                try {
                    String updated = Files.readString(testFile).replace("assertTrue(false);", "assertTrue(true);");
                    Files.writeString(testFile, updated);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                return new ActionExecutionResult(Map.of(), List.of("APPLY_PATCH"));
            }
        };
    }

    private ToolActionExecutor patchCountingExecutor(Path testFile, AtomicInteger patchCount) {
        return new ToolActionExecutor(
                new com.gigachat.unit.tests.generator.reasoning.service.SourceFileEditor(),
                successCompiler(),
                successExecutor(),
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError") {
            @Override
            public ActionExecutionResult execute(com.gigachat.unit.tests.generator.reasoning.model.ToolAction action) {
                patchCount.incrementAndGet();
                try {
                    String updated = Files.readString(testFile).replace("assertTrue(false);", "assertTrue(true);");
                    Files.writeString(testFile, updated);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                return new ActionExecutionResult(Map.of(), List.of("APPLY_PATCH"));
            }
        };
    }

    private ToolActionExecutor contextThenPatchExecutor(Path testFile, AtomicInteger patchCount) {
        return new ToolActionExecutor(
                new com.gigachat.unit.tests.generator.reasoning.service.SourceFileEditor(),
                successCompiler(),
                successExecutor(),
                tempDir,
                testFile,
                "com.example.CoverageGoalWorkflowServiceTest",
                "classifySignal_returnsPriority") {
            @Override
            public ActionExecutionResult execute(com.gigachat.unit.tests.generator.reasoning.model.ToolAction action) {
                String actionType = "";
                if (action != null) {
                    if (action.getType() != null) {
                        actionType = action.getType().name();
                    } else if (action.getSingleStep() != null && action.getSingleStep().getType() != null) {
                        actionType = action.getSingleStep().getType().name();
                    }
                }
                if ("READ_METHOD".equals(actionType)) {
                    return new ActionExecutionResult(
                            Map.of("contextCacheUpdates", Map.of("CoverageGoalWorkflowService#classifySignal", "context-loaded")),
                            List.of("READ_METHOD"));
                }
                patchCount.incrementAndGet();
                try {
                    String updated = Files.readString(testFile).replace("assertTrue(false);", "assertTrue(true);");
                    Files.writeString(testFile, updated);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                return new ActionExecutionResult(Map.of(), List.of("APPLY_PATCH"));
            }
        };
    }

    private CoverageResult coverageResultForPatchCount(int patchCount) {
        CoverageSummary summary = switch (patchCount) {
            case 0 -> new CoverageSummary("NotificationService", "sendWelcome", 1, 3, 0, 0);
            case 1 -> new CoverageSummary("NotificationService", "sendWelcome", 2, 2, 0, 0);
            case 2 -> new CoverageSummary("NotificationService", "sendWelcome", 3, 1, 0, 0);
            default -> new CoverageSummary("NotificationService", "sendWelcome", 4, 0, 0, 0);
        };
        return new CoverageResult(true, true, summary, tempDir.resolve("jacoco.xml"), "", "");
    }

    private CompilerInvoker successCompiler() {
        return new CompilerInvoker() {
            @Override
            public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                return new CompileResult(true, List.of(), "", "");
            }
        };
    }

    private ExecutionInvoker successExecutor() {
        return (projectRoot, testClassFile, methodName) -> new ExecuteResult(true, List.of(), "", "");
    }

    private CompilationReasoningService stopReasoningService() {
        return new CompilationReasoningService(
                new LlmClient() {
                    @Override
                    public String requestStructuredResponse(String prompt) {
                        return """
                                {
                                  "decision": "STOP",
                                  "actions": [],
                                  "memory_updates": {}
                                }
                                """;
                    }

                    @Override
                    public com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet generateTestSnippet(String prompt,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestClassInfo classInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestMethodInfo methodInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.MockPlan plan) {
                        throw new UnsupportedOperationException();
                    }
                },
                new com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser());
    }

    private CompilationReasoningService applyFixReasoningService(Path testFile) {
        return new CompilationReasoningService(
                new LlmClient() {
                    @Override
                    public String requestStructuredResponse(String prompt) {
                        return """
                                {
                                  "decision": "APPLY_FIX",
                                  "actions": [
                                    { "type": "APPLY_PATCH", "args": { "path": "%s", "patch": "@@\\n-assertTrue(false);\\n+assertTrue(true);\\n" } }
                                  ],
                                  "memory_updates": {}
                                }
                                """.formatted(testFile.toString().replace("\\", "\\\\"));
                    }

                    @Override
                    public com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet generateTestSnippet(String prompt,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestClassInfo classInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.TestMethodInfo methodInfo,
                                                                                                          com.gigachat.unit.tests.generator.dto.MockPlan plan) {
                        throw new UnsupportedOperationException();
                    }
                },
                new com.gigachat.unit.tests.generator.reasoning.prompt.CompilationReasoningPromptBuilder(),
                new ReasoningResponseParser());
    }

    private CompilationPipelineOrchestrator noOpFixingOrchestrator(Path testFile) {
        return new CompilationPipelineOrchestrator(
                successCompiler(),
                stopReasoningService(),
                new ProjectContextCollector(tempDir),
                noOpExecutor(testFile),
                null,
                new PipelineLogger(tempDir),
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError");
    }

    private CompilationPipelineOrchestrator compileFailingFixingOrchestrator(Path testFile, AtomicBoolean patched) {
        CompilerInvoker failingCompiler = new CompilerInvoker() {
            @Override
            public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
                return patched.get()
                        ? new CompileResult(false, List.of("broken compile"), "", "cannot compile")
                        : new CompileResult(true, List.of(), "", "");
            }
        };
        return new CompilationPipelineOrchestrator(
                failingCompiler,
                stopReasoningService(),
                new ProjectContextCollector(tempDir),
                noOpExecutor(testFile),
                null,
                new PipelineLogger(tempDir),
                tempDir,
                testFile,
                "com.example.NotificationServiceTest",
                "shouldSendWelcomeEmailWithoutError");
    }
}
