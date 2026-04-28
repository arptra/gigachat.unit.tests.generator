package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.cleaner.parser.ExecutionFailureParseResult;
import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.report.parser.TestReportFailure;
import com.gigachat.unit.tests.generator.resources.StateModelCatalog;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds deterministic repair context passed back to the generation stage after compile/runtime
 * failures. This class owns only JSON assembly and feedback heuristics; it does not execute any
 * pipeline step.
 */
public class GenerationRepairContextBuilder {

    private final StateModelCatalog stateModelCatalog;

    public GenerationRepairContextBuilder(StateModelCatalog stateModelCatalog) {
        this.stateModelCatalog = Objects.requireNonNull(stateModelCatalog, "stateModelCatalog");
    }

    public JSONObject build(JSONObject baseContext,
                            CompileResult compileResult,
                            ExecuteResult executeResult,
                            ExecutionFailureParseResult failureParseResult,
                            List<TestReportFailure> reportFailures,
                            GeneratedTestSnippet snippet,
                            int attempt) {
        JSONObject nextContext = baseContext == null ? new JSONObject() : new JSONObject(baseContext.toString());
        JSONObject repair = nextContext.optJSONObject("repair");
        if (repair == null) {
            repair = new JSONObject();
            nextContext.put("repair", repair);
        }
        repair.put("attempt", attempt);
        repair.put("mode", "deterministic-feedback");
        repair.put("nextObjective", "Return one corrected version of the same test class with minimal test-side changes.");
        repair.put("repairPolicy", new JSONObject(Map.of(
                "preserveWorkingTestShape", Boolean.TRUE,
                "modifyProductionCode", Boolean.FALSE,
                "fixOnlyReportedStages", Boolean.TRUE,
                "preferMinimalPatch", Boolean.TRUE
        )));
        if (snippet != null) {
            repair.put("previousSnippet", snippet.methodBody());
        }
        JSONArray stageFeedback = new JSONArray();
        if (compileResult != null) {
            stageFeedback.put(buildCompileStageFeedback(compileResult));
        }
        if (executeResult != null) {
            stageFeedback.put(buildExecutionStageFeedback(executeResult, failureParseResult, reportFailures));
        }
        if (stageFeedback.length() > 0) {
            repair.put("stageFeedback", stageFeedback);
            repair.put("diagnostics", new JSONArray(stageFeedback.toString()));
            repair.put("latestFailureStage", stageFeedback.getJSONObject(stageFeedback.length() - 1).optString("stage"));
        }
        nextContext.put("stateModel", buildRepairStateModel(compileResult, executeResult));
        return nextContext;
    }

    private JSONObject buildRepairStateModel(CompileResult compileResult, ExecuteResult executeResult) {
        if (compileResult != null && !compileResult.success()) {
            return copyRepairState("compilationFailed");
        }
        if (executeResult != null && !executeResult.success()) {
            return copyRepairState("executionFailed");
        }
        return copyRepairState("generationDefault");
    }

    private JSONObject buildCompileStageFeedback(CompileResult compileResult) {
        JSONObject compileBlock = new JSONObject();
        compileBlock.put("stage", "compile");
        compileBlock.put("status", compileResult.success() ? "SUCCESS" : "FAILED");
        compileBlock.put("messages", new JSONArray(compileResult.messages()));
        compileBlock.put("stdout", compileResult.stdout());
        compileBlock.put("stderr", compileResult.stderr());
        compileBlock.put("recommendedFixes", new JSONArray(deriveCompileRepairHints(compileResult)));
        return compileBlock;
    }

