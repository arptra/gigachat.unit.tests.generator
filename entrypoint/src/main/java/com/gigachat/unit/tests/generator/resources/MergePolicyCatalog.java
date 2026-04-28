package com.gigachat.unit.tests.generator.resources;

import org.json.JSONObject;

import java.util.Objects;

/**
 * Loads merge behavior settings from editable resources so structural merge tuning does not live in Java code.
 */
public class MergePolicyCatalog {

    private final MergePolicy policy;

    public MergePolicyCatalog() {
        this(new ResourceTextLoader());
    }

    public MergePolicyCatalog(ResourceTextLoader loader) {
        Objects.requireNonNull(loader, "loader");
        JSONObject root = new JSONObject(loader.readText("policies/merge-policies.json"));
        this.policy = new MergePolicy(
                root.optBoolean("renameMethodOnCollision", true),
                root.optString("collisionSuffixStem", "Variant"),
                Math.max(1, root.optInt("maxCollisionAttempts", 25)),
                root.optBoolean("dedupeDuplicateAnnotations", true),
                root.optBoolean("normalizeImports", true),
                root.optBoolean("autoAddTestAnnotationWhenMissing", true)
        );
    }

    public MergePolicy policy() {
        return policy;
    }
}
