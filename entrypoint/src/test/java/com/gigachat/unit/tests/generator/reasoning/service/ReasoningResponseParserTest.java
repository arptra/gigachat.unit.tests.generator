package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReasoningResponseParserTest {

    @Test
    void shouldParseValidJson() {
        String json = """
                {
                  "decision": "APPLY_FIX",
                  "actions": [
                    {"type": "APPLY_PATCH", "args": {"filePath": "src/test/java/Sample.java", "patch": "@@ -1,1 +1,1 @@"}}
                  ],
                  "memory_updates": {
                    "knownMissingSymbols": ["Missing"],
                    "appliedFixSignatures": ["fix-1"]
                  }
                }
                """;

        ReasoningResponseParser parser = new ReasoningResponseParser();
        ReasoningResponse response = parser.parse(json);

        assertEquals("APPLY_FIX", response.getDecision());
        assertEquals(1, response.getActions().size());
        assertEquals("Missing", response.getMemoryUpdates().getKnownMissingSymbols().iterator().next());
    }

    @Test
    void shouldRejectEmptyPayload() {
        ReasoningResponseParser parser = new ReasoningResponseParser();
        ReasoningResponse response = parser.parse("   ");
        assertEquals("STOP", response.getDecision());
    }

    @Test
    void shouldParsePayloadWrappedInCodeFence() {
        String json = """
                ```json
                {"decision":"STOP","actions":[],"memory_updates":{}}
                ```
                """;

        ReasoningResponseParser parser = new ReasoningResponseParser();
        ReasoningResponse response = parser.parse(json);

        assertEquals("STOP", response.getDecision());
    }

    @Test
    void parseStrictShouldThrowOnInvalidPayload() {
        ReasoningResponseParser parser = new ReasoningResponseParser();
        assertThrows(RuntimeException.class, () -> parser.parseStrict("not-a-json"));
    }
}
