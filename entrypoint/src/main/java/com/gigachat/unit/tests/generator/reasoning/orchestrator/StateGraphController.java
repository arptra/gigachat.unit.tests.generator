package com.gigachat.unit.tests.generator.reasoning.orchestrator;

import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.reasoning.model.AgentState;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningMemory;
import com.gigachat.unit.tests.generator.resources.ReasoningLoopPolicy;

import java.util.Objects;

/**
 * Thin runtime controller over {@link ReasoningMemory} that applies resource-backed transition
 * rules and records state/decision/action trace events in one place.
 */
public class StateGraphController {

    private final ReasoningMemory memory;
    private final ReasoningLoopPolicy loopPolicy;
    private final PipelineLogger logger;
    private final String methodName;

    public StateGraphController(PipelineLogger logger,
                                String methodName,
                                ReasoningLoopPolicy loopPolicy,
                                AgentState initialState,
                                String startMessage) {
        this.memory = new ReasoningMemory();
        this.loopPolicy = Objects.requireNonNull(loopPolicy, "loopPolicy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.methodName = methodName;
        applyDefaultForbiddenActions();
        move("STATE", initialState, startMessage);
    }

    public ReasoningMemory memory() {
        return memory;
    }

    public void incrementAttempt() {
        memory.incrementAttempt();
    }

    public void addErrorSignature(String signature) {
        memory.addErrorSignature(signature);
    }

    public int countOccurrences(String signature) {
        return memory.countOccurrences(signature);
    }

    public void addForbiddenAction(String action) {
        memory.addForbiddenAction(action);
    }

    public void decrementContextBudget() {
        memory.decrementContextBudget();
    }

    public void resetContextBudget() {
        memory.resetContextBudget();
    }

    public int contextBudgetRemaining() {
        return memory.getContextRequestBudgetRemaining();
    }

    public AgentState move(String category, AgentState nextState, String message) {
        memory.setState(nextState);
        logger.trace(category, methodName, nextState.name(), message);
        return nextState;
    }

    public AgentState moveForDecision(String category, String decision, AgentState fallback, String message) {
        return move(category, loopPolicy.nextStateForDecision(decision, fallback), message);
    }

    public void logDecision(String message) {
        logger.trace("DECISION", methodName, memory.getState().name(), message);
    }

    public void logAction(String message) {
        logger.trace("ACTION", methodName, memory.getState().name(), message);
    }

    private void applyDefaultForbiddenActions() {
        if (loopPolicy.defaultForbiddenActions() == null) {
            return;
        }
        loopPolicy.defaultForbiddenActions().forEach(memory::addForbiddenAction);
    }
}
