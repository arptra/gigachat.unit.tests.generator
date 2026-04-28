package com.gigachat.unit.tests.generator.resources;

import com.gigachat.unit.tests.generator.reasoning.model.AgentState;

import java.util.List;
import java.util.Map;

/**
 * Resource-driven loop policy for compilation/execution reasoning loops.
 */
public record ReasoningLoopPolicy(int maxIterations,
                                  int repeatedSignatureThreshold,
                                  int stopGraceRounds,
                                  List<String> defaultForbiddenActions,
                                  Map<String, AgentState> decisionNextStates,
                                  FalseDependencyPolicy falseDependencyPolicy,
                                  Map<String, String> runtimeRegressionPolicy) {

    public AgentState nextStateForDecision(String decision, AgentState fallback) {
        if (decision == null || decision.isBlank()) {
            return fallback;
        }
        return decisionNextStates.getOrDefault(decision, fallback);
    }

    public String runtimeRegressionPolicyValue(String key, String fallback) {
        if (key == null || key.isBlank() || runtimeRegressionPolicy == null) {
            return fallback;
        }
        return runtimeRegressionPolicy.getOrDefault(key, fallback);
    }

    public int runtimeRegressionPolicyInt(String key, int fallback) {
        String value = runtimeRegressionPolicyValue(key, Integer.toString(fallback));
        try {
            return Math.max(Integer.parseInt(value), 0);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
