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

        prompt.append("You are a deterministic reasoning agent fixing generated TESTS only. ")
                .append("Production code must never be modified. ")
                .append("Decide on the next action only; do not include any chain-of-thought.\n\n");

        prompt.append("AGENT STATE: ").append(memory.getState()).append("\n");
        prompt.append("ATTEMPT: ").append(memory.getAttempt()).append("\n");
        prompt.append("KNOWN MISSING SYMBOLS: ").append(memory.getKnownMissingSymbols()).append("\n");
        prompt.append("RECENT ERROR SIGNATURES: ").append(memory.getRecentErrorSignatures()).append("\n");
        prompt.append("APPLIED FIXES: ").append(memory.getAppliedFixSignatures()).append("\n");
        prompt.append("FORBIDDEN ACTIONS: ").append(forbidden).append("\n\n");

        prompt.append("Allowed actions: ").append(allowedTools).append("\n");
        prompt.append("Output schema (strict JSON):\n")
                .append("{\n")
                .append("  \"decision\": \"APPLY_PATCH | ADD_IMPORT | MARK_FALSE_DEPENDENCY | STOP\",\n")
                .append("  \"actions\": [ {\"type\": \"APPLY_PATCH|ADD_IMPORT\", \"target\": \"<file path>\", \"details\": \"<patch or import>\"} ],\n")
                .append("  \"memory_updates\": { \"knownMissingSymbols\": [\"...\"], \"appliedFixSignatures\": [\"...\"] }\n")
                .append("}\n\n");

        prompt.append("Compilation error info (JSON):\n");
        prompt.append(asJson(loopContext.getErrorInfo())).append("\n\n");
        if (loopContext.getErrorReport() != null) {
            prompt.append("Classified compilation errors (JSON):\n");
            prompt.append(asJson(loopContext.getErrorReport())).append("\n\n");
        }
        prompt.append("Project context summary (JSON):\n");
        prompt.append(asJson(loopContext.getProjectContextSummary())).append("\n\n");
        if (loopContext.getExecutionResult() != null
                && (!loopContext.getExecutionResult().getInformation().isEmpty()
                || !loopContext.getExecutionResult().getPerformedActions().isEmpty())) {
            prompt.append("Execution log from previous iteration (JSON):\n");
            prompt.append(asJson(loopContext.getExecutionResult().toPromptPayload())).append("\n");
        }
        prompt.append("Do not include explanations. Respond only with JSON matching the schema.");

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
