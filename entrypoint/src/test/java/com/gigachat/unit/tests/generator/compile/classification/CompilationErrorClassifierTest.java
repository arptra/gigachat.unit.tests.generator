package com.gigachat.unit.tests.generator.compile.classification;

import com.gigachat.unit.tests.generator.compile.classification.classify.CompilationErrorClassifier;
import com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorClass;
import com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorReport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilationErrorClassifierTest {

    private final CompilationErrorClassifier classifier = new CompilationErrorClassifier();

    @Test
    void classifiesMissingPackageAndSymbol() {
        String output = "/tmp/Example.java:3: error: package org.junit.jupiter.api does not exist\n" +
                "import org.junit.jupiter.api.Test;\n" +
                "^\n" +
                "/tmp/Example.java:10: error: cannot find symbol\n" +
                "  symbol:   variable Assertions\n" +
                "  location: class Example";

        CompilationErrorReport report = classifier.classify(output);

        assertEquals(1, report.getCounts().get(CompilationErrorClass.MISSING_DEPENDENCY_OR_PACKAGE));
        assertEquals(1, report.getCounts().get(CompilationErrorClass.MISSING_IMPORT_OR_SYMBOL));
        assertTrue(report.getTopMissingPackages().contains("org.junit.jupiter.api"));
        assertTrue(report.getTopMissingSymbols().contains("Assertions"));
    }

    @Test
    void classifiesSignatureAndTypeMismatches() {
        String output = "Test.java:14: error: method foo in class Sample cannot be applied to given types;\n" +
                "  required: int\n" +
                "  found:    java.lang.String\n" +
                "error: incompatible types: String cannot be converted to int";

        CompilationErrorReport report = classifier.classify(output);

        assertTrue(report.has(CompilationErrorClass.METHOD_SIGNATURE_MISMATCH));
        assertTrue(report.has(CompilationErrorClass.TYPE_MISMATCH));
    }

    @Test
    void classifiesSyntaxAndAccessViolations() {
        String output = "Example.java:5: error: ';' expected\n" +
                "Example.java:9: error: has private access in Foo";

        CompilationErrorReport report = classifier.classify(output);

        assertTrue(report.has(CompilationErrorClass.SYNTAX_ERROR));
        assertTrue(report.has(CompilationErrorClass.ACCESS_VIOLATION));
    }
}
