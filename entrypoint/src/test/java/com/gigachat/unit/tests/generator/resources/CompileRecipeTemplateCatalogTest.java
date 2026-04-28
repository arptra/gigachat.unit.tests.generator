package com.gigachat.unit.tests.generator.resources;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompileRecipeTemplateCatalogTest {

    @Test
    void shouldRenderDuplicateJUnitAnnotationRecipe() {
        CompileRecipeTemplateCatalog catalog = new CompileRecipeTemplateCatalog();

        Map<String, Object> recipe = catalog.render("COLLAPSE_CONSECUTIVE_JUNIT_ANNOTATION", Map.of(
                "annotationFqcn", "org.junit.jupiter.api.Test",
                "annotationSimpleName", "Test",
                "annotationUpper", "TEST"
        ));

        assertEquals("COLLAPSE_CONSECUTIVE_TEST_ANNOTATIONS", recipe.get("id"));
        assertEquals("COLLAPSE_CONSECUTIVE_JUNIT_ANNOTATION", recipe.get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipe.get("operations");
        assertEquals("collapse_consecutive_annotation", operations.get(0).get("type"));
        assertEquals("@Test", operations.get(0).get("annotation"));
    }
}
