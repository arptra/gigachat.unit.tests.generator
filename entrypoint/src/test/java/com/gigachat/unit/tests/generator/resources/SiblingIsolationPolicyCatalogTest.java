package com.gigachat.unit.tests.generator.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiblingIsolationPolicyCatalogTest {

    @Test
    void shouldLoadSiblingIsolationPolicyFromResources() {
        SiblingIsolationPolicy policy = new SiblingIsolationPolicyCatalog().policy();

        assertTrue(policy.enabled());
        assertTrue(policy.compileBeforeMerge());
        assertTrue(policy.executeBeforeMerge());
        assertTrue(policy.executeOnlyWhenTargetClassAlreadyPopulated());
        assertTrue(policy.validateAgainstMergedClassWhenTargetClassAlreadyPopulated());
        assertTrue(policy.executeWholeScratchSuiteWhenSnippetContainsSiblingTests());
        assertTrue(policy.cleanupScratchSource());
        assertTrue(policy.cleanupCompiledArtifacts());
        assertEquals("PreMergeScratch", policy.scratchClassSuffix());
    }
}
