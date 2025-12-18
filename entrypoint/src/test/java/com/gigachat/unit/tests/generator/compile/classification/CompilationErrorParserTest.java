package com.gigachat.unit.tests.generator.compile.classification;

import com.gigachat.unit.tests.generator.compile.classification.model.CompilationError;
import com.gigachat.unit.tests.generator.compile.classification.parse.CompilationErrorParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilationErrorParserTest {

    private final CompilationErrorParser parser = new CompilationErrorParser();

    @Test
    void parsesFileLineAndMessage() {
        String output = "/tmp/Example.java:12: error: package org.example does not exist\n" +
                "import org.example.Missing;\n" +
                "^";

        List<CompilationError> errors = parser.parse(output);

        assertEquals(1, errors.size());
        CompilationError error = errors.get(0);
        assertEquals("/tmp/Example.java", error.getFilePath());
        assertEquals(12, error.getLine());
        assertNull(error.getColumn());
        assertTrue(error.getRawMessage().contains("package org.example does not exist"));
    }

    @Test
    void parsesGenericErrorBlocks() {
        String output = "error: cannot find symbol\n" +
                "  symbol:   variable foo\n" +
                "  location: class Sample";

        List<CompilationError> errors = parser.parse(output);

        assertEquals(1, errors.size());
        CompilationError error = errors.get(0);
        assertNull(error.getFilePath());
        assertNull(error.getLine());
        assertTrue(error.getRawMessage().contains("cannot find symbol"));
        assertTrue(error.getRawMessage().contains("variable foo"));
    }
}
