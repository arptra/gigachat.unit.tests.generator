package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.cleaner.parser.ExecutionFailureParseResult;
import com.gigachat.unit.tests.generator.cleaner.parser.TestFailure;
import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.report.parser.TestReportFailure;
import com.gigachat.unit.tests.generator.resources.StateModelCatalog;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRepairContextBuilderTest {

    private final GenerationRepairContextBuilder builder = new GenerationRepairContextBuilder(new StateModelCatalog());

    @Test
    void shouldBuildCompileRepairContextWithResourceDrivenCompilationState() {
        CompileResult compileResult = new CompileResult(false,
                List.of("cannot find symbol: class ImaginaryGateway"),
                "",
                "package foo.bar does not exist");
        GeneratedTestSnippet snippet = new GeneratedTestSnippet("SampleTest",
                "shouldCompile",
                "@Test void shouldCompile() {}",
                List.of());

        JSONObject context = builder.build(new JSONObject().put("goal", "repair"),
                compileResult,
                null,
                null,
                List.of(),
                snippet,
                2);

        assertEquals("S2_COMPILATION_FAILED", context.getJSONObject("stateModel").getString("currentState"));
        assertEquals("compile", context.getJSONObject("repair").getJSONArray("stageFeedback").getJSONObject(0).getString("stage"));
        assertEquals("compile", context.getJSONObject("repair").getString("latestFailureStage"));
        assertTrue(context.toString().contains("Search for the real symbol/import first"));
    }

    @Test
    void shouldBuildExecutionRepairContextWithRuntimeHintsAndParsedFailures() {
        ExecuteResult executeResult = new ExecuteResult(false,
                List.of("SampleTest.shouldWork"),
                "",
                "org.mockito.exceptions.misusing.NotAMockException");
        ExecutionFailureParseResult parseResult = new ExecutionFailureParseResult(
                List.of(new TestFailure("SampleTest", "shouldWork")),
                Optional.empty());
        List<TestReportFailure> reportFailures = List.of(new TestReportFailure(
                "SampleTest",
                "shouldWork",
                "Wanted but not invoked",
                List.of("stack")));

        JSONObject context = builder.build(new JSONObject().put("goal", "repair"),
                null,
                executeResult,
                parseResult,
                reportFailures,
                null,
                3);

        JSONObject repair = context.getJSONObject("repair");
        JSONObject executeStage = repair.getJSONArray("stageFeedback").getJSONObject(0);

        assertEquals("S2_2_EXECUTION_FAILED", context.getJSONObject("stateModel").getString("currentState"));
        assertEquals("execute", executeStage.getString("stage"));
        assertTrue(executeStage.has("parsedFailures"));
        assertTrue(executeStage.has("reportFailures"));
        assertTrue(context.toString().contains("Do not stub real objects"));
        assertTrue(context.toString().contains("Verify the collaborator and arguments"));
    }
}
