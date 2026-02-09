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
                  "hypothesis": "Unique symbol resolution indicates missing import.",
                  "expected_delta": {"compile_errors": -1, "symbol": "Missing"},
                  "actions": [
                    {"type": "APPLY_PATCH", "preconditions": ["patch_applies_cleanly"], "args": {"filePath": "src/test/java/Sample.java", "patch": "@@ -1,1 +1,1 @@"}}
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
                  "hypothesis": "Resolved candidate provides import.",
                  "expected_delta": {"compile_errors": -1, "symbol": "Test"},
                  "actions": [
                    {"type": "add_import", "preconditions": ["symbol_resolved_unique"], "target": "entrypoint/src/test/java/example/SampleTest.java", "details": "org.junit.jupiter.api.Test"}
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

    @Test
    void shouldRejectApplyFixWithoutPreconditions() {
        String json = """
                {
                  "decision": "APPLY_FIX",
                  "hypothesis": "Missing import.",
                  "expected_delta": {"compile_errors": -1},
                  "actions": [
                    {"type": "ADD_IMPORT", "args": {"path": "src/test/java/Sample.java", "import": "org.junit.jupiter.api.Test"}}
                  ],
                  "memory_updates": {}
                }
                """;

        ReasoningResponseParser parser = new ReasoningResponseParser();
        ReasoningResponse response = parser.parse(json);
        assertEquals("STOP", response.getDecision());
    }
}
