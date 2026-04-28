package com.gigachat.unit.tests.generator.resources;

import java.util.Map;

/**
 * Loads runtime recipe templates from editable resources and renders them with runtime bindings.
 */
public class RuntimeRecipeTemplateCatalog {
    private static final String RESOURCE_PATH = "recipes/runtime/runtime-recipe-templates.json";

    private final RecipeTemplateCatalog delegate;

    public RuntimeRecipeTemplateCatalog() {
        this(new ResourceTextLoader());
    }

    public RuntimeRecipeTemplateCatalog(ResourceTextLoader loader) {
        this.delegate = new RecipeTemplateCatalog(loader, RESOURCE_PATH, "runtime");
    }

    public Map<String, Object> render(String templateId, Map<String, Object> bindings) {
        return delegate.render(templateId, bindings);
    }
}
