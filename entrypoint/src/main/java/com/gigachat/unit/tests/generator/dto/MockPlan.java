package com.gigachat.unit.tests.generator.dto;

import java.util.List;

/**
 * Contains the mock targets and strategy required to set up a generated test.
 */
public record MockPlan(List<MockTarget> targets,
                       MockStrategy strategy,
                       List<String> shouldMock,
                       List<String> shouldNotMock) {

    public MockPlan {
        targets = targets == null ? List.of() : List.copyOf(targets);
        strategy = strategy == null ? MockStrategy.NONE : strategy;
        shouldMock = shouldMock == null ? List.of() : List.copyOf(shouldMock);
        shouldNotMock = shouldNotMock == null ? List.of() : List.copyOf(shouldNotMock);
    }
}
