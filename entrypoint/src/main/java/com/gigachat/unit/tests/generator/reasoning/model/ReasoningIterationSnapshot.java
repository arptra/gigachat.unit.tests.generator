package com.gigachat.unit.tests.generator.reasoning.model;

import java.util.List;
import java.util.Objects;

/**
 * Immutable trace record of one reasoning iteration.
 */
public class ReasoningIterationSnapshot {

    private final String sessionId;
    private final int iteration;
    private final AgentState state;
    private final String errorSignature;
    private final String decision;
    private final List<String> plannedActions;
    private final List<String> performedActions;
    private final String primaryError;
    private final String outcome;

    public ReasoningIterationSnapshot(String sessionId,
                                      int iteration,
                                      AgentState state,
                                      String errorSignature,
                                      String decision,
                                      List<String> plannedActions,
                                      List<String> performedActions,
                                      String primaryError,
                                      String outcome) {
        this.sessionId = sessionId;
        this.iteration = iteration;
        this.state = state;
        this.errorSignature = errorSignature;
        this.decision = decision;
        this.plannedActions = plannedActions == null ? List.of() : List.copyOf(plannedActions);
        this.performedActions = performedActions == null ? List.of() : List.copyOf(performedActions);
        this.primaryError = primaryError;
        this.outcome = outcome;
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getIteration() {
        return iteration;
    }

    public AgentState getState() {
        return state;
    }

    public String getErrorSignature() {
        return errorSignature;
    }

    public String getDecision() {
        return decision;
    }

    public List<String> getPlannedActions() {
        return plannedActions;
    }

    public List<String> getPerformedActions() {
        return performedActions;
    }

    public String getPrimaryError() {
        return primaryError;
    }

    public String getOutcome() {
        return outcome;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ReasoningIterationSnapshot that = (ReasoningIterationSnapshot) o;
        return iteration == that.iteration
                && Objects.equals(sessionId, that.sessionId)
                && state == that.state
                && Objects.equals(errorSignature, that.errorSignature)
                && Objects.equals(decision, that.decision)
                && Objects.equals(plannedActions, that.plannedActions)
                && Objects.equals(performedActions, that.performedActions)
                && Objects.equals(primaryError, that.primaryError)
                && Objects.equals(outcome, that.outcome);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, iteration, state, errorSignature, decision, plannedActions, performedActions, primaryError, outcome);
    }

    @Override
    public String toString() {
        return "ReasoningIterationSnapshot{" +
                "sessionId='" + sessionId + '\'' +
                ", iteration=" + iteration +
                ", state=" + state +
                ", errorSignature='" + errorSignature + '\'' +
                ", decision='" + decision + '\'' +
                ", plannedActions=" + plannedActions +
                ", performedActions=" + performedActions +
                ", primaryError='" + primaryError + '\'' +
                ", outcome='" + outcome + '\'' +
                '}';
    }
}
