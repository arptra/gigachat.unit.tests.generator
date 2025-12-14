package com.gigachat.unit.tests.generator.reasoning.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;

import java.util.Arrays;
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
        prompt.append("You are a reasoning agent that debugs compilation failures for generated tests.\n");
        prompt.append("Think step-by-step (chain-of-thought) before choosing actions.\n\n");

        prompt.append("Allowed tools: \n");
        String tools = Arrays.stream(ToolActionType.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
        prompt.append(tools).append("\n\n");

        prompt.append("Return JSON response strictly in the following format:\n");
        prompt.append("{\n")
                .append("  \"reasoning\": [<chain-of-thought steps as strings>],\n")
                .append("  \"action\": {\n")
                .append("    \"type\": <ToolActionType>,\n")
                .append("    \"steps\": [ { \"type\": <ToolActionType>, \"arguments\": {..} } ],\n")
                .append("    \"singleStep\": { \"type\": <ToolActionType>, \"arguments\": {..} }\n")
                .append("  }\n")
                .append("}\n\n");

        prompt.append("Compilation error info (JSON):\n");
        prompt.append(asJson(loopContext.getErrorInfo())).append("\n\n");
        prompt.append("Project context summary (JSON):\n");
        prompt.append(asJson(loopContext.getProjectContextSummary())).append("\n\n");
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
