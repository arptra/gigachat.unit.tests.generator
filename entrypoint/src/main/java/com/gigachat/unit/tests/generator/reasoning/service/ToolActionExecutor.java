package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfoBuilder;
import com.gigachat.unit.tests.generator.reasoning.model.ToolAction;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionStep;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;

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
 * Executes tool actions returned by the reasoning model and accumulates machine-readable context
 * for subsequent reasoning iterations.
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
        if (action.getType() == ToolActionType.COMPOSITE && action.getSteps() != null) {
            return executeComposite(action.getSteps());
        }
        if (action.getSingleStep() != null) {
            return executeStep(action.getSingleStep());
        }
        if (action.getType() != null) {
            return executeStep(new ToolActionStep(action.getType(), action.getSingleStep() == null ? Map.of() : action.getSingleStep().getArguments()));
        }
        return ActionExecutionResult.empty();
    }

    public ActionExecutionResult executeStep(ToolActionStep step) {
        if (step == null || step.getType() == null) {
            return ActionExecutionResult.empty();
        }
        ToolActionType type = step.getType();
        Map<String, Object> args = step.getArguments();
        return switch (type) {
            case ADD_DEPENDENCY -> handleAddDependency(args);
            case APPLY_PATCH -> handleApplyPatch(args);
            case ADD_IMPORT -> handleAddImport(args);
            case SHOW_FILE -> handleShowFile(args);
            case SHOW_IMPORTS -> handleShowImports(args);
            case SEARCH_SYMBOL -> handleSearchSymbol(args);
            case RECOMPILE -> handleRecompile();
            case RUN_TEST -> handleRunTests();
            case COMPOSITE -> ActionExecutionResult.empty();
        };
    }

    private ActionExecutionResult executeComposite(List<ToolActionStep> steps) {
        ActionExecutionResult cumulative = ActionExecutionResult.empty();
        for (ToolActionStep step : steps) {
            cumulative = cumulative.merge(executeStep(step));
        }
        return cumulative;
    }

    private ActionExecutionResult handleAddDependency(Map<String, Object> args) {
        String dependency = readStringArg(args, "dependencyName", "dependency", "artifact");
        List<String> dependencies = buildFileEditor.addTestDependency(dependency);
        Map<String, Object> payload = new HashMap<>();
        if (!dependencies.isEmpty()) {
            payload.put("updatedDependencies", dependencies);
        }
        return new ActionExecutionResult(payload);
    }

    private ActionExecutionResult handleApplyPatch(Map<String, Object> args) {
        String pathValue = readStringArg(args, "filePath", "path", "target");
        String patch = readStringArg(args, "patch", "content", "diff");
        if (patch == null || pathValue == null) {
            return ActionExecutionResult.empty();
        }
        Path path = resolve(pathValue);
        String updatedContent = sourceFileEditor.applyPatch(path, patch);
        Map<String, Object> payload = new HashMap<>();
        if (!updatedContent.isEmpty()) {
            payload.put("updatedFile", Map.of(
                    "path", path.toString(),
                    "content", updatedContent
            ));
        }
        return new ActionExecutionResult(payload);
    }

    private ActionExecutionResult handleAddImport(Map<String, Object> args) {
        String pathValue = readStringArg(args, "filePath", "path", "target");
        String importName = readStringArg(args, "import", "importFqcn", "fqcn");
        if (pathValue == null || importName == null) {
            return ActionExecutionResult.empty();
        }
        Path path = resolve(pathValue);
        String updatedContent = sourceFileEditor.addImport(path, importName);
        Map<String, Object> payload = new HashMap<>();
        if (!updatedContent.isEmpty()) {
            payload.put("updatedFile", Map.of(
                    "path", path.toString(),
                    "content", updatedContent
            ));
        }
        return new ActionExecutionResult(payload);
    }

    private ActionExecutionResult handleShowFile(Map<String, Object> args) {
        String pathValue = readStringArg(args, "filePath", "path", "target");
        if (pathValue == null) {
            return ActionExecutionResult.empty();
        }
        Path path = resolve(pathValue);
        String content = sourceFileEditor.readFile(path);
        Map<String, Object> payload = new HashMap<>();
        payload.put("fileContent", Map.of(
                "path", path.toString(),
                "content", content
        ));
        return new ActionExecutionResult(payload);
    }

    private ActionExecutionResult handleShowImports(Map<String, Object> args) {
        String pathValue = readStringArg(args, "filePath", "path", "target");
        if (pathValue == null) {
            return ActionExecutionResult.empty();
        }
        Path path = resolve(pathValue);
        List<String> imports = sourceFileEditor.readImports(path);
        Map<String, Object> payload = new HashMap<>();
        payload.put("imports", imports);
        return new ActionExecutionResult(payload);
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
        Map<String, Object> payload = new HashMap<>();
        payload.put("symbolSearchResults", matches);
        return new ActionExecutionResult(payload);
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
        Map<String, Object> payload = new HashMap<>();
        if (result.success()) {
            payload.put("compilationSuccess", true);
        } else {
            payload.put("errorInfo", CompilationErrorInfoBuilder.from(result, testFile, testFileFqcn));
        }
        return new ActionExecutionResult(payload);
    }

    private ActionExecutionResult handleRunTests() {
        Map<String, Object> payload = new HashMap<>();
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
        payload.put("testResult", detail);
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

