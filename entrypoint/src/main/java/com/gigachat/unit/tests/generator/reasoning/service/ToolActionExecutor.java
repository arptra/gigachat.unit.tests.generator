package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.ToolAction;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionStep;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Executes tool actions returned by the reasoning model. Information-gathering steps append data to
 * the execution log while project modifications are applied silently and recorded only as performed
 * actions. No human-facing logs are emitted; all outputs are captured for the next reasoning prompt.
 */
public class ToolActionExecutor {

    private final SourceFileEditor sourceFileEditor;
    private final SymbolLookupService symbolLookupService;
    private final ToolContextActionExecutor contextActionExecutor;
    private final ToolMutationActionExecutor mutationActionExecutor;
    private final ToolRunActionExecutor runActionExecutor;
    private final Path testFile;

    public ToolActionExecutor(SourceFileEditor sourceFileEditor,
                              CompilerInvoker compilerInvoker,
                              ExecutionInvoker executionInvoker,
                              Path projectRoot,
                              Path testFile,
                              String testFileFqcn,
                              String methodName) {
        this.sourceFileEditor = Objects.requireNonNull(sourceFileEditor, "sourceFileEditor");
        Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        Objects.requireNonNull(projectRoot, "projectRoot");
        this.testFile = Objects.requireNonNull(testFile, "testFile");
        this.symbolLookupService = new SymbolLookupService(projectRoot, sourceFileEditor);
        this.contextActionExecutor = new ToolContextActionExecutor(sourceFileEditor, symbolLookupService, projectRoot);
        this.mutationActionExecutor = new ToolMutationActionExecutor(sourceFileEditor, projectRoot, testFile, methodName);
        this.runActionExecutor = new ToolRunActionExecutor(compilerInvoker,
                executionInvoker,
                projectRoot,
                testFile,
                testFileFqcn,
                methodName);
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
        Map<String, Object> args = ArgumentNormalizer.normalize(step.getArguments(), type);
        return switch (type) {
            case SHOW_FILE -> contextActionExecutor.handleShowFile(args);
            case SHOW_IMPORTS -> contextActionExecutor.handleShowImports(args);
            case SEARCH_SYMBOL -> contextActionExecutor.handleSearchSymbol(args);
            case READ_CLASS -> contextActionExecutor.handleReadClass(args);
            case READ_METHOD -> contextActionExecutor.handleReadMethod(args);
            case LIST_METHODS -> contextActionExecutor.handleListMethods(args);
            case RUN_TEST -> runActionExecutor.handleRunTests(args);
            case APPLY_PATCH -> mutationActionExecutor.handleApplyPatch(args);
            case APPLY_RECIPE -> mutationActionExecutor.handleApplyRecipe(args);
            case ADD_IMPORT -> mutationActionExecutor.handleAddImport(args);
            case RECOMPILE -> runActionExecutor.handleRecompile();
            case MARK_FALSE_DEPENDENCY -> mutationActionExecutor.handleMarkFalseDependency(args);
            case STOP -> ActionExecutionResult.empty();
            case COMPOSITE -> ActionExecutionResult.empty();
        };
    }

    public void setAvailableRecipes(List<Map<String, Object>> recipes) {
        mutationActionExecutor.setAvailableRecipes(recipes);
    }

    public boolean symbolExistsInProjectOrClasspath(String symbol) {
        return symbolLookupService.symbolExistsInProjectOrClasspath(symbol);
    }

    public boolean packageExistsInClasspath(String packageName) {
        return symbolLookupService.packageExistsInClasspath(packageName);
    }

    public ActionExecutionResult alignImportWithUniqueProjectSymbol(Path targetFile, String symbol) {
        return alignImportWithUniqueProjectSymbol(targetFile, symbol, false);
    }

    public ActionExecutionResult alignImportWithUniqueProjectSymbol(Path targetFile, String symbol, boolean preferStaticImport) {
        if (targetFile == null || symbol == null || symbol.isBlank()) {
            return ActionExecutionResult.empty();
        }
        ImportResolution resolution = uniqueResolvableImport(symbol, preferStaticImport);
        if (resolution == null || resolution.importName() == null) {
            return ActionExecutionResult.empty();
        }
        String before = sourceFileEditor.readFile(targetFile);
        if (before.isBlank()) {
            return ActionExecutionResult.error("Unable to read test file for import alignment: " + targetFile);
        }
        String importSpec = resolution.staticImport()
                ? "static " + resolution.importName()
                : resolution.importName();
        String updated = sourceFileEditor.addImport(targetFile, importSpec);
        if (updated.isBlank() || Objects.equals(before, updated)) {
            return ActionExecutionResult.empty();
        }
        return new ActionExecutionResult(
                Map.of("autoImportRepairs", List.of(Map.of(
                        "symbol", symbol,
                        "resolvedImport", importSpec,
                        "path", targetFile.toString()
                ))),
                List.of("ALIGN_IMPORT " + symbol + " -> " + importSpec + " @ " + targetFile));
    }

