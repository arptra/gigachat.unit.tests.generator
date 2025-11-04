package com.testagent.entrypoint.pipeline.helpers.analyze;

import com.gigachat.unit.tests.generator.config.AgentConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Normalised configuration extracted from {@link AgentConfig} that controls analysis behaviour.
 */
public record AnalysisOptions(boolean includeStatic,
                              int maxChainDepth,
                              List<String> excludePackages) {

    private static final String KEY_INCLUDE_STATIC = "analysis.includeStatic";
    private static final String KEY_MAX_CHAIN_DEPTH = "analysis.maxChainDepth";
    private static final String KEY_EXCLUDE_PACKAGES = "analysis.excludePackages";

    private static final List<String> DEFAULT_EXCLUDE = List.of("java.lang", "java.util", "org.slf4j");

    public static AnalysisOptions from(AgentConfig config) {
        Map<String, Object> options = config == null ? Map.of() : config.getModuleOptions();
        boolean includeStatic = parseBoolean(options.get(KEY_INCLUDE_STATIC), true);
        int maxChainDepth = parseInt(options.get(KEY_MAX_CHAIN_DEPTH), 3);
        List<String> exclude = new ArrayList<>(DEFAULT_EXCLUDE);
        exclude.addAll(parsePackages(options.get(KEY_EXCLUDE_PACKAGES)));
        List<String> normalised = exclude.stream()
                .filter(entry -> entry != null && !entry.isBlank())
                .map(entry -> entry.trim().replaceAll("\\*$", ""))
                .distinct()
                .toList();
        return new AnalysisOptions(includeStatic, maxChainDepth, normalised);
    }

    public boolean isExcluded(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return false;
        }
        String name = qualifiedName.trim();
        for (String prefix : excludePackages) {
            if (name.startsWith(prefix + '.')) {
                return true;
            }
            if (name.equals(prefix)) {
                return true;
            }
        }
        return false;
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

    private static int parseInt(Object raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        try {
            return Math.max(1, Integer.parseInt(raw.toString().trim()));
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private static List<String> parsePackages(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof Collection<?> collection) {
            Set<String> values = new LinkedHashSet<>();
            for (Object entry : collection) {
                if (entry != null) {
                    values.add(entry.toString());
                }
            }
            return new ArrayList<>(values);
        }
        String value = raw.toString().trim();
        if (value.isEmpty()) {
            return List.of();
        }
        if (value.contains(",")) {
            String[] parts = value.split(",");
            List<String> result = new ArrayList<>();
            for (String part : parts) {
                if (!part.isBlank()) {
                    result.add(part.trim());
                }
            }
            return result;
        }
        return List.of(value);
    }
}
