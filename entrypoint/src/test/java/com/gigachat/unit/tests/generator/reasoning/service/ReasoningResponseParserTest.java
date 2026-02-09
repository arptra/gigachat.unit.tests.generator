package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.model.ToolAction;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    void shouldNormalizeLegacyActionShape() {
        String json = """
                {
                  "decision": "apply_fix",
                  "actions": [
                    {"type": "add_import", "target": "entrypoint/src/test/java/example/SampleTest.java", "details": "org.junit.jupiter.api.Test"}
                  ],
                  "memory_updates": {}
                }
                """;

        ReasoningResponseParser parser = new ReasoningResponseParser();
        ReasoningResponse response = parser.parse(json);
        ToolAction toolAction = response.toToolAction();

        assertEquals("APPLY_FIX", response.getDecision());
        assertNotNull(toolAction);
        assertEquals(ToolActionType.ADD_IMPORT, toolAction.getSingleStep().getType());
        assertEquals("entrypoint/src/test/java/example/SampleTest.java", toolAction.getSingleStep().getArguments().get("path"));
        assertEquals("org.junit.jupiter.api.Test", toolAction.getSingleStep().getArguments().get("import"));
    }
}
