package com.gigachat.unit.tests.generator.cleaner.rules.classlevel;

import com.gigachat.unit.tests.generator.cleaner.TestFileContext;
import com.github.javaparser.JavaParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StubAssertionRemovalRuleTest {

    @TempDir
    Path projectDir;

    @Test
    void removesTestMethodsContainingOnlyTrivialAssertions() throws IOException {
        Path testFile = projectDir.resolve("src/test/java/com/example/ExampleTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, String.join(System.lineSeparator(),
                "package com.example;",
                "import org.junit.jupiter.api.Test;",
                "class ExampleTest {",
                "    @Test",
                "    void placeholder() {",
                "        org.junit.jupiter.api.Assertions.assertTrue(true);",
                "    }",
                "    @Test",
                "    void realTest() {",
                "        org.junit.jupiter.api.Assertions.assertTrue(false == false);",
                "    }",
                "}"));

        StubAssertionRemovalRule rule = new StubAssertionRemovalRule();
        TestFileContext context = new TestFileContext(testFile, new JavaParser());

        rule.apply(context);
        context.saveIfDirty();

        String updated = Files.readString(testFile);
        assertFalse(updated.contains("placeholder"));
        assertTrue(updated.contains("realTest"));
    }
}
