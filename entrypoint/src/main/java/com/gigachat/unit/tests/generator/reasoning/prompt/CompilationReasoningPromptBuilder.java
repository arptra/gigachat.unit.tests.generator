package com.gigachat.unit.tests.generator.reasoning.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningMemory;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class CompilationReasoningPromptBuilder {

    private final ObjectWriter writer;

    public CompilationReasoningPromptBuilder() {
        ObjectMapper mapper = new ObjectMapper();
        this.writer = mapper.writerWithDefaultPrettyPrinter();
    }

    public String buildPrompt(ReasoningLoopContext loopContext) {
        if (loopContext == null) {
            throw new IllegalArgumentException("loopContext");
        }
        StringBuilder prompt = new StringBuilder();
        ReasoningMemory memory = loopContext.getMemory();
        Set<String> forbidden = memory.getForbiddenActions();
        String allowedTools = Arrays.stream(ToolActionType.values())
                .map(Enum::name)
                .filter(name -> !forbidden.contains(name))
                .collect(Collectors.joining(", "));

        prompt.append("You are a deterministic agent fixing GENERATED TESTS only. ")
                .append("Production code must NEVER be modified or invented. ")
                .append("If information is missing, request context via READ_* or SEARCH_SYMBOL before proposing fixes. ")
                .append("If context budget is exhausted or symbol is absent from sources, STOP safely.\n\n");

        prompt.append("STATE: ").append(memory.getState())
                .append(" | ATTEMPT: ").append(memory.getAttempt())
                .append(" | CONTEXT_BUDGET: ").append(memory.getContextRequestBudgetRemaining()).append("\n");
        prompt.append("KNOWN_MISSING_SYMBOLS: ").append(memory.getKnownMissingSymbols()).append("\n");
        prompt.append("APPLIED_FIXES: ").append(memory.getAppliedFixSignatures()).append("\n");
        prompt.append("RECENT_ERRORS: ").append(memory.getRecentErrorSignatures()).append("\n");
        prompt.append("CONTEXT_CACHE_KEYS: ").append(memory.getContextCache().keySet()).append("\n");
        prompt.append("FORBIDDEN_ACTIONS: ").append(forbidden).append("\n\n");

        prompt.append("Allowed tool actions: ").append(allowedTools).append("\n");
        prompt.append("Required JSON response ONLY:\n")
                .append("{\n")
                .append("  \"decision\": \"REQUEST_CONTEXT | APPLY_FIX | MARK_FALSE_DEPENDENCY | STOP\",\n")
                .append("  \"actions\": [ { \"type\": \"READ_METHOD|READ_CLASS|LIST_METHODS|SEARCH_SYMBOL|SHOW_FILE|APPLY_PATCH|ADD_IMPORT|RECOMPILE\", \"args\": { ... } } ],\n")
                .append("  \"memory_updates\": { \"knownMissingSymbols\": [], \"appliedFixSignatures\": [], \"contextCache\": {\"key\":\"value\"} }\n")
                .append("}\n");
        prompt.append("Return JSON only. No explanations.\n\n");

        prompt.append("Compilation error info (JSON):\n").append(asJson(loopContext.getErrorInfo())).append("\n\n");
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

    private String asJson(Object value) {
        try {
            return writer.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialise prompt payload", exception);
        }
    }
}
