package com.gigachat.unit.tests.generator.reasoning.model;

/**
 * Canonical tool actions and their expected arguments (normalized keys):
 * SHOW_FILE: { "path": "<string>" }
 * SHOW_IMPORTS: { "path": "<string>" }
 * READ_CLASS: { "className": "<FQN>" }
 * READ_METHOD: { "className": "<FQN>", "methodName": "<string>" }
 * LIST_METHODS: { "className": "<FQN>" }
 * SEARCH_SYMBOL: { "symbol": "<string>" }
 * APPLY_PATCH: { "path": "<testFilePath>", "patch": "<diff>" }
 * APPLY_RECIPE: { "recipeId": "<string>" }
 * ADD_IMPORT: { "path": "<testFilePath>", "import": "<fqcn>" }
 * RECOMPILE/RUN_TEST/MARK_FALSE_DEPENDENCY/STOP: {}
 */
public enum ToolActionType {
    COMPOSITE,
    SHOW_FILE,
    SHOW_IMPORTS,
    SEARCH_SYMBOL,
    READ_METHOD,
    READ_CLASS,
    LIST_METHODS,
    APPLY_PATCH,
    APPLY_RECIPE,
    ADD_IMPORT,
    RECOMPILE,
    RUN_TEST,
    MARK_FALSE_DEPENDENCY,
    STOP
}
