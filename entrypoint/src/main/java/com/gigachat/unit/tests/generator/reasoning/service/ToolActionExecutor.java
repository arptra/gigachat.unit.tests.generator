package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfoBuilder;
import com.gigachat.unit.tests.generator.reasoning.model.ToolAction;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionStep;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;
import com.gigachat.unit.tests.generator.reasoning.service.action.AddDependencyAction;
import com.gigachat.unit.tests.generator.reasoning.service.action.AddImportAction;
import com.gigachat.unit.tests.generator.reasoning.service.action.ApplyPatchAction;
import com.gigachat.unit.tests.generator.reasoning.service.action.ProjectModificationAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Executes tool actions returned by the reasoning model. Information-gathering steps append data to
 * the execution log while project modifications are applied silently and recorded only as performed
 * actions. No human-facing logs are emitted; all outputs are captured for the next reasoning prompt.
 */
public class ToolActionExecutor {

    private final BuildFileEditor buildFileEditor;
    private final SourceFileEditor sourceFileEditor;
    private final CompilerInvoker compilerInvoker;
    private final ExecutionInvoker executionInvoker;
    private final Path projectRoot;
    private final Path testFile;
    private final String testFileFqcn;
    private final String methodName;

    public ToolActionExecutor(BuildFileEditor buildFileEditor,
                              SourceFileEditor sourceFileEditor,
                              CompilerInvoker compilerInvoker,
                              ExecutionInvoker executionInvoker,
                              Path projectRoot,
                              Path testFile,
                              String testFileFqcn,
                              String methodName) {
        this.buildFileEditor = Objects.requireNonNull(buildFileEditor, "buildFileEditor");
        this.sourceFileEditor = Objects.requireNonNull(sourceFileEditor, "sourceFileEditor");
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.executionInvoker = executionInvoker;
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.testFile = Objects.requireNonNull(testFile, "testFile");
        this.testFileFqcn = testFileFqcn;
        this.methodName = methodName;
    }

    public ActionExecutionResult execute(ToolAction action) {
        if (action == null) {
            return ActionExecutionResult.empty();
        }
        List<ToolActionStep> steps = resolveSteps(action);
        ActionExecutionResult cumulative = ActionExecutionResult.empty();
        for (ToolActionStep step : steps) {
            cumulative = cumulative.merge(executeStep(step));
        }
        return cumulative;
    }

    public ActionExecutionResult executeStep(ToolActionStep step) {
        if (step == null || step.getType() == null) {
            return ActionExecutionResult.empty();
        }
        ToolActionType type = step.getType();
        Map<String, Object> args = step.getArguments();
        return switch (type) {
            case SHOW_FILE -> handleShowFile(args);
            case SHOW_IMPORTS -> handleShowImports(args);
            case SEARCH_SYMBOL -> handleSearchSymbol(args);
            case RUN_TEST -> handleRunTests();
            case ADD_DEPENDENCY -> applyModification(createAddDependencyAction(args));
            case APPLY_PATCH -> applyModification(createApplyPatchAction(args));
            case ADD_IMPORT -> applyModification(createAddImportAction(args));
            case RECOMPILE -> handleRecompile();
            case COMPOSITE -> ActionExecutionResult.empty();
        };
    }

    private ActionExecutionResult applyModification(ProjectModificationAction action) {
        if (action == null) {
            return ActionExecutionResult.empty();
        }
        action.apply();
        return new ActionExecutionResult(Map.of(), List.of(action.describe()));
    }

    private ActionExecutionResult handleShowFile(Map<String, Object> args) {
        String pathValue = readStringArg(args, "filePath", "path", "target");
        if (pathValue == null) {
            return ActionExecutionResult.empty();
        }
        Path path = resolve(pathValue);
        String content = sourceFileEditor.readFile(path);
        Map<String, Object> entry = new HashMap<>();
        entry.put("path", path.toString());
        entry.put("content", content);
        return listPayload("fileContents", entry);
    }

    private ActionExecutionResult handleShowImports(Map<String, Object> args) {
        String pathValue = readStringArg(args, "filePath", "path", "target");
        if (pathValue == null) {
            return ActionExecutionResult.empty();
        }
        Path path = resolve(pathValue);
        List<String> imports = sourceFileEditor.readImports(path);
        Map<String, Object> entry = new HashMap<>();
        entry.put("path", path.toString());
        entry.put("imports", imports.stream()
                .map(value -> value.endsWith(";") ? value : value + ";")
                .collect(Collectors.toList()));
        return listPayload("imports", entry);
    }

