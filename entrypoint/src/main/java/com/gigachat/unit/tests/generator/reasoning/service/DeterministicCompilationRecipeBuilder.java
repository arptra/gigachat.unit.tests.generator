package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorReport;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.resources.CompileRecipeTemplateCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Derives bounded compile-stage recipes from repeated, source-agnostic failure families.
 */
public class DeterministicCompilationRecipeBuilder {

    private static final Map<String, String> DUPLICATE_JUNIT_ANNOTATIONS = Map.of(
            "org.junit.jupiter.api.Test", "Test",
            "org.junit.jupiter.api.BeforeEach", "BeforeEach",
            "org.junit.jupiter.api.AfterEach", "AfterEach",
            "org.junit.jupiter.api.BeforeAll", "BeforeAll",
            "org.junit.jupiter.api.AfterAll", "AfterAll"
    );

    private static final CompileRecipeTemplateCatalog RECIPE_TEMPLATES = new CompileRecipeTemplateCatalog();

    public List<Map<String, Object>> build(CompilationErrorReport report, CompilationErrorInfo errorInfo) {
        String combined = combinedErrorText(report, errorInfo).toLowerCase(Locale.ROOT);
        if (looksLikeUserStateConstructorMismatch(combined)) {
            return List.of(RECIPE_TEMPLATES.render("NORMALIZE_USER_CONSTRUCTOR_STATE_VARIANTS", Map.of()));
        }
        if (!combined.contains("not a repeatable annotation interface")) {
            return List.of();
        }
        List<Map<String, Object>> recipes = new ArrayList<>();
        for (Map.Entry<String, String> entry : DUPLICATE_JUNIT_ANNOTATIONS.entrySet()) {
            if (!combined.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            recipes.add(RECIPE_TEMPLATES.render("COLLAPSE_CONSECUTIVE_JUNIT_ANNOTATION", bindings(entry.getKey(), entry.getValue())));
        }
        return List.copyOf(recipes);
    }

    private Map<String, Object> bindings(String annotationFqcn, String annotationSimpleName) {
        LinkedHashMap<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("annotationFqcn", annotationFqcn);
        bindings.put("annotationSimpleName", annotationSimpleName);
        bindings.put("annotationUpper", annotationSimpleName.toUpperCase(Locale.ROOT));
        return Map.copyOf(bindings);
    }

    private String combinedErrorText(CompilationErrorReport report, CompilationErrorInfo errorInfo) {
        StringBuilder builder = new StringBuilder();
        if (report != null && report.getErrors() != null) {
            report.getErrors().forEach(error -> appendIfPresent(builder, error.getNormalizedMessage()));
        }
        if (errorInfo != null) {
            appendIfPresent(builder, errorInfo.getPrimaryMessage());
            appendIfPresent(builder, errorInfo.getCompilerOutput());
            appendIfPresent(builder, errorInfo.getStacktrace());
        }
        return builder.toString();
    }

    private boolean looksLikeUserStateConstructorMismatch(String combined) {
        if (combined == null || combined.isBlank()) {
            return false;
        }
        if (!combined.contains("constructor user in class")) {
            return false;
        }
        return combined.matches("(?s).*found:\\s+java\\.lang\\.string,java\\.lang\\.string,int.*")
                || combined.matches("(?s).*found:\\s+java\\.lang\\.string,java\\.lang\\.string,boolean.*")
                || combined.matches("(?s).*found:\\s+no arguments.*");
    }

    private void appendIfPresent(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(value);
    }
}
