package com.gigachat.unit.tests.generator.reasoning.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixSessionTest {

    @Test
    void shouldStartInObserveAndRecordInitialTransition() {
        FixSession session = new FixSession("session-1", Path.of("src/test/java/example/Test.java"), "testMethod");

        assertEquals(FixSessionState.OBSERVE, session.getState());
        assertEquals(1, session.getJournal().getTransitions().size());
        assertEquals(FixSessionState.OBSERVE, session.getJournal().getTransitions().get(0).getTo());
    }

    @Test
    void shouldAllowHappyPathTransitions() {
        FixSession session = new FixSession("session-2", Path.of("src/test/java/example/Test.java"), "testMethod");

        session.transitionTo(FixSessionState.DIAGNOSE, "compile_failed");
        session.transitionTo(FixSessionState.PLAN, "plan_fix");
        session.transitionTo(FixSessionState.APPLY, "apply_fix");
        session.transitionTo(FixSessionState.VERIFY, "verify_fix");
        session.transitionTo(FixSessionState.LEARN, "learn");
        session.transitionTo(FixSessionState.DONE, "compiled");

        assertEquals(FixSessionState.DONE, session.getState());
        assertEquals(7, session.getJournal().getTransitions().size());
    }

    @Test
    void shouldRejectInvalidTransition() {
        FixSession session = new FixSession("session-3", Path.of("src/test/java/example/Test.java"), "testMethod");

        assertThrows(IllegalStateException.class, () -> session.transitionTo(FixSessionState.APPLY, "skip_steps"));
    }

    @Test
    void shouldPersistMemoryAndExecutionResultInSession() {
        FixSession session = new FixSession("session-4", Path.of("src/test/java/example/Test.java"), "testMethod");

        session.getMemory().addKnownMissingSymbol("MissingType");
        session.mergeExecutionResult(new ActionExecutionResult(Map.of("symbolSearchResults", "found"), java.util.List.of("SEARCH_SYMBOL MissingType")));

        assertTrue(session.getMemory().getKnownMissingSymbols().contains("MissingType"));
        assertTrue(session.getCumulativeExecutionResult().getInformation().containsKey("symbolSearchResults"));
        assertEquals(1, session.getCumulativeExecutionResult().getPerformedActions().size());
    }
}
