package com.gigachat.unit.tests.generator.resources;

import java.util.Map;

/**
 * Loads compile-stage deterministic recipe templates from editable resources.
 */
public class CompileRecipeTemplateCatalog {

    private static final String RESOURCE_PATH = "recipes/compile/compile-recipe-templates.json";

    private final RecipeTemplateCatalog delegate;

    public CompileRecipeTemplateCatalog() {
        this(new ResourceTextLoader());
    }

    public CompileRecipeTemplateCatalog(ResourceTextLoader loader) {
        this.delegate = new RecipeTemplateCatalog(loader, RESOURCE_PATH, "compile");
    }

    public Map<String, Object> render(String templateId, Map<String, Object> bindings) {
        return delegate.render(templateId, bindings);
    }
}
