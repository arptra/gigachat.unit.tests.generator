package com.gigachat.unit.tests.generator.reasoning.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningMemory;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningStage;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;
import com.gigachat.unit.tests.generator.resources.PromptSnippetCatalog;
import com.gigachat.unit.tests.generator.resources.ReasoningPattern;
import com.gigachat.unit.tests.generator.resources.ReasoningPatternCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class CompilationReasoningPromptBuilder {

    private final ObjectWriter writer;
    private final ReasoningPatternCatalog patternCatalog;
    private final PromptSnippetCatalog promptSnippetCatalog;

    public CompilationReasoningPromptBuilder() {
        ObjectMapper mapper = new ObjectMapper();
        this.writer = mapper.writerWithDefaultPrettyPrinter();
        this.patternCatalog = new ReasoningPatternCatalog();
        this.promptSnippetCatalog = new PromptSnippetCatalog();
    }

    public String buildPrompt(ReasoningLoopContext loopContext) {
        if (loopContext == null) {
            throw new IllegalArgumentException("loopContext");
        }
        StringBuilder prompt = new StringBuilder();
        ReasoningMemory memory = loopContext.getMemory();
        var stage = loopContext.getStage();
        Set<String> forbidden = memory.getForbiddenActions();
        String allowedTools = stage.describeAllowedActions(memory);
        String allowedDecisions = stage.describeAllowedDecisions();
        String actionSchema = stage.allowedToolActions(memory).stream()
                .map(ToolActionType::name)
                .collect(Collectors.joining("|"));
        List<String> heuristics = deriveHeuristics(loopContext);
        List<ReasoningPattern> matchedPatterns = patternCatalog.match(loopContext);

        prompt.append("You are a deterministic test-repair agent working on GENERATED TESTS only. ")
                .append("Production code must NEVER be modified or invented. ")
                .append("Respond with compact JSON only. ")
                .append("If information is missing, request context via READ_* or SEARCH_SYMBOL before proposing fixes. ")
                .append("If context budget is exhausted or symbol is absent from sources, STOP safely.\n\n");

        appendRules(prompt, "Resource-driven reasoning rules", promptSnippetCatalog.commonReasoningRules());

        prompt.append("STAGE: ").append(stage.name()).append("\n");
        prompt.append("OBJECTIVE: ").append(stage.objective()).append("\n\n");

        prompt.append("Deterministic protocol:\n");
        for (String rule : stage.protocol()) {
            prompt.append("- ").append(rule).append("\n");
        }
        prompt.append("- Choose exactly one decision from the allowed list.\n")
                .append("- Do not invent tools that are not in the allowed action list.\n")
                .append("- Use REQUEST_CONTEXT when the next test edit depends on missing source information.\n")
                .append("- Use APPLY_FIX only when you can name a concrete test-side edit right now.\n")
                .append("- Use STOP only when no allowed action can move the stage forward.\n\n");

        appendRules(prompt, "Stage-specific resource directives", stageRules(stage));

        if (!heuristics.isEmpty()) {
            prompt.append("Failure-specific heuristics:\n");
            for (String heuristic : heuristics) {
                prompt.append("- ").append(heuristic).append("\n");
            }
            prompt.append("\n");
        }

        if (!matchedPatterns.isEmpty()) {
            prompt.append("Matched catalog patterns:\n");
            for (ReasoningPattern pattern : matchedPatterns) {
                prompt.append("- ").append(pattern.id())
                        .append(" [").append(pattern.errorFamily()).append("]: ")
                        .append(pattern.summary()).append("\n");
                if (pattern.allowedTools() != null && !pattern.allowedTools().isEmpty()) {
                    prompt.append("  allowedTools: ").append(String.join(", ", pattern.allowedTools())).append("\n");
                }
                if (pattern.executorActions() != null && !pattern.executorActions().isEmpty()) {
                    prompt.append("  executorActions: ").append(String.join(", ", pattern.executorActions())).append("\n");
                }
                if (pattern.llmHeuristic() != null && !pattern.llmHeuristic().isBlank()) {
                    prompt.append("  llmHeuristic: ").append(pattern.llmHeuristic()).append("\n");
                }
            }
            prompt.append("\n");
        }

        List<Map<String, Object>> deterministicRecipes = extractDeterministicRecipes(loopContext);
        if (!deterministicRecipes.isEmpty()) {
            prompt.append("Deterministic repair recipes:\n");
            for (String rule : promptSnippetCatalog.reasoningRecipeRules()) {
                prompt.append("- ").append(rule).append("\n");
            }
            for (Map<String, Object> recipe : deterministicRecipes) {
                String recipeId = stringify(recipe.get("id"));
                String summary = stringify(recipe.get("summary"));
                prompt.append("- ").append(recipeId).append(": ").append(summary).append("\n");
                Object operations = recipe.get("operations");
                if (operations instanceof List<?> steps && !steps.isEmpty()) {
                    for (Object step : steps) {
                        if (step instanceof Map<?, ?> operation) {
                            prompt.append("  operation: ").append(operation).append("\n");
                        }
                    }
                }
            }
            prompt.append("- If one recipe matches the failing collaborator flow, return APPLY_FIX with action {\"type\":\"APPLY_RECIPE\",\"args\":{\"recipeId\":\"<exact id>\"}}.\n\n");
        }

        prompt.append("STATE: ").append(memory.getState())
                .append(" | ATTEMPT: ").append(memory.getAttempt())
                .append(" | CONTEXT_BUDGET: ").append(memory.getContextRequestBudgetRemaining()).append("\n");
        prompt.append("KNOWN_MISSING_SYMBOLS: ").append(memory.getKnownMissingSymbols()).append("\n");
        prompt.append("APPLIED_FIXES: ").append(memory.getAppliedFixSignatures()).append("\n");
        prompt.append("RECENT_ERRORS: ").append(memory.getRecentErrorSignatures()).append("\n");
        prompt.append("CONTEXT_CACHE_KEYS: ").append(memory.getContextCache().keySet()).append("\n");
        prompt.append("FORBIDDEN_ACTIONS: ").append(forbidden).append("\n\n");

        prompt.append("Allowed tool actions: ").append(allowedTools).append("\n");
        prompt.append("Allowed decisions: ").append(allowedDecisions).append("\n");
        prompt.append("Required JSON response ONLY:\n")
                .append("{\n")
                .append("  \"decision\": \"").append(allowedDecisions).append("\",\n")
                .append("  \"actions\": [ { \"type\": \"").append(actionSchema).append("\", \"args\": { ... } } ],\n")
                .append("  \"memory_updates\": { \"knownMissingSymbols\": [], \"appliedFixSignatures\": [], \"contextCache\": {\"key\":\"value\"} }\n")
                .append("}\n");
        prompt.append("Return JSON only. No explanations. No markdown. No prose.\n\n");

        prompt.append("Failure info (JSON):\n").append(asJson(loopContext.getErrorInfo())).append("\n\n");
        if (loopContext.getErrorReport() != null) {
            prompt.append("Classified compilation errors (JSON):\n").append(asJson(loopContext.getErrorReport())).append("\n\n");
        }
        prompt.append("Project context summary (JSON):\n").append(asJson(loopContext.getProjectContextSummary())).append("\n\n");
        if (loopContext.getExecutionResult() != null
                && (!loopContext.getExecutionResult().getInformation().isEmpty()
                || !loopContext.getExecutionResult().getPerformedActions().isEmpty())) {
            prompt.append("Execution log from previous iteration (JSON):\n");
            prompt.append(asJson(loopContext.getExecutionResult().toPromptPayload())).append("\n");
        }

        return prompt.toString();
    }

    private void appendRules(StringBuilder prompt, String title, List<String> rules) {
        if (rules == null || rules.isEmpty()) {
            return;
        }
        prompt.append(title).append(":\n");
        for (String rule : rules) {
            prompt.append("- ").append(rule).append("\n");
        }
        prompt.append("\n");
    }

    private List<String> stageRules(ReasoningStage stage) {
        if (stage == null) {
            return List.of();
        }
        return switch (stage) {
            case COMPILATION -> promptSnippetCatalog.compilationReasoningRules();
            case EXECUTION -> promptSnippetCatalog.executionReasoningRules();
            case COVERAGE -> promptSnippetCatalog.coverageReasoningRules();
        };
    }

    private String asJson(Object value) {
        try {
            return writer.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialise prompt payload", exception);
        }
    }

    private List<String> deriveHeuristics(ReasoningLoopContext loopContext) {
        if (loopContext == null) {
            return List.of();
        }
        return switch (loopContext.getStage()) {
            case COMPILATION -> deriveCompilationHeuristics(loopContext);
            case EXECUTION -> deriveExecutionHeuristics(loopContext);
            case COVERAGE -> deriveCoverageHeuristics(loopContext);
        };
    }

    private List<String> deriveCompilationHeuristics(ReasoningLoopContext loopContext) {
        List<String> heuristics = new ArrayList<>();
        String combined = combinedErrorText(loopContext.getErrorInfo());
        if (containsIgnoreCase(combined, "cannot find symbol")
                || containsIgnoreCase(combined, "package ")
                || containsIgnoreCase(combined, "does not exist")) {
            heuristics.add("Missing symbol/import: SEARCH_SYMBOL or SHOW_IMPORTS before adding imports; do not invent packages or class names.");
        }
        if (containsIgnoreCase(combined, "non-static")
                || containsIgnoreCase(combined, "static context")) {
            heuristics.add("Static-context mismatch: inspect the target call shape and either instantiate the collaborator or stop using static access.");
        }
        if (containsIgnoreCase(combined, "incompatible types")
                || containsIgnoreCase(combined, "cannot be converted")) {
            heuristics.add("Type mismatch: READ_METHOD or READ_CLASS for the called API and align argument/return types before patching assertions.");
        }
        if (containsIgnoreCase(combined, "private access")
                || containsIgnoreCase(combined, "has private access")) {
            heuristics.add("Private member access: remove the private-field/private-method usage and switch to public API setup or assertions.");
        }
        if (heuristics.isEmpty()) {
            heuristics.add("Prefer the smallest compilable fix: inspect context first, then patch only the generated test.");
        }
        heuristics.addAll(extractPatternHeuristics(loopContext));
        return heuristics;
    }

    private List<String> deriveExecutionHeuristics(ReasoningLoopContext loopContext) {
        List<String> heuristics = new ArrayList<>();
        String combined = combinedErrorText(loopContext.getErrorInfo());
        if (containsIgnoreCase(combined, "NotAMockException")) {
            heuristics.add("Mockito NotAMockException: do not stub real objects; only collaborators from shouldMock should be wrapped in Mockito mocks.");
        }
        if (containsIgnoreCase(combined, "MissingMethodInvocationException")) {
            heuristics.add("Mockito MissingMethodInvocationException: ensure when()/doReturn() targets an actual mock call and inspect the collaborator type before patching.");
        }
        if (containsIgnoreCase(combined, "Wanted but not invoked")) {
            heuristics.add("Verification failure: inspect control flow and verify the collaborator/arguments that are actually used by the method under test.");
        }
        if (containsIgnoreCase(combined, "NullPointerException")) {
            heuristics.add("NullPointerException: inspect constructor/setup paths and initialize or stub the missing collaborator before changing assertions.");
        }
        if (containsIgnoreCase(combined, "AssertionFailedError")
                || containsIgnoreCase(combined, "ComparisonFailure")
                || containsIgnoreCase(combined, "expected:")
                || containsIgnoreCase(combined, "but was:")) {
            heuristics.add("Assertion failure: keep setup stable and adjust only the assertion or the immediately related stub.");
        }
        if (containsIgnoreCase(combined, "Real DB connection attempt")) {
            heuristics.add("Static void blocker: the failing static owner and channel literal are already known from the runtime stack, so prefer APPLY_RECIPE over another REQUEST_CONTEXT loop.");
        }
        heuristics.addAll(extractMockContextHints(loopContext));
        if (!extractDeterministicRecipes(loopContext).isEmpty()) {
            heuristics.add("A deterministic recipe catalog is available for this runtime failure; choose APPLY_RECIPE instead of inventing a free-form patch.");
        }
        if (heuristics.isEmpty()) {
            heuristics.add("Preserve the compilable test and fix runtime behavior incrementally instead of regenerating the whole method.");
        }
        heuristics.addAll(extractPatternHeuristics(loopContext));
        return heuristics.stream().distinct().toList();
    }

    private List<String> deriveCoverageHeuristics(ReasoningLoopContext loopContext) {
        List<String> heuristics = new ArrayList<>();
        heuristics.add("Target missed branches/lines from coverage feedback; extend the current test before introducing new scaffolding.");
        if (!loopContext.getMemory().getAppliedFixSignatures().isEmpty()) {
            heuristics.add("Reuse already compilable setup from previous fixes and add only the assertions/inputs needed for uncovered branches.");
        }
        heuristics.addAll(extractPatternHeuristics(loopContext));
        return heuristics;
    }

    private List<String> extractPatternHeuristics(ReasoningLoopContext loopContext) {
        return patternCatalog.match(loopContext).stream()
                .map(ReasoningPattern::llmHeuristic)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractMockContextHints(ReasoningLoopContext loopContext) {
        if (loopContext.getExecutionResult() == null) {
            return List.of();
        }
        Object rawMockContext = loopContext.getExecutionResult().getInformation().get("mockContext");
        if (!(rawMockContext instanceof Map<?, ?> mockContext)) {
            return List.of();
        }
        Object rawHints = mockContext.get("reasoningHints");
        if (!(rawHints instanceof List<?> hints)) {
            return List.of();
        }
        return hints.stream()
                .map(Object::toString)
                .filter(value -> !value.isBlank())
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractDeterministicRecipes(ReasoningLoopContext loopContext) {
        if (loopContext.getExecutionResult() == null) {
            return List.of();
        }
        Object rawRecipes = loopContext.getExecutionResult().getInformation().get("deterministicRepairRecipes");
        if (!(rawRecipes instanceof List<?> recipes)) {
            return List.of();
        }
        List<Map<String, Object>> converted = new ArrayList<>();
        for (Object recipe : recipes) {
            if (recipe instanceof Map<?, ?> map) {
                converted.add((Map<String, Object>) map);
            }
        }
        return converted;
    }

    private String stringify(Object value) {
        return value == null ? "" : value.toString();
    }

    private String combinedErrorText(CompilationErrorInfo errorInfo) {
        if (errorInfo == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendIfPresent(builder, errorInfo.getPrimaryMessage());
        appendIfPresent(builder, errorInfo.getCompilerOutput());
        appendIfPresent(builder, errorInfo.getStacktrace());
        return builder.toString();
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

    private boolean containsIgnoreCase(String source, String fragment) {
        return source != null
                && fragment != null
                && source.toLowerCase().contains(fragment.toLowerCase());
    }
}
