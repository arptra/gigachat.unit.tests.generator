package com.gigachat.unit.tests.generator.resources;

/**
 * Controls how generated test snippets are validated in an isolated scratch test class before they
 * are merged into the accumulated generated test class.
 */
public record SiblingIsolationPolicy(boolean enabled,
                                     boolean compileBeforeMerge,
                                     boolean executeBeforeMerge,
                                     boolean executeOnlyWhenTargetClassAlreadyPopulated,
                                     boolean validateAgainstMergedClassWhenTargetClassAlreadyPopulated,
                                     boolean executeWholeScratchSuiteWhenSnippetContainsSiblingTests,
                                     boolean cleanupScratchSource,
                                     boolean cleanupCompiledArtifacts,
                                     String scratchClassSuffix) {
}
