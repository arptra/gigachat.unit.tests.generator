package com.example.app.service;

import java.util.HashSet;
import java.util.Set;

public class FeatureToggleService {
    private final Set<String> enabledFeatures = new HashSet<>();

    public FeatureToggleService() {
        enabledFeatures.add("hidden");
    }

    public boolean isEnabled(String featureName) {
        return enabledFeatures.contains(featureName);
    }

    public void enable(String featureName) {
        enabledFeatures.add(featureName);
    }

    public void disable(String featureName) {
        enabledFeatures.remove(featureName);
    }
}
