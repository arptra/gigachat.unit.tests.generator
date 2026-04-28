package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministically rewrites wrong in-project imports reported by E111 to the authoritative
 * source-backed type path that the validator already discovered.
 */
public final class ProjectImportCorrectionFallbackBuilder {

    private static final Pattern IMPORT_MAPPING =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_$.]*)\\s*->\\s*([A-Za-z_][A-Za-z0-9_$.]*)");

    private final PipelineLogger logger;

    public ProjectImportCorrectionFallbackBuilder(PipelineLogger logger) {
        this.logger = logger;
    }

    public GeneratedTestSnippet build(GeneratedTestSnippet snippet, String validationMessage) {
        if (snippet == null || validationMessage == null || !validationMessage.contains("E111")) {
            return null;
        }
        Map<String, String> replacements = parseReplacements(validationMessage);
        if (replacements.isEmpty()) {
            return null;
        }
        List<String> updatedImports = rewriteImports(snippet.imports(), replacements);
        List<String> updatedHelpers = rewriteTextList(snippet.helperMethods(), replacements);
        List<String> updatedFields = rewriteTextList(snippet.fieldDeclarations(), replacements);
        String updatedMethodBody = rewriteText(snippet.methodBody(), replacements);
        String updatedSource = rewriteText(snippet.fullClassSource(), replacements);
        if (unchanged(snippet.imports(), updatedImports)
                && unchanged(snippet.helperMethods(), updatedHelpers)
                && unchanged(snippet.fieldDeclarations(), updatedFields)
                && equalsSafe(snippet.methodBody(), updatedMethodBody)
                && equalsSafe(snippet.fullClassSource(), updatedSource)) {
            return null;
        }
        logger.info("Built deterministic wrong-project-import fallback for "
                + snippet.methodName() + " using replacements " + replacements);
        return new GeneratedTestSnippet(
                snippet.className(),
                snippet.methodName(),
                updatedMethodBody,
                updatedImports,
                snippet.classAnnotations(),
                updatedFields,
                updatedHelpers,
                updatedSource
        );
    }

    private Map<String, String> parseReplacements(String validationMessage) {
        LinkedHashMap<String, String> replacements = new LinkedHashMap<>();
        Matcher matcher = IMPORT_MAPPING.matcher(validationMessage);
        while (matcher.find()) {
            String wrongImport = matcher.group(1);
            String correctImport = matcher.group(2);
            if (wrongImport == null || wrongImport.isBlank() || correctImport == null || correctImport.isBlank()) {
                continue;
            }
            replacements.put(wrongImport, correctImport);
        }
        return replacements;
    }

    private List<String> rewriteImports(List<String> imports, Map<String, String> replacements) {
        if (imports == null || imports.isEmpty()) {
            return imports;
        }
        List<String> rewritten = new ArrayList<>(imports.size());
        for (String importLine : imports) {
            rewritten.add(rewriteImportLine(importLine, replacements));
        }
        return rewritten;
    }

    private String rewriteImportLine(String importLine, Map<String, String> replacements) {
        if (importLine == null || importLine.isBlank()) {
            return importLine;
        }
        String rewritten = importLine;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String wrongImport = entry.getKey();
            String correctImport = entry.getValue();
            rewritten = rewritten.replace("import " + wrongImport + ";", "import " + correctImport + ";");
            rewritten = rewritten.replace(wrongImport, correctImport);
        }
        return rewritten;
    }

    private List<String> rewriteTextList(List<String> fragments, Map<String, String> replacements) {
        if (fragments == null || fragments.isEmpty()) {
            return fragments;
        }
        List<String> rewritten = new ArrayList<>(fragments.size());
        for (String fragment : fragments) {
            rewritten.add(rewriteText(fragment, replacements));
        }
        return rewritten;
    }

    private String rewriteText(String text, Map<String, String> replacements) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String rewritten = text;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            rewritten = rewritten.replace(entry.getKey(), entry.getValue());
        }
        return rewritten;
    }

    private boolean unchanged(List<String> original, List<String> updated) {
        if (original == null) {
            return updated == null;
        }
        return original.equals(updated);
    }

    private boolean equalsSafe(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }
}
