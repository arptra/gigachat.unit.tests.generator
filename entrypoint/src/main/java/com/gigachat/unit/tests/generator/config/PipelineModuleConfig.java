package com.gigachat.unit.tests.generator.config;

import java.util.Locale;
import java.util.Map;

/**
 * Helper object that exposes strongly typed configuration flags for the pipeline module.
 */
public record PipelineModuleConfig(ParallelMode parallelMode,
                                   boolean compileEnabled,
                                   boolean executeEnabled,
                                   boolean snapshotsEnabled,
                                   boolean autoMockDetectionEnabled,
                                   boolean validateMockUsage,
                                   boolean excludeInternalCollections) {

    private static final String KEY_PARALLEL_MODE = "pipeline.parallelMode";
    private static final String KEY_COMPILE_ENABLED = "pipeline.compile.enabled";
    private static final String KEY_EXECUTE_ENABLED = "pipeline.execute.enabled";
    private static final String KEY_SNAPSHOTS_ENABLED = "pipeline.snapshots.enabled";
    private static final String KEY_AUTO_MOCK_DETECTION = "pipeline.autoMockDetectionEnabled";
    private static final String KEY_VALIDATE_MOCK_USAGE = "pipeline.validateMockUsage";
    private static final String KEY_EXCLUDE_INTERNAL_COLLECTIONS = "pipeline.excludeInternalCollections";

    public static PipelineModuleConfig from(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return defaults();
        }
        ParallelMode mode = parseMode(options.get(KEY_PARALLEL_MODE));
        boolean compileEnabled = parseBoolean(options.get(KEY_COMPILE_ENABLED), false);
        boolean executeEnabled = parseBoolean(options.get(KEY_EXECUTE_ENABLED), false);
        boolean snapshotsEnabled = parseBoolean(options.get(KEY_SNAPSHOTS_ENABLED), true);
        boolean autoMockDetection = parseBoolean(options.get(KEY_AUTO_MOCK_DETECTION), true);
        boolean validateMockUsage = parseBoolean(options.get(KEY_VALIDATE_MOCK_USAGE), true);
        boolean excludeInternalCollections = parseBoolean(options.get(KEY_EXCLUDE_INTERNAL_COLLECTIONS), true);
        return new PipelineModuleConfig(mode,
                compileEnabled,
                executeEnabled,
                snapshotsEnabled,
                autoMockDetection,
                validateMockUsage,
                excludeInternalCollections);
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
        return new PipelineModuleConfig(ParallelMode.NONE,
                false,
                false,
                true,
                true,
                true,
                true);
    }
}
