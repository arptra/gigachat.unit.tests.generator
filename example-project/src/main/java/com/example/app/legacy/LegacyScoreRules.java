package com.example.app.legacy;

import com.example.app.util.MathUtil;

public final class LegacyScoreRules {

    private LegacyScoreRules() {
    }

    public static int normalizeSignal(int rawSignal, int attempts) {
        int boundedAttempts = Math.max(1, Math.min(3, attempts));
        return MathUtil.sum(rawSignal, MathUtil.factorial(boundedAttempts));
    }

    public static boolean shouldEscalate(int normalizedSignal, boolean activeUser) {
        return activeUser ? normalizedSignal >= 10 : normalizedSignal >= 7;
    }

    public static int reboundFactor(int attempts, boolean activeUser) {
        int baseline = MathUtil.max(1, attempts + (activeUser ? 2 : 1));
        return MathUtil.sum(baseline, MathUtil.factorial(2));
    }
}
