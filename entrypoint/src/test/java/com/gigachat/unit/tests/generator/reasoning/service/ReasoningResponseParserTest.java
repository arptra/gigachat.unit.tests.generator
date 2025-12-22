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
                  "decision": "APPLY_PATCH",
                  "actions": [
                    {"type": "APPLY_PATCH", "target": "src/test/java/Sample.java", "details": "@@ -1,1 +1,1 @@"}
                  ],
                  "memory_updates": {
                    "knownMissingSymbols": ["Missing"],
                    "appliedFixSignatures": ["fix-1"]
                  }
                }
                """;

        ReasoningResponseParser parser = new ReasoningResponseParser();
        ReasoningResponse response = parser.parse(json);

        assertEquals("APPLY_PATCH", response.getDecision());
        assertEquals(1, response.getActions().size());
        assertEquals("Missing", response.getMemoryUpdates().getKnownMissingSymbols().iterator().next());
    }

    @Test
    void shouldRejectEmptyPayload() {
        ReasoningResponseParser parser = new ReasoningResponseParser();
        assertThrows(IllegalArgumentException.class, () -> parser.parse("   "));
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
}
