package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministically rewrites simple invented boolean-style state setters such as
 * {@code user.setActive(false)} into listed public mutators like {@code user.deactivate()}
 * when the source metadata already exposes the legal mutators.
 */
public final class InventedStateMutatorFallbackBuilder {

    private static final Pattern SET_ACTIVE_CALL =
            Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\.setActive\\((true|false)\\)");
    private static final Pattern COUNTER_MUTATOR_STATEMENT =
            Pattern.compile("(?m)^([ \\t]*)([A-Za-z_][A-Za-z0-9_]*)\\.(incrementLoginAttempts|setLoginAttempts)\\((\\d+)\\);");
    private static final Pattern VALIDATION_ENTRY =
            Pattern.compile("Invented method\\s+([A-Za-z0-9_$.]+)\\.([A-Za-z0-9_]+)\\(");

    private final PipelineLogger logger;

    public InventedStateMutatorFallbackBuilder(PipelineLogger logger) {
        this.logger = logger;
    }

    public GeneratedTestSnippet build(GeneratedTestSnippet snippet,
                                      Analyze.AnalysisSummary analysisSummary,
                                      String validationMessage) {
        if (snippet == null || analysisSummary == null || validationMessage == null || !validationMessage.contains("E102")) {
            return null;
        }
        EligibleMutators eligibleMutators = resolveEligibleMutators(analysisSummary.availableMethods(), validationMessage);
        if (eligibleMutators.isEmpty()) {
            return null;
        }
        String updatedMethodBody = rewriteMutatorCalls(snippet.methodBody(), eligibleMutators);
        List<String> updatedHelpers = rewriteHelpers(snippet.helperMethods(), eligibleMutators);
        String updatedSource = rewriteMutatorCalls(snippet.fullClassSource(), eligibleMutators);
        if (equalsSafe(snippet.methodBody(), updatedMethodBody)
                && equalsSafeList(snippet.helperMethods(), updatedHelpers)
                && equalsSafe(snippet.fullClassSource(), updatedSource)) {
            return null;
        }
        logger.info("Built deterministic invented-state-mutator fallback for "
                + snippet.methodName() + " using types " + eligibleMutators.types());
        return new GeneratedTestSnippet(snippet.className(),
                snippet.methodName(),
                updatedMethodBody,
                snippet.imports(),
                snippet.classAnnotations(),
                snippet.fieldDeclarations(),
                updatedHelpers,
                updatedSource);
    }

    private List<String> rewriteHelpers(List<String> helperMethods, EligibleMutators eligibleMutators) {
        if (helperMethods == null || helperMethods.isEmpty()) {
            return helperMethods;
        }
        List<String> rewritten = new ArrayList<>(helperMethods.size());
        for (String helper : helperMethods) {
            rewritten.add(rewriteMutatorCalls(helper, eligibleMutators));
        }
        return rewritten;
    }

    private String rewriteMutatorCalls(String text, EligibleMutators eligibleMutators) {
        if (text == null || text.isBlank() || eligibleMutators == null || eligibleMutators.isEmpty()) {
            return text;
        }
        String rewrittenText = text;
        if (eligibleMutators.booleanStateTypes().isEmpty() && eligibleMutators.counterStateTypes().isEmpty()) {
            return text;
        }
        if (!eligibleMutators.booleanStateTypes().isEmpty()) {
            rewrittenText = rewriteSetActiveCalls(rewrittenText);
        }
        if (!eligibleMutators.counterStateTypes().isEmpty()) {
            rewrittenText = rewriteCounterMutatorCalls(rewrittenText);
        }
        return rewrittenText;
    }

    private String rewriteSetActiveCalls(String text) {
        Matcher matcher = SET_ACTIVE_CALL.matcher(text);
        StringBuffer rewritten = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String variable = matcher.group(1);
            boolean active = Boolean.parseBoolean(matcher.group(2));
            String replacement = variable + '.' + (active ? "activate()" : "deactivate()");
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
            changed = true;
        }
        if (!changed) {
            return text;
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private String rewriteCounterMutatorCalls(String text) {
        Matcher matcher = COUNTER_MUTATOR_STATEMENT.matcher(text);
        StringBuffer rewritten = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String indentation = matcher.group(1);
            String variable = matcher.group(2);
            int targetValue;
            try {
                targetValue = Integer.parseInt(matcher.group(4));
            } catch (NumberFormatException exception) {
                continue;
            }
            String replacement = buildCounterReplacement(indentation, variable, targetValue);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
            changed = true;
        }
        if (!changed) {
            return text;
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private String buildCounterReplacement(String indentation, String variable, int targetValue) {
        String prefix = indentation == null ? "" : indentation;
        if (targetValue <= 0) {
            return prefix + "// loginAttempts already starts at 0";
        }
        List<String> calls = new ArrayList<>(targetValue);
        for (int index = 0; index < targetValue; index++) {
            calls.add(prefix + variable + ".incrementAttempts();");
        }
        return String.join(System.lineSeparator(), calls);
    }

    private EligibleMutators resolveEligibleMutators(Map<String, List<String>> availableMethods, String validationMessage) {
        LinkedHashSet<String> booleanTypes = new LinkedHashSet<>();
        LinkedHashSet<String> counterTypes = new LinkedHashSet<>();
        if (availableMethods == null || availableMethods.isEmpty()) {
            return new EligibleMutators(booleanTypes, counterTypes);
        }
        Matcher matcher = VALIDATION_ENTRY.matcher(validationMessage);
        while (matcher.find()) {
            String type = simpleName(matcher.group(1));
            String method = matcher.group(2);
            if (type.isBlank()) {
                continue;
            }
            List<String> methods = availableMethods.getOrDefault(type, List.of());
            boolean hasActivate = methods.stream().anyMatch(signature -> signature != null && signature.contains("activate()"));
            boolean hasDeactivate = methods.stream().anyMatch(signature -> signature != null && signature.contains("deactivate()"));
            boolean hasIncrementAttempts = methods.stream().anyMatch(signature -> signature != null && signature.contains("incrementAttempts()"));
            if ("setActive".equals(method) && (hasActivate || hasDeactivate)) {
                booleanTypes.add(type);
            }
            if (("incrementLoginAttempts".equals(method) || "setLoginAttempts".equals(method)) && hasIncrementAttempts) {
                counterTypes.add(type);
            }
        }
        return new EligibleMutators(booleanTypes, counterTypes);
    }

    private String simpleName(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
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

    private record EligibleMutators(Set<String> booleanStateTypes,
                                    Set<String> counterStateTypes) {

        private boolean isEmpty() {
            return booleanStateTypes.isEmpty() && counterStateTypes.isEmpty();
        }

        private Set<String> types() {
            LinkedHashSet<String> combined = new LinkedHashSet<>(booleanStateTypes);
            combined.addAll(counterStateTypes);
            return combined;
        }
    }
}
