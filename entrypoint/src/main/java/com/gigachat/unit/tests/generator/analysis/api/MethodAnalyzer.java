package com.gigachat.unit.tests.generator.analysis.api;

import com.gigachat.unit.tests.generator.dto.TestMethodInfo;

/**
 * Defines a strategy for analysing a test method body and extracting the
 * dependencies required for prompt generation.
 */
public interface MethodAnalyzer {
    MethodAnalysisDTO analyze(TestMethodInfo info);
}
