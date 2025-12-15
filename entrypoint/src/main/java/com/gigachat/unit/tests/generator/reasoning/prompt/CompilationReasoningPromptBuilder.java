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

        prompt.append("Use the following argument schema for each tool (all keys are case-sensitive):\n");
        prompt.append("- SHOW_FILE:   {\\\"filePath\\\": \\\"<relative or absolute path>\\\"}\n");
        prompt.append("- SHOW_IMPORTS:{\\\"filePath\\\": \\\"<relative or absolute path>\\\"}\n");
        prompt.append("- SEARCH_SYMBOL:{\\\"symbol\\\": \\\"<identifier or substring>\\\"}\n");
        prompt.append("- RUN_TEST:    { } (no arguments; the target test/method is provided in context)\n");
        prompt.append("- ADD_DEPENDENCY:{\\\"dependency\\\": \\\"<group:artifact:version or notation>\\\"}\n");
        prompt.append("- APPLY_PATCH: {\\\"filePath\\\": \\\"<relative or absolute path>\\\", \\\"patch\\\": \\\"<unified diff>\\\"}\n");
        prompt.append("- ADD_IMPORT:  {\\\"filePath\\\": \\\"<relative or absolute path>\\\", \\\"importFqcn\\\": \\\"<fully-qualified class>\\\"}\n");
        prompt.append("- RECOMPILE:   { } (no arguments)\n");
        prompt.append("- COMPOSITE:   {\\\"steps\\\": [{step objects using the schema above}]}\n\n");

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
