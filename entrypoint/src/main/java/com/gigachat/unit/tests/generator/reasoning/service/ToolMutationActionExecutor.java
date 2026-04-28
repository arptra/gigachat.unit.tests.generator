package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.service.action.AddImportAction;
import com.gigachat.unit.tests.generator.reasoning.service.action.ApplyPatchAction;
import com.gigachat.unit.tests.generator.reasoning.service.action.ProjectModificationAction;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ToolMutationActionExecutor {

    private final SourceFileEditor sourceFileEditor;
    private final Path projectRoot;
    private final Path testFile;
    private final RecipeOperationApplier recipeOperationApplier;
    private Map<String, Map<String, Object>> availableRecipes = Map.of();

    ToolMutationActionExecutor(SourceFileEditor sourceFileEditor,
                               Path projectRoot,
                               Path testFile,
                               String methodName) {
        this.sourceFileEditor = Objects.requireNonNull(sourceFileEditor, "sourceFileEditor");
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.testFile = Objects.requireNonNull(testFile, "testFile");
        this.recipeOperationApplier = new RecipeOperationApplier(methodName);
    }

    void setAvailableRecipes(List<Map<String, Object>> recipes) {
        if (recipes == null || recipes.isEmpty()) {
            this.availableRecipes = Map.of();
            return;
        }
        LinkedHashMap<String, Map<String, Object>> catalog = new LinkedHashMap<>();
        for (Map<String, Object> recipe : recipes) {
            if (recipe == null) {
                continue;
            }
            Object rawId = recipe.get("id");
            if (rawId == null) {
                continue;
            }
            catalog.put(rawId.toString(), recipe);
        }
        this.availableRecipes = Collections.unmodifiableMap(catalog);
    }

    ActionExecutionResult handleApplyPatch(Map<String, Object> args) {
        return applyModification(createApplyPatchAction(args));
    }

    ActionExecutionResult handleAddImport(Map<String, Object> args) {
        return applyModification(createAddImportAction(args));
    }

    ActionExecutionResult handleMarkFalseDependency(Map<String, Object> args) {
        String symbol = requireString(args, "symbol");
        if (symbol == null) {
            return ActionExecutionResult.error("Missing required argument: symbol");
        }
        return new ActionExecutionResult(Map.of("knownMissingSymbols", List.of(symbol)));
    }

    @SuppressWarnings("unchecked")
    ActionExecutionResult handleApplyRecipe(Map<String, Object> args) {
        String recipeId = requireString(args, "recipeId");
        if (recipeId == null || recipeId.isBlank()) {
            return ActionExecutionResult.error("Missing required argument: recipeId");
        }
        Map<String, Object> recipe = availableRecipes.get(recipeId);
        if (recipe == null || recipe.isEmpty()) {
            return ActionExecutionResult.error("Unknown recipeId: " + recipeId);
        }
        String source = sourceFileEditor.readFile(testFile);
        if (source.isBlank()) {
            return ActionExecutionResult.error("Unable to read test file for recipe: " + recipeId);
        }
        String updated = source;
        boolean changed = false;
        Object rawOperations = recipe.get("operations");
        if (rawOperations instanceof List<?> operations) {
            for (Object operation : operations) {
                if (operation instanceof Map<?, ?> rawOperation) {
                    updated = recipeOperationApplier.apply(updated, (Map<String, Object>) rawOperation);
                }
            }
        }
        if (!Objects.equals(source, updated)) {
            String persisted = sourceFileEditor.writeFile(testFile, updated);
            if (persisted.isBlank()) {
                return ActionExecutionResult.error("Failed to persist recipe changes: " + recipeId);
            }
            changed = true;
        }
        Object rawImports = recipe.get("requiredImports");
        if (rawImports instanceof List<?> imports) {
            for (Object requiredImport : imports) {
                if (requiredImport != null && !requiredImport.toString().isBlank()) {
                    String beforeImport = sourceFileEditor.readFile(testFile);
                    String afterImport = sourceFileEditor.addImport(testFile, requiredImport.toString());
                    if (!afterImport.isBlank() && !Objects.equals(beforeImport, afterImport)) {
                        changed = true;
                    }
                }
            }
        }
        if (!changed) {
            return ActionExecutionResult.error("Recipe produced no changes: " + recipeId);
        }
        return new ActionExecutionResult(
                Map.of("appliedRecipeIds", List.of(recipeId)),
                List.of("APPLY_RECIPE " + recipeId));
    }

    private ActionExecutionResult applyModification(ProjectModificationAction action) {
        if (action == null) {
            return ActionExecutionResult.empty();
        }
        Path targetPath = null;
        if (action instanceof ApplyPatchAction applyPatchAction) {
            targetPath = applyPatchAction.getFilePath();
        }
        if (action instanceof AddImportAction addImportAction) {
            targetPath = addImportAction.getFilePath();
        }
        if (targetPath != null && !isTestFile(targetPath)) {
            return new ActionExecutionResult(Map.of("forbiddenActions", List.of(action.describe())), List.of());
        }
        String before = targetPath == null ? "" : sourceFileEditor.readFile(targetPath);
        action.apply();
        if (targetPath != null) {
            String after = sourceFileEditor.readFile(targetPath);
            if (before.isBlank() || after.isBlank() || Objects.equals(before, after)) {
                return ActionExecutionResult.error("Project modification produced no persisted change: " + action.describe());
            }
        }
        return new ActionExecutionResult(Map.of(), List.of(action.describe()));
    }

    private ProjectModificationAction createApplyPatchAction(Map<String, Object> args) {
        String pathValue = requireString(args, "path");
        String patch = requireString(args, "patch");
        if (patch == null || pathValue == null) {
            return null;
        }
        Path target = resolve(pathValue);
        if (!isTestFile(target)) {
            return null;
        }
        return new ApplyPatchAction(sourceFileEditor, target, patch);
    }

    private ProjectModificationAction createAddImportAction(Map<String, Object> args) {
        String pathValue = requireString(args, "path");
        String importName = requireString(args, "import");
        if (pathValue == null || importName == null) {
            return null;
        }
        Path target = resolve(pathValue);
        if (!isTestFile(target)) {
            return null;
        }
        return new AddImportAction(sourceFileEditor, target, importName);
    }

    private String requireString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : value.toString();
    }

    private boolean isTestFile(Path path) {
        Path testRoot = projectRoot.resolve("src/test").toAbsolutePath().normalize();
        return path != null && path.toAbsolutePath().normalize().startsWith(testRoot);
    }

    private Path resolve(String pathValue) {
        Path path = Path.of(pathValue);
        if (!path.isAbsolute()) {
            path = projectRoot.resolve(pathValue);
        }
        return path.normalize().toAbsolutePath();
    }
}
