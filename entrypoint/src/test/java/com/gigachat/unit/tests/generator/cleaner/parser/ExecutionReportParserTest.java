package com.gigachat.unit.tests.generator.cleaner.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionReportParserTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsFailuresFromReport() throws IOException {
        Path report = tempDir.resolve("index.html");
        Files.writeString(report, String.join(System.lineSeparator(),
                "<html>",
                "<body>",
                "<div>UserTest > shouldReturnCorrectUsername() FAILED</div>",
                "<div>LibraryComponentTest > testReload FAILED</div>",
                "</body>",
                "</html>"));

        ExecutionReportParser parser = new ExecutionReportParser();

        List<TestFailure> failures = parser.parse(report);

        assertEquals(2, failures.size());
        assertTrue(failures.contains(new TestFailure("UserTest", "shouldReturnCorrectUsername")));
        assertTrue(failures.contains(new TestFailure("LibraryComponentTest", "testReload")));
    }
}
