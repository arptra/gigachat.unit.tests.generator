package com.gigachat.unit.tests.generator.analyzer.semantic;

/**
 * Captures a static method usage discovered during analysis.
 */
public record StaticInvocation(TypeName owner, MethodSignature signature) {
}
