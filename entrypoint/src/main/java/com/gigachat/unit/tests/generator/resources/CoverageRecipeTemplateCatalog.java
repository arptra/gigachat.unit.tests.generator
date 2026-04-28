package com.gigachat.unit.tests.generator.resources;

import java.util.Map;

/**
 * Loads coverage-stage deterministic recipe templates from editable resources.
 */
public class CoverageRecipeTemplateCatalog {

    private static final String RESOURCE_PATH = "recipes/coverage/coverage-recipe-templates.json";

    private final RecipeTemplateCatalog delegate;

    public CoverageRecipeTemplateCatalog() {
        this(new ResourceTextLoader());
    }

    public CoverageRecipeTemplateCatalog(ResourceTextLoader loader) {
        this.delegate = new RecipeTemplateCatalog(loader, RESOURCE_PATH, "coverage");
    }

    public Map<String, Object> render(String templateId, Map<String, Object> bindings) {
        return delegate.render(templateId, bindings);
    }
}
