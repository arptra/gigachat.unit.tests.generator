package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.reasoning.model.ToolAction;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionStep;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Executes tool actions returned by the reasoning model.
 */
public class ToolActionExecutor {

    private final PipelineLogger logger;
    private final BuildFileEditor buildFileEditor;
    private final SourceFileEditor sourceFileEditor;
    private final CompilerInvoker compilerInvoker;
    private final ExecutionInvoker executionInvoker;
    private final Path projectRoot;
    private final Path testFile;
    private final String methodName;

    public ToolActionExecutor(PipelineLogger logger,
                              BuildFileEditor buildFileEditor,
                              SourceFileEditor sourceFileEditor,
                              CompilerInvoker compilerInvoker,
                              ExecutionInvoker executionInvoker,
                              Path projectRoot,
                              Path testFile,
                              String methodName) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.buildFileEditor = Objects.requireNonNull(buildFileEditor, "buildFileEditor");
        this.sourceFileEditor = Objects.requireNonNull(sourceFileEditor, "sourceFileEditor");
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.executionInvoker = executionInvoker;
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.testFile = Objects.requireNonNull(testFile, "testFile");
        this.methodName = methodName;
    }

    public void execute(ToolAction action) {
        if (action == null) {
            logger.warn("No tool action provided by reasoning workflow");
            return;
        }
        if (action.getType() == ToolActionType.COMPOSITE && action.getSteps() != null) {
            action.getSteps().forEach(this::executeStep);
            return;
        }
        if (action.getSingleStep() != null) {
            executeStep(action.getSingleStep());
            return;
        }
        if (action.getType() != null) {
            executeStep(new ToolActionStep(action.getType(), action.getSingleStep() == null ? Map.of() : action.getSingleStep().getArguments()));
        }
    }

    public void executeStep(ToolActionStep step) {
        if (step == null || step.getType() == null) {
            return;
        }
        ToolActionType type = step.getType();
        Map<String, Object> args = step.getArguments();
        switch (type) {
            case ADD_DEPENDENCY -> handleAddDependency(args);
            case APPLY_PATCH -> handleApplyPatch(args);
            case ADD_IMPORT -> handleAddImport(args);
            case SHOW_FILE -> handleShowFile(args);
            case SHOW_IMPORTS -> handleShowImports(args);
            case RECOMPILE -> compilerInvoker.compile(projectRoot, testFile, methodName);
            case RUN_TEST -> runTests();
            case COMPOSITE, SEARCH_SYMBOL -> logger.info("Composite or search action ignored at executor level.");
            default -> logger.warn("Unhandled tool action type: " + type);
        }
    }

    private void handleAddDependency(Map<String, Object> args) {
        String dependency = readStringArg(args, "dependencyName", "dependency", "artifact");
        buildFileEditor.addTestDependency(dependency);
    }

    private void handleApplyPatch(Map<String, Object> args) {
        String pathValue = readStringArg(args, "filePath", "path", "target");
        String patch = readStringArg(args, "patch", "content", "diff");
        if (patch == null || pathValue == null) {
            logger.warn("APPLY_PATCH missing required arguments");
            return;
        }
        Path path = resolve(pathValue);
        sourceFileEditor.applyPatch(path, patch);
    }

    private void handleAddImport(Map<String, Object> args) {
        String pathValue = readStringArg(args, "filePath", "path", "target");
        String importName = readStringArg(args, "import", "importFqcn", "fqcn");
        if (pathValue == null || importName == null) {
            logger.warn("ADD_IMPORT missing required arguments");
            return;
        }
        sourceFileEditor.addImport(resolve(pathValue), importName);
    }

    private void handleShowFile(Map<String, Object> args) {
        String pathValue = readStringArg(args, "filePath", "path", "target");
        if (pathValue == null) {
            return;
        }
        String content = sourceFileEditor.readFile(resolve(pathValue));
        logger.info("SHOW_FILE:\n" + content);
    }

    private void handleShowImports(Map<String, Object> args) {
        String pathValue = readStringArg(args, "filePath", "path", "target");
        if (pathValue == null) {
            return;
        }
        logger.info("SHOW_IMPORTS: " + sourceFileEditor.readImports(resolve(pathValue)));
    }

    private void runTests() {
        if (executionInvoker == null) {
            logger.warn("Execution invoker not configured; skipping RUN_TEST action");
            return;
        }
        executionInvoker.execute(projectRoot, testFile, methodName);
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