    private ImportResolution uniqueResolvableImport(String symbol, boolean preferStaticImport) {
        List<String> staticImportCandidates = symbolLookupService.lookupStaticImportCandidates(symbol).stream()
                .filter(this::isImportableCandidate)
                .distinct()
                .toList();
        if (preferStaticImport && staticImportCandidates.size() == 1) {
            return new ImportResolution(staticImportCandidates.get(0), true);
        }
        List<String> projectCandidates = preferContextualProjectCandidates(symbolLookupService.lookupProjectCandidates(symbol)).stream()
                .filter(this::isImportableCandidate)
                .distinct()
                .toList();
        if (projectCandidates.size() == 1) {
            return new ImportResolution(projectCandidates.get(0), false);
        }
        List<String> standardLibraryCandidates = symbolLookupService.lookupStandardLibraryCandidates(symbol).stream()
                .filter(this::isImportableCandidate)
                .distinct()
                .toList();
        if (standardLibraryCandidates.size() == 1) {
            return new ImportResolution(standardLibraryCandidates.get(0), false);
        }
        List<String> classpathCandidates = symbolLookupService.lookupClasspathCandidates(symbol).stream()
                .filter(this::isImportableCandidate)
                .distinct()
                .toList();
        if (classpathCandidates.size() == 1) {
            return new ImportResolution(classpathCandidates.get(0), false);
        }
        if (!preferStaticImport && staticImportCandidates.size() == 1) {
            return new ImportResolution(staticImportCandidates.get(0), true);
        }
        return null;
    }

    private List<String> preferContextualProjectCandidates(List<String> candidates) {
        if (candidates == null || candidates.size() <= 1) {
            return candidates == null ? List.of() : candidates;
        }
        List<String> ownerImported = filterCandidatesByImportedOwnerType(candidates);
        if (ownerImported.size() == 1) {
            return ownerImported;
        }
        List<String> ownerReferenced = filterCandidatesByReferencedOwnerType(candidates);
        if (ownerReferenced.size() == 1) {
            return ownerReferenced;
        }
        return candidates;
    }

    private List<String> filterCandidatesByImportedOwnerType(List<String> candidates) {
        List<String> importedTypes = readImportedTypeNames();
        if (importedTypes.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> {
                    String ownerType = ownerTypeFqcn(candidate);
                    return ownerType != null && importedTypes.contains(ownerType);
                })
                .distinct()
                .toList();
    }

    private List<String> filterCandidatesByReferencedOwnerType(List<String> candidates) {
        String source = sourceFileEditor.readFile(testFile);
        if (source.isBlank()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            String ownerSimpleName = ownerTypeSimpleName(candidate);
            if (ownerSimpleName == null || ownerSimpleName.isBlank()) {
                continue;
            }
            Pattern ownerPattern = Pattern.compile("\\b" + Pattern.quote(ownerSimpleName) + "\\b");
            if (ownerPattern.matcher(source).find()) {
                matches.add(candidate);
            }
        }
        return matches.stream().distinct().toList();
    }

    private List<String> readImportedTypeNames() {
        return sourceFileEditor.readImports(testFile).stream()
                .map(String::trim)
                .filter(importLine -> importLine.startsWith("import ") && !importLine.startsWith("import static "))
                .map(importLine -> importLine.substring("import ".length(), importLine.length() - 1).trim())
                .toList();
    }

    private String ownerTypeFqcn(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String normalized = candidate.replace('$', '.');
        int lastDot = normalized.lastIndexOf('.');
        if (lastDot < 0) {
            return null;
        }
        String prefix = normalized.substring(0, lastDot);
        int ownerSegmentStart = prefix.lastIndexOf('.') + 1;
        if (ownerSegmentStart >= prefix.length()) {
            return null;
        }
        char ownerInitial = prefix.charAt(ownerSegmentStart);
        if (!Character.isUpperCase(ownerInitial)) {
            return null;
        }
        return prefix;
    }

    private String ownerTypeSimpleName(String candidate) {
        String ownerType = ownerTypeFqcn(candidate);
        if (ownerType == null || ownerType.isBlank()) {
            return null;
        }
        return ownerType.substring(ownerType.lastIndexOf('.') + 1);
    }

    private boolean isImportableCandidate(String candidate) {
        return candidate != null
                && !candidate.isBlank()
                && candidate.contains(".");
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

    private record ImportResolution(String importName, boolean staticImport) {
    }
}
