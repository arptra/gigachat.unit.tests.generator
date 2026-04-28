package com.gigachat.unit.tests.generator.reasoning.model;

import com.gigachat.unit.tests.generator.resources.ReasoningStagePolicy;
import com.gigachat.unit.tests.generator.resources.ReasoningStagePolicyCatalog;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * High-level pipeline stages that require deterministic reasoning.
 *
 * Policy details for each stage are loaded from editable resources.
 */
public enum ReasoningStage {
    COMPILATION,
    EXECUTION,
    COVERAGE;

    private static final ReasoningStagePolicyCatalog POLICY_CATALOG = new ReasoningStagePolicyCatalog();

    public String objective() {
        return policy().objective();
    }

    public List<String> protocol() {
        return policy().protocol();
    }

    public List<String> allowedDecisions() {
        return policy().allowedDecisions();
    }

    public List<ToolActionType> allowedToolActions(ReasoningMemory memory) {
        Set<String> forbidden = memory == null ? Set.of() : memory.getForbiddenActions();
        return policy().allowedToolActions().stream()
                .filter(type -> !forbidden.contains(type.name()))
                .collect(Collectors.toList());
    }

    public String describeAllowedDecisions() {
        return String.join(" | ", allowedDecisions());
    }

    public String describeAllowedActions(ReasoningMemory memory) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        allowedToolActions(memory).forEach(type -> names.add(type.name()));
        return String.join(", ", names);
    }

    private ReasoningStagePolicy policy() {
        return POLICY_CATALOG.policyFor(this);
    }
}
