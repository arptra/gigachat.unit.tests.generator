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
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Response JSON is empty");
        }
        try {
            String cleaned = sanitize(json);
            return mapper.readValue(cleaned, ReasoningResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to parse reasoning response JSON", exception);
        }
    }

    private String sanitize(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            } else {
                trimmed = trimmed.substring(3);
            }
            int fence = trimmed.lastIndexOf("``` ");
            int closing = fence >= 0 ? fence : trimmed.lastIndexOf("```");
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
}
