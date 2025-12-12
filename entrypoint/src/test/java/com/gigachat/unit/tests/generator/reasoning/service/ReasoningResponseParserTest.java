package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.model.ToolAction;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionStep;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReasoningResponseParserTest {

    @Test
    void shouldParseValidJson() {
        String json = """
                {
                  "reasoning": ["Step 1", "Step 2"],
                  "action": {
                    "type": "COMPOSITE",
                    "steps": [
                      {
                        "type": "SHOW_FILE",
                        "arguments": {"path": "Sample.java"}
                      }
                    ],
                    "singleStep": {
                      "type": "RECOMPILE",
                      "arguments": {}
                    }
                  }
                }
                """;

        ReasoningResponseParser parser = new ReasoningResponseParser();
        ReasoningResponse response = parser.parse(json);

        assertEquals(List.of("Step 1", "Step 2"), response.getReasoning());
        ToolAction expectedAction = new ToolAction(
                ToolActionType.COMPOSITE,
                List.of(new ToolActionStep(ToolActionType.SHOW_FILE, Map.of("path", "Sample.java"))),
                new ToolActionStep(ToolActionType.RECOMPILE, Map.of())
        );
        assertEquals(expectedAction, response.getAction());
    }

    @Test
    void shouldRejectEmptyPayload() {
        ReasoningResponseParser parser = new ReasoningResponseParser();
        assertThrows(IllegalArgumentException.class, () -> parser.parse("   "));
    }
}
