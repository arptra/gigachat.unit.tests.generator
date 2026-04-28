package com.gigachat.unit.tests.generator.resources;

import org.json.JSONObject;

import java.util.Objects;

/**
 * Loads pre-merge sibling isolation settings from editable resources.
 */
public class SiblingIsolationPolicyCatalog {

    private final SiblingIsolationPolicy policy;

    public SiblingIsolationPolicyCatalog() {
        this(new ResourceTextLoader());
    }

    public SiblingIsolationPolicyCatalog(ResourceTextLoader loader) {
        Objects.requireNonNull(loader, "loader");
        JSONObject root = new JSONObject(loader.readText("policies/sibling-isolation-policies.json"));
        this.policy = new SiblingIsolationPolicy(
                root.optBoolean("enabled", true),
                root.optBoolean("compileBeforeMerge", true),
                root.optBoolean("executeBeforeMerge", true),
                root.optBoolean("executeOnlyWhenTargetClassAlreadyPopulated", true),
                root.optBoolean("validateAgainstMergedClassWhenTargetClassAlreadyPopulated", true),
                root.optBoolean("executeWholeScratchSuiteWhenSnippetContainsSiblingTests", true),
                root.optBoolean("cleanupScratchSource", true),
                root.optBoolean("cleanupCompiledArtifacts", true),
                root.optString("scratchClassSuffix", "PreMergeScratch").trim().isEmpty()
                        ? "PreMergeScratch"
                        : root.optString("scratchClassSuffix", "PreMergeScratch").trim()
        );
    }

    public SiblingIsolationPolicy policy() {
        return policy;
    }
}
