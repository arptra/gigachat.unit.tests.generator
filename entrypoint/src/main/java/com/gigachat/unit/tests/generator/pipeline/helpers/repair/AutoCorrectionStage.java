package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;

/**
 * Applies heuristic auto-corrections to generated snippets before validation
 * so that obvious internal field mutations are rewritten into safe public API
 * interactions.
 */
public class AutoCorrectionStage {

    public GeneratedTestSnippet apply(GeneratedTestSnippet snippet) {
        if (snippet == null) {
            return null;
        }
        String correctedBody = applyRules(snippet.methodBody());
        String correctedSource = applyRules(snippet.fullClassSource());
        if (equalsSafe(snippet.methodBody(), correctedBody)
                && equalsSafe(snippet.fullClassSource(), correctedSource)) {
            return snippet;
        }
        return new GeneratedTestSnippet(snippet.className(),
                snippet.methodName(),
                correctedBody,
                snippet.imports(),
                snippet.classAnnotations(),
                snippet.fieldDeclarations(),
                snippet.helperMethods(),
                correctedSource);
    }

    private String applyRules(String code) {
        if (code == null || code.isEmpty()) {
            return code;
        }
        String updated = code;
        if (updated.contains("repository.users")) {
            updated = updated.replace("repository.users = users;",
                    "for (User u : users) { repository.save(u); }");
        }
        return updated;
    }

    private boolean equalsSafe(String original, String updated) {
        if (original == null) {
            return updated == null;
        }
        return original.equals(updated);
    }
}
