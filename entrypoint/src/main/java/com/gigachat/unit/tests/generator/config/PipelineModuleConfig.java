package com.gigachat.unit.tests.generator.config;

import java.util.Locale;
import java.util.Map;

/**
 * Helper object that exposes strongly typed configuration flags for the pipeline module.
 */
public record PipelineModuleConfig(ParallelMode parallelMode,
                                   boolean compileEnabled,
                                   boolean executeEnabled,
                                   boolean snapshotsEnabled) {

    private static final String KEY_PARALLEL_MODE = "pipeline.parallelMode";
    private static final String KEY_COMPILE_ENABLED = "pipeline.compile.enabled";
    private static final String KEY_EXECUTE_ENABLED = "pipeline.execute.enabled";
    private static final String KEY_SNAPSHOTS_ENABLED = "pipeline.snapshots.enabled";

    public static PipelineModuleConfig from(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return defaults();
        }
        ParallelMode mode = parseMode(options.get(KEY_PARALLEL_MODE));
        boolean compileEnabled = parseBoolean(options.get(KEY_COMPILE_ENABLED), true);
        boolean executeEnabled = parseBoolean(options.get(KEY_EXECUTE_ENABLED), true);
        boolean snapshotsEnabled = parseBoolean(options.get(KEY_SNAPSHOTS_ENABLED), true);
        return new PipelineModuleConfig(mode, compileEnabled, executeEnabled, snapshotsEnabled);
    }

    private static ParallelMode parseMode(Object raw) {
        if (raw == null) {
            return ParallelMode.NONE;
        }
        String value = raw.toString().trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "class" -> ParallelMode.CLASS;
            case "method" -> ParallelMode.METHOD;
            case "full" -> ParallelMode.FULL;
            default -> ParallelMode.NONE;
        };
    }

    private static boolean parseBoolean(Object raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(raw.toString());
    }

    private static PipelineModuleConfig defaults() {
        return new PipelineModuleConfig(ParallelMode.NONE, true, true, true);
    }
}
