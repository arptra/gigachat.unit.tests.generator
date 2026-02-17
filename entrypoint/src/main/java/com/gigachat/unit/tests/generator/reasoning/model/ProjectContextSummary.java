package com.gigachat.unit.tests.generator.reasoning.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ProjectContextSummary {

    private List<String> sourceRoots;
    private List<String> testSourceRoots;
    private List<String> dependencies;

    public ProjectContextSummary() {
        this.sourceRoots = new ArrayList<>();
        this.testSourceRoots = new ArrayList<>();
        this.dependencies = new ArrayList<>();
    }

    public ProjectContextSummary(List<String> sourceRoots,
                                 List<String> testSourceRoots,
                                 List<String> dependencies) {
        this.sourceRoots = sourceRoots == null ? new ArrayList<>() : new ArrayList<>(sourceRoots);
        this.testSourceRoots = testSourceRoots == null ? new ArrayList<>() : new ArrayList<>(testSourceRoots);
        this.dependencies = dependencies == null ? new ArrayList<>() : new ArrayList<>(dependencies);
    }

    public List<String> getSourceRoots() {
        return sourceRoots;
    }

    public void setSourceRoots(List<String> sourceRoots) {
        this.sourceRoots = sourceRoots == null ? new ArrayList<>() : new ArrayList<>(sourceRoots);
    }

    public List<String> getTestSourceRoots() {
        return testSourceRoots;
    }

    public void setTestSourceRoots(List<String> testSourceRoots) {
        this.testSourceRoots = testSourceRoots == null ? new ArrayList<>() : new ArrayList<>(testSourceRoots);
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<String> dependencies) {
        this.dependencies = dependencies == null ? new ArrayList<>() : new ArrayList<>(dependencies);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ProjectContextSummary that = (ProjectContextSummary) o;
        return Objects.equals(sourceRoots, that.sourceRoots)
                && Objects.equals(testSourceRoots, that.testSourceRoots)
                && Objects.equals(dependencies, that.dependencies);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceRoots, testSourceRoots, dependencies);
    }
}
