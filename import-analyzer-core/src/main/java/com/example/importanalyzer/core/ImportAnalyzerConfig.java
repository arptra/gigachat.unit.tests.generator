package com.example.importanalyzer.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ImportAnalyzerConfig {
    private final List<Path> sourceRoots;
    private final List<Path> testSourceRoots;
    private final Path projectRoot;
    private final boolean includeDependencies;
    private final int threads;
    private final Path indexCachePath;
    private final boolean reuseIndex;
    private final boolean cacheEnabled;

    public ImportAnalyzerConfig(List<Path> sourceRoots, List<Path> testSourceRoots, Path projectRoot, boolean includeDependencies, int threads, Path indexCachePath, boolean reuseIndex, boolean cacheEnabled) {
        this.sourceRoots = List.copyOf(sourceRoots);
        this.testSourceRoots = List.copyOf(testSourceRoots);
        this.projectRoot = projectRoot;
        this.includeDependencies = includeDependencies;
        this.threads = threads;
        this.indexCachePath = indexCachePath;
        this.reuseIndex = reuseIndex;
        this.cacheEnabled = cacheEnabled;
    }

    public List<Path> sourceRoots() {
        return sourceRoots;
    }

    public List<Path> testSourceRoots() {
        return testSourceRoots;
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public boolean includeDependencies() {
        return includeDependencies;
    }

    public int threads() {
        return threads;
    }

    public Path indexCachePath() {
        return indexCachePath;
    }

    public boolean reuseIndex() {
        return reuseIndex;
    }

    public boolean cacheEnabled() {
        return cacheEnabled;
    }
}
