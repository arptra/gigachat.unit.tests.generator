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
            return mapper.readValue(json, ReasoningResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to parse reasoning response JSON", exception);
        }
    }
}
