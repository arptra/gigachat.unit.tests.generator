package com.gigachat.unit.tests.generator.coverage;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record CoverageResult(boolean success,
                             boolean reportGenerated,
                             CoverageSummary summary,
                             Path xmlReport,
                             String stdout,
                             String stderr) {

    public CoverageResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }

    public boolean fullyCovered() {
        return success && reportGenerated && summary != null && summary.fullyCovered();
    }

    public boolean meetsGoal(int goalPercent) {
        return success && reportGenerated && summary != null && summary.meetsGoal(goalPercent);
    }

    public Optional<Path> xmlReportOptional() {
        return Optional.ofNullable(xmlReport);
    }

    public String describeFailure() {
        if (!success) {
            return stderr.isBlank() ? "Coverage task failed" : stderr;
        }
        if (!reportGenerated) {
            return "JaCoCo XML report was not generated";
        }
        if (summary == null) {
            return "Coverage summary for target method is unavailable";
        }
        return summary.toFeedbackMessage();
    }

    public String describeFailure(int goalPercent) {
        if (!success) {
            return stderr.isBlank() ? "Coverage task failed" : stderr;
        }
        if (!reportGenerated) {
            return "JaCoCo XML report was not generated";
        }
        if (summary == null) {
            return "Coverage summary for target method is unavailable";
        }
        return summary.toGoalFeedbackMessage(goalPercent);
    }
}