    private ActionExecutionResult handleSearchSymbol(Map<String, Object> args) {
        String symbol = readStringArg(args, "symbol", "query", "name");
        if (symbol == null || symbol.isBlank()) {
            return ActionExecutionResult.empty();
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        try {
            Files.walk(projectRoot)
                    .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                    .forEach(path -> matches.addAll(searchInFile(path, symbol)));
        } catch (IOException ignored) {
            // ignore and return any matches gathered so far
        }
        return new ActionExecutionResult(Map.of("symbolSearchResults", matches));
    }

    private List<Map<String, Object>> searchInFile(Path file, String symbol) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<Map<String, Object>> results = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.contains(symbol)) {
                    results.add(Map.of(
                            "file", file.toString(),
                            "line", i + 1,
                            "code", line.trim()
                    ));
                }
            }
            return results;
        } catch (IOException exception) {
            return List.of();
        }
    }

    private ActionExecutionResult handleRecompile() {
        CompileResult result = compilerInvoker.compile(projectRoot, testFile, methodName);
        Map<String, Object> detail = new HashMap<>();
        detail.put("success", result.success());
        if (!result.success()) {
            detail.put("errorInfo", CompilationErrorInfoBuilder.from(result, testFile, testFileFqcn));
        }
        return new ActionExecutionResult(Map.of("compilationResult", detail));
    }

    private ActionExecutionResult handleRunTests() {
        Map<String, Object> detail = new HashMap<>();
        if (executionInvoker == null) {
            detail.put("success", false);
            detail.put("stdout", "");
            detail.put("stderr", "Execution invoker is not configured");
            detail.put("stacktrace", "");
        } else {
            ExecuteResult executeResult = executionInvoker.execute(projectRoot, testFile, methodName);
            detail.put("success", executeResult.success());
            detail.put("stdout", executeResult.stdout());
            detail.put("stderr", executeResult.stderr());
            detail.put("stacktrace", executeResult.failedTests().stream().collect(Collectors.joining("\n")));
        }
        return listPayload("testRuns", detail);
    }

    private ProjectModificationAction createAddDependencyAction(Map<String, Object> args) {
        String dependency = readStringArg(args, "dependencyName", "dependency", "artifact");
        if (dependency == null) {
            return null;
        }
        return new AddDependencyAction(buildFileEditor, dependency);
    }

    private ProjectModificationAction createApplyPatchAction(Map<String, Object> args) {
        String pathValue = readStringArg(args, "filePath", "path", "target");
        String patch = readStringArg(args, "patch", "content", "diff");
        if (patch == null || pathValue == null) {
            return null;
        }
        return new ApplyPatchAction(sourceFileEditor, resolve(pathValue), patch);
    }

    private ProjectModificationAction createAddImportAction(Map<String, Object> args) {
        String pathValue = readStringArg(args, "filePath", "path", "target");
        String importName = readStringArg(args, "import", "importFqcn", "fqcn");
        if (pathValue == null || importName == null) {
            return null;
        }
        return new AddImportAction(sourceFileEditor, resolve(pathValue), importName);
    }

    private List<ToolActionStep> resolveSteps(ToolAction action) {
        if (action.getType() == ToolActionType.COMPOSITE && action.getSteps() != null) {
            return action.getSteps();
        }
        if (action.getSingleStep() != null) {
            return List.of(action.getSingleStep());
        }
        if (action.getType() != null) {
            return List.of(new ToolActionStep(action.getType(), action.getSingleStep() == null ? Map.of() : action.getSingleStep().getArguments()));
        }
        return List.of();
    }

    private ActionExecutionResult listPayload(String key, Object entry) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(key, List.of(entry));
        return new ActionExecutionResult(payload);
    }

    private String readStringArg(Map<String, Object> args, String... keys) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Object value = args.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }

    private Path resolve(String pathValue) {
        Path path = Path.of(pathValue);
        if (!path.isAbsolute()) {
            path = projectRoot.resolve(pathValue);
        }
        return path.normalize().toAbsolutePath();
    }
}