    private JSONObject buildExecutionStageFeedback(ExecuteResult executeResult,
                                                   ExecutionFailureParseResult failureParseResult,
                                                   List<TestReportFailure> reportFailures) {
        JSONObject executeBlock = new JSONObject();
        executeBlock.put("stage", "execute");
        executeBlock.put("status", executeResult.success() ? "SUCCESS" : "FAILED");
        JSONArray messages = new JSONArray(executeResult.failedTests());
        if (failureParseResult != null && !failureParseResult.failures().isEmpty()) {
            JSONArray parsedFailures = new JSONArray();
            failureParseResult.failures().forEach(failure -> parsedFailures.put(failure.className() + "." + failure.methodName()));
            executeBlock.put("parsedFailures", parsedFailures);
            for (int i = 0; i < parsedFailures.length(); i++) {
                messages.put(parsedFailures.get(i));
            }
        }
        if (reportFailures != null && !reportFailures.isEmpty()) {
            JSONArray reports = new JSONArray();
            for (TestReportFailure reportFailure : reportFailures) {
                JSONObject detail = new JSONObject();
                detail.put("class", reportFailure.className());
                detail.put("method", reportFailure.methodName());
                detail.put("message", reportFailure.message());
                detail.put("stackTrace", new JSONArray(reportFailure.stackTrace()));
                reports.put(detail);
            }
            executeBlock.put("reportFailures", reports);
        }
        executeBlock.put("messages", messages);
        executeBlock.put("stdout", executeResult.stdout());
        executeBlock.put("stderr", executeResult.stderr());
        executeBlock.put("recommendedFixes", new JSONArray(deriveExecutionRepairHints(executeResult, reportFailures)));
        return executeBlock;
    }

    private List<String> deriveCompileRepairHints(CompileResult compileResult) {
        if (compileResult == null) {
            return List.of("Use the compiler diagnostics as the only source of truth.");
        }
        String diagnostics = (compileResult.stdout()
                + "\n"
                + compileResult.stderr()
                + "\n"
                + String.join("\n", compileResult.messages())).toLowerCase();
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (diagnostics.contains("cannot find symbol") || diagnostics.contains("does not exist")) {
            hints.add("Search for the real symbol/import first and replace invented packages, imports, or class names.");
        }
        if (diagnostics.contains("non-static") || diagnostics.contains("static context")) {
            hints.add("Align static/instance usage with the real API instead of forcing a static call.");
        }
        if (diagnostics.contains("incompatible types") || diagnostics.contains("cannot be converted")) {
            hints.add("Match constructor arguments, return values, and assertions to the real method signature.");
        }
        if (diagnostics.contains("private access")) {
            hints.add("Remove private-field access and switch to public API setup/assertions.");
        }
        if (hints.isEmpty()) {
            hints.add("Apply the smallest test-only fix that removes the reported compiler error.");
        }
        return List.copyOf(hints);
    }

    private List<String> deriveExecutionRepairHints(ExecuteResult executeResult,
                                                    List<TestReportFailure> reportFailures) {
        if (executeResult == null) {
            return List.of("Keep the last compilable test and change only the failing runtime behavior.");
        }
        StringBuilder diagnostics = new StringBuilder();
        diagnostics.append(executeResult.stderr()).append('\n');
        diagnostics.append(executeResult.stdout()).append('\n');
        if (reportFailures != null) {
            reportFailures.forEach(failure -> {
                if (failure.message() != null) {
                    diagnostics.append(failure.message()).append('\n');
                }
            });
        }
        String combined = diagnostics.toString().toLowerCase();
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (combined.contains("notamockexception")) {
            hints.add("Do not stub real objects; mock only external collaborators and keep value objects/entities real.");
        }
        if (combined.contains("missingmethodinvocationexception")) {
            hints.add("Ensure Mockito stubbing wraps a real mock call and that the collaborator being stubbed is actually mocked.");
        }
        if (combined.contains("wanted but not invoked")) {
            hints.add("Verify the collaborator and arguments that are actually used on the executed branch.");
        }
        if (combined.contains("nullpointerexception")) {
            hints.add("Initialize or stub the missing collaborator before changing assertions.");
        }
        if (combined.contains("assertionfailederror") || combined.contains("expected:") || combined.contains("but was:")) {
            hints.add("Keep setup stable and adjust only the assertion or the directly related stub.");
        }
        if (hints.isEmpty()) {
            hints.add("Preserve the working compile state and apply one focused runtime fix.");
        }
        return List.copyOf(hints);
    }

    private JSONObject copyRepairState(String key) {
        JSONObject stateModel = stateModelCatalog.repairState(key);
        return stateModel == null ? new JSONObject() : stateModel;
    }
}
