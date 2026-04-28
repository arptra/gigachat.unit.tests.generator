package com.gigachat.unit.tests.generator.coverage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageSummaryTest {

    @Test
    void shouldComputeCombinedCoveragePercent() {
        CoverageSummary summary = new CoverageSummary("NotificationService", "sendWelcome", 3, 1, 1, 1);

        assertEquals(75.0, summary.lineCoveragePercent(), 0.001);
        assertEquals(50.0, summary.branchCoveragePercent(), 0.001);
        assertEquals(66.666, summary.combinedCoveragePercent(), 0.01);
        assertTrue(summary.meetsGoal(60));
        assertFalse(summary.meetsGoal(80));
    }

    @Test
    void shouldTreatMethodsWithoutCountersAsFullySatisfied() {
        CoverageSummary summary = new CoverageSummary("NotificationService", "sendWelcome", 0, 0, 0, 0);

        assertEquals(100.0, summary.combinedCoveragePercent(), 0.001);
        assertTrue(summary.meetsGoal(100));
    }
}
