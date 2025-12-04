package com.gigachat.unit.tests.generator.analyzer.semantic;

import com.github.javaparser.ast.body.MethodDeclaration;

/**
 * Semantic analyzer contract.
 */
public interface SemanticAnalyzer {
    SemanticMethodAnalysis analyze(MethodDeclaration declaration, SignatureRegistry registry);
}
