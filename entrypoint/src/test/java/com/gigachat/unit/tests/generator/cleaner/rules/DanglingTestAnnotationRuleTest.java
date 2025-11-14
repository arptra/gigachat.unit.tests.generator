package com.gigachat.unit.tests.generator.cleaner.rules;

import com.gigachat.unit.tests.generator.cleaner.TestFileContext;
import com.github.javaparser.JavaParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DanglingTestAnnotationRuleTest {

    @TempDir
    Path projectDir;

    @Test
    void removesTrailingTestAnnotationsWithoutDeclarations() throws IOException {
        Path testFile = projectDir.resolve("src/test/java/com/example/ExampleTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, String.join(System.lineSeparator(),
                "package com.example;",
                "class ExampleTest {",
                "    @Test",
                "    ",
                "}"));

        DanglingTestAnnotationRule rule = new DanglingTestAnnotationRule();
        TestFileContext context = new TestFileContext(testFile, new JavaParser());

        rule.apply(context);
        context.saveIfDirty();

        String updated = Files.readString(testFile);
        assertFalse(updated.contains("@Test"));
    }
}
