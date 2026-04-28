package com.gigachat.unit.tests.generator.reasoning.orchestrator;

import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.reasoning.model.AgentState;
import com.gigachat.unit.tests.generator.resources.ReasoningLoopPolicyCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateGraphControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldApplyDecisionDrivenTransitionAndTraceIt() throws Exception {
        PipelineLogger logger = new PipelineLogger(tempDir);
        StateGraphController controller = new StateGraphController(
                logger,
                "shouldCompile",
                new ReasoningLoopPolicyCatalog().compilationPolicy(),
                AgentState.S0_INIT,
                "starting compile loop");

        controller.move("RESULT", AgentState.S2_COMPILATION_FAILED, "compile failed");
        controller.moveForDecision("TRANSITION",
                "APPLY_FIX",
                AgentState.S6_GIVE_UP,
                "fix applied");

        assertEquals(AgentState.S4_FIX_APPLIED, controller.memory().getState());
        String trace = Files.readString(tempDir.resolve(".agent/logs/state-trace.log"));
        assertTrue(trace.contains("[STATE] method=shouldCompile state=S0_INIT"));
        assertTrue(trace.contains("[TRANSITION] method=shouldCompile state=S4_FIX_APPLIED"));
    }
}
