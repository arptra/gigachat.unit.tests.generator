package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministically repairs repeated E104 fixture shapes that try to encode
 * later state through invented User constructors or no-arg constructor + setters.
 */
public final class ConstructorStatePathFallbackBuilder {

    private final PipelineLogger logger;

    public ConstructorStatePathFallbackBuilder(PipelineLogger logger) {
        this.logger = logger;
    }

    public GeneratedTestSnippet build(GeneratedTestSnippet snippet,
                                      Analyze.AnalysisSummary analysisSummary,
                                      String validationMessage) {
        if (snippet == null || analysisSummary == null || validationMessage == null || !looksLikeConstructorStateProblem(validationMessage)) {
            return null;
        }
        if (!supportsUserStateNormalization(analysisSummary, validationMessage, snippet)) {
            return null;
        }
        String updatedMethodBody = UserConstructorStateNormalizer.normalize(snippet.methodBody());
        List<String> updatedHelpers = rewriteHelpers(snippet.helperMethods());
        String updatedSource = UserConstructorStateNormalizer.normalize(snippet.fullClassSource());
        if (equalsSafe(snippet.methodBody(), updatedMethodBody)
                && equalsSafeList(snippet.helperMethods(), updatedHelpers)
                && equalsSafe(snippet.fullClassSource(), updatedSource)) {
            return null;
        }
        logger.info("Built deterministic constructor-state fallback for " + snippet.methodName());
        return new GeneratedTestSnippet(snippet.className(),
                snippet.methodName(),
                updatedMethodBody,
                snippet.imports(),
                snippet.classAnnotations(),
                snippet.fieldDeclarations(),
                updatedHelpers,
                updatedSource);
    }

    private boolean looksLikeConstructorStateProblem(String validationMessage) {
        if (validationMessage == null || validationMessage.isBlank()) {
            return false;
        }
        return validationMessage.contains("E104")
                || validationMessage.contains("E102")
                || validationMessage.contains("E113");
    }

    private List<String> rewriteHelpers(List<String> helperMethods) {
        if (helperMethods == null || helperMethods.isEmpty()) {
            return helperMethods;
        }
        List<String> rewritten = new ArrayList<>(helperMethods.size());
        for (String helper : helperMethods) {
            rewritten.add(UserConstructorStateNormalizer.normalize(helper));
        }
        return rewritten;
    }

    private boolean supportsUserStateNormalization(Analyze.AnalysisSummary analysisSummary,
                                                   String validationMessage,
                                                   GeneratedTestSnippet snippet) {
        Map<String, List<ConstructorMetadata>> constructors = analysisSummary.availableConstructors();
        if (constructors == null || constructors.isEmpty()) {
            return false;
        }
        List<ConstructorMetadata> userConstructors = constructors.getOrDefault("User", List.of());
        boolean hasStringEmailConstructor = userConstructors.stream()
                .map(ConstructorMetadata::signature)
                .filter(signature -> signature != null && !signature.isBlank())
                .map(signature -> signature.toLowerCase(Locale.ROOT))
                .anyMatch(signature -> signature.contains("user(string username, string email)")
                        || signature.contains("user(java.lang.string username, java.lang.string email)"));
        if (!hasStringEmailConstructor) {
            return false;
        }
        List<String> userMethods = analysisSummary.availableMethods() == null
                ? List.of()
                : analysisSummary.availableMethods().getOrDefault("User", List.of());
        boolean hasDeactivate = userMethods.stream().anyMatch(signature -> signature != null && signature.contains("deactivate()"));
        boolean hasIncrementAttempts = userMethods.stream().anyMatch(signature -> signature != null && signature.contains("incrementAttempts()"));
        String combinedSource = (snippet.methodBody() == null ? "" : snippet.methodBody())
                + "\n"
                + String.join("\n", snippet.helperMethods() == null ? List.of() : snippet.helperMethods())
                + "\n"
                + (snippet.fullClassSource() == null ? "" : snippet.fullClassSource())
                + "\n"
                + validationMessage;
        if (!combinedSource.contains("User")) {
            return false;
        }
        boolean needsDeactivate = combinedSource.contains("false)") || combinedSource.contains(".setActive(false)");
        boolean needsIncrementAttempts = combinedSource.contains(", 1)")
                || combinedSource.contains(", 2)")
                || combinedSource.contains(", 3)")
                || combinedSource.contains(", 4)")
                || combinedSource.contains(", 5)")
                || combinedSource.contains(".setLoginAttempts(")
                || combinedSource.contains(".incrementLoginAttempts(")
                || combinedSource.contains(".incrementAttempts()");
        return (!needsDeactivate || hasDeactivate) && (!needsIncrementAttempts || hasIncrementAttempts);
    }

    private boolean equalsSafe(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private boolean equalsSafeList(List<String> left, List<String> right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }
}
