package com.gigachat.unit.tests.generator.report.parser;

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
    void extractsDetailedFailuresFromHtmlReport() throws IOException {
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);

        Path index = tempDir.resolve("index.html");
        Files.writeString(index, String.join(System.lineSeparator(),
                "<html>",
                "<body>",
                "<a href=\"classes/UserTest.html#shouldReturnCorrectUsername\">UserTest</a>",
                "<a href=\"classes/LibraryComponentTest.html#testReload\">LibraryComponentTest</a>",
                "</body>",
                "</html>"));

        Path userTestReport = classesDir.resolve("UserTest.html");
        Files.writeString(userTestReport, String.join(System.lineSeparator(),
                "<html>",
                "<body>",
                "<a id=\"shouldReturnCorrectUsername\"></a>",
                "<div class=\"message\">org.opentest4j.AssertionFailedError: expected &lt;john&gt; but was &lt;doe&gt;</div>",
                "<pre>org.opentest4j.AssertionFailedError: expected &lt;john&gt; but was &lt;doe&gt;<br>",
                "    at com.example.UserTest.shouldReturnCorrectUsername(UserTest.java:20)</pre>",
                "</body>",
                "</html>"));

        Path libraryReport = classesDir.resolve("LibraryComponentTest.html");
        Files.writeString(libraryReport, String.join(System.lineSeparator(),
                "<html>",
                "<body>",
                "<a name=\"testReload\"></a>",
                "<div class=\"message\">java.lang.AssertionError: reload failed</div>",
                "<pre>java.lang.AssertionError: reload failed\n",
                "    at com.example.LibraryComponentTest.testReload(LibraryComponentTest.java:22)\n",
                "    at java.base/java.util.Optional.orElseThrow(Optional.java:408)</pre>",
                "</body>",
                "</html>"));

        ExecutionReportParser parser = new ExecutionReportParser();

        List<TestReportFailure> failures = parser.parse(index);

        assertEquals(2, failures.size());

        TestReportFailure first = failures.get(0);
        assertEquals("UserTest", first.className());
        assertEquals("shouldReturnCorrectUsername", first.methodName());
        assertTrue(first.message().contains("expected <john> but was <doe>"));
        assertTrue(first.stackTrace().stream().anyMatch(line -> line.contains("UserTest.java:20")));

        TestReportFailure second = failures.get(1);
        assertEquals("LibraryComponentTest", second.className());
        assertEquals("testReload", second.methodName());
        assertTrue(second.message().contains("reload failed"));
        assertTrue(second.stackTrace().stream().anyMatch(line -> line.contains("Optional.orElseThrow")));
    }
}
