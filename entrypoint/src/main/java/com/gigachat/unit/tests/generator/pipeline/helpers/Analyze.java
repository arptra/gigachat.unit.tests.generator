package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;

/**
 * Performs lightweight analysis of the target method to understand mocking needs.
 * This is a placeholder implementation that defaults to not mocking any dependencies.
 */
public class Analyze {

    public MockPlan analyze(TestClassInfo classInfo, TestMethodInfo methodInfo) {
        // Future implementations will inspect the AST and populate the plan appropriately.
        return new MockPlan(null, MockStrategy.NONE);
    }
}
