package com.gigachat.unit.tests.generator.util;

import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetClassMatcherTest {

    @Test
    void matchesFullyQualifiedTargetAgainstDiscoveredTestPath() {
        TestClassInfo info = new TestClassInfo(
                "CoverageGoalWorkflowService",
                "CoverageGoalWorkflowServiceTest",
                Path.of("/tmp/project/src/test/java/com/example/app/service/CoverageGoalWorkflowServiceTest.java"),
                List.of(),
                List.of());

        assertTrue(TargetClassMatcher.matches(info, "com.example.app.service.CoverageGoalWorkflowService"));
    }
}
