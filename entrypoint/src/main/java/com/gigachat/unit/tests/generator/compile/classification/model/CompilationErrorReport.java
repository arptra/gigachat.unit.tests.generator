package com.gigachat.unit.tests.generator.compile.classification.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Aggregated view over parsed and classified compilation errors.
 */
public class CompilationErrorReport {

    private final List<CompilationError> errors;
    private final Map<CompilationErrorClass, List<CompilationError>> grouped;
    private final Map<CompilationErrorClass, Integer> counts;
    private final List<String> topMissingPackages;
    private final List<String> topMissingSymbols;
    private final String rawCompilerOutput;

    public CompilationErrorReport(List<CompilationError> errors,
                                  String rawCompilerOutput) {
        this.errors = errors == null ? List.of() : List.copyOf(errors);
        this.rawCompilerOutput = rawCompilerOutput;
        this.grouped = buildGrouped();
        this.counts = buildCounts();
        this.topMissingPackages = computeTopMissingPackages();
        this.topMissingSymbols = computeTopMissingSymbols();
    }

    public List<CompilationError> getErrors() {
        return errors;
    }

    public Map<CompilationErrorClass, List<CompilationError>> getGrouped() {
        return grouped;
    }

    public Map<CompilationErrorClass, Integer> getCounts() {
        return counts;
    }

    public List<String> getTopMissingPackages() {
        return topMissingPackages;
    }

    public List<String> getTopMissingSymbols() {
        return topMissingSymbols;
    }

    public String getRawCompilerOutput() {
        return rawCompilerOutput;
    }

    public boolean has(CompilationErrorClass clazz) {
        return counts.getOrDefault(clazz, 0) > 0;
    }

    public List<CompilationError> get(CompilationErrorClass clazz) {
        return grouped.getOrDefault(clazz, List.of());
    }

    private Map<CompilationErrorClass, List<CompilationError>> buildGrouped() {
        Map<CompilationErrorClass, List<CompilationError>> map = errors.stream()
                .collect(Collectors.groupingBy(CompilationError::getErrorClass, () -> new EnumMap<>(CompilationErrorClass.class), Collectors.toList()));
        for (CompilationErrorClass clazz : CompilationErrorClass.values()) {
            map.putIfAbsent(clazz, List.of());
        }
        return Collections.unmodifiableMap(map);
    }

    private Map<CompilationErrorClass, Integer> buildCounts() {
        Map<CompilationErrorClass, Integer> map = new EnumMap<>(CompilationErrorClass.class);
        grouped.forEach((key, value) -> map.put(key, value.size()));
        return Collections.unmodifiableMap(map);
    }

    private List<String> computeTopMissingPackages() {
        return errors.stream()
                .map(CompilationError::getPackageName)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private List<String> computeTopMissingSymbols() {
        return errors.stream()
                .map(CompilationError::getSymbol)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
