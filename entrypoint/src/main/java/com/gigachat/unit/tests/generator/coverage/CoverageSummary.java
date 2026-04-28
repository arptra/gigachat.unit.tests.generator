package com.gigachat.unit.tests.generator.coverage;

public record CoverageSummary(String targetClassName,
                              String targetMethodName,
                              int coveredLines,
                              int missedLines,
                              int coveredBranches,
                              int missedBranches) {

    public int totalLines() {
        return Math.max(coveredLines + missedLines, 0);
    }

    public int totalBranches() {
        return Math.max(coveredBranches + missedBranches, 0);
    }

    public double lineCoveragePercent() {
        return percentage(coveredLines, totalLines());
    }

    public double branchCoveragePercent() {
        return percentage(coveredBranches, totalBranches());
    }

    public double combinedCoveragePercent() {
        return percentage(coveredLines + coveredBranches, totalLines() + totalBranches());
    }

    public boolean fullyCovered() {
        return missedLines <= 0 && missedBranches <= 0;
    }

    public boolean meetsGoal(int goalPercent) {
        return combinedCoveragePercent() + 1e-9 >= goalPercent;
    }

    public String toFeedbackMessage() {
        return "Coverage for " + targetClassName + "." + targetMethodName
                + ": combined=" + formatPercent(combinedCoveragePercent()) + "%"
                + ", lines=" + formatPercent(lineCoveragePercent()) + "%"
                + ", branches=" + formatPercent(branchCoveragePercent()) + "%"
                + ": missed lines=" + missedLines
                + ", missed branches=" + missedBranches
                + ", covered lines=" + coveredLines
                + ", covered branches=" + coveredBranches;
    }

    public String toGoalFeedbackMessage(int goalPercent) {
        return "Coverage goal " + goalPercent + "% for " + targetClassName + "." + targetMethodName
                + ": current combined=" + formatPercent(combinedCoveragePercent()) + "%"
                + ", lines=" + formatPercent(lineCoveragePercent()) + "%"
                + ", branches=" + formatPercent(branchCoveragePercent()) + "%"
                + ", missed lines=" + missedLines
                + ", missed branches=" + missedBranches
                + ", covered lines=" + coveredLines
                + ", covered branches=" + coveredBranches;
    }

    private double percentage(int covered, int total) {
        if (total <= 0) {
            return 100.0;
        }
        return (covered * 100.0) / total;
    }

    private String formatPercent(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
