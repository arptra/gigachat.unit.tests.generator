package com.gigachat.unit.tests.generator.cleaner.parser;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionFailureLogParserTest {

    @Test
    void parsesFailuresAndReportPath() {
        String log = String.join(System.lineSeparator(),
                "UserTest > shouldReturnCorrectUsername() FAILED",
                "    org.opentest4j.AssertionFailedError at UserTest.java:20",
                "LibraryComponentTest > testReload FAILED",
                "    org.opentest4j.AssertionFailedError at LibraryComponentTest.java:22",
                "> There were failing tests. See the report at: file:///tmp/build/reports/tests/test/index.html");

        ExecutionFailureLogParser parser = new ExecutionFailureLogParser();

        ExecutionFailureParseResult result = parser.parse(log);

        List<TestFailure> failures = result.failures();
        assertEquals(2, failures.size());
        assertTrue(failures.contains(new TestFailure("UserTest", "shouldReturnCorrectUsername")));
        assertTrue(failures.contains(new TestFailure("LibraryComponentTest", "testReload")));
        assertEquals(Path.of("/tmp/build/reports/tests/test/index.html"), result.reportPath().orElse(null));
    }
}
