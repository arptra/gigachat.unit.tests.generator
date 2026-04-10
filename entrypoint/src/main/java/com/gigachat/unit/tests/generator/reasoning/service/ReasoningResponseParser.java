package com.gigachat.unit.tests.generator.reasoning.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;

public class ReasoningResponseParser {

    private final ObjectMapper mapper;

    public ReasoningResponseParser() {
        this.mapper = new ObjectMapper();
    }

    public ReasoningResponse parse(String json) {
        try {
            return parseStrict(json);
        } catch (Exception ignored) {
            return stopFallback();
        }
    }

    public ReasoningResponse parseStrict(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Reasoning response is empty");
        }
        try {
            String cleaned = sanitize(json);
            ReasoningResponse response = mapper.readValue(cleaned, ReasoningResponse.class);
            if (response.getDecision() != null) {
                response.setDecision(response.getDecision().toUpperCase());
            }
            validate(response);
            return response;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to parse reasoning response", exception);
        }
    }

    private String sanitize(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            trimmed = firstNewline > 0 ? trimmed.substring(firstNewline + 1) : trimmed.substring(3);
            int closing = Math.max(trimmed.lastIndexOf("``` "), trimmed.lastIndexOf("```"));
            if (closing >= 0) {
                trimmed = trimmed.substring(0, closing);
            }
            trimmed = trimmed.trim();
        }
        if (!trimmed.startsWith("{")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end >= start) {
                trimmed = trimmed.substring(start, end + 1);
            }
        }
        return trimmed;
    }

    private void validate(ReasoningResponse response) {
        if (response.getDecision() == null || response.getDecision().isBlank()) {
            throw new IllegalArgumentException("Missing decision");
        }
        String decision = response.getDecision();
        boolean validDecision = decision.equals("REQUEST_CONTEXT")
                || decision.equals("APPLY_FIX")
                || decision.equals("MARK_FALSE_DEPENDENCY")
                || decision.equals("STOP");
        if (!validDecision) {
            throw new IllegalArgumentException("Unsupported decision: " + decision);
        }
        boolean actionsEmpty = response.getActions() == null || response.getActions().isEmpty();
        if (!decision.equals("STOP") && !decision.equals("MARK_FALSE_DEPENDENCY") && actionsEmpty) {
            throw new IllegalArgumentException("Actions required for decision " + decision);
        }
        if (response.getActions() == null) {
            response.setActions(java.util.List.of());
        }
        if (response.getMemoryUpdates() == null) {
            response.setMemoryUpdates(new ReasoningResponse.MemoryUpdate());
        }
    }

    private ReasoningResponse stopFallback() {
        ReasoningResponse response = new ReasoningResponse();
        response.setDecision("STOP");
        response.setActions(java.util.List.of());
        return response;
    }
}
