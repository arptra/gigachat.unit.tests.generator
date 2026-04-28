package com.gigachat.unit.tests.generator.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergePolicyCatalogTest {

    @Test
    void shouldLoadMergePolicyFromResources() {
        MergePolicy policy = new MergePolicyCatalog().policy();

        assertTrue(policy.renameMethodOnCollision());
        assertEquals("Variant", policy.collisionSuffixStem());
        assertTrue(policy.dedupeDuplicateAnnotations());
        assertTrue(policy.normalizeImports());
    }
}
