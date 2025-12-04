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

    @Test
    void removesDuplicatedAnnotationsSeparatedByComments() throws IOException {
        Path testFile = projectDir.resolve("src/test/java/com/example/ExampleTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, String.join(System.lineSeparator(),
                "package com.example;",
                "class ExampleTest {",
                "    @Test",
                "    // comment about test",
                "    @Test",
                "    void shouldKeepSingleAnnotation() {",
                "        assertTrue(true);",
                "    }",
                "}"));

        DanglingTestAnnotationRule rule = new DanglingTestAnnotationRule();
        TestFileContext context = new TestFileContext(testFile, new JavaParser());

        rule.apply(context);
        context.saveIfDirty();

        String updated = Files.readString(testFile);
        int occurrences = updated.split("@Test", -1).length - 1;
        assertTrue(occurrences == 1);
        assertTrue(updated.contains("// comment about test"));
    }

    @Test
    void keepsAnnotationWithInlineCommentBeforeMethod() throws IOException {
        Path testFile = projectDir.resolve("src/test/java/com/example/ExampleTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, String.join(System.lineSeparator(),
                "package com.example;",
                "class ExampleTest {",
                "    @Test",
                "    // valid comment",
                "    void shouldStay() {",
                "        assertTrue(true);",
                "    }",
                "}"));

        DanglingTestAnnotationRule rule = new DanglingTestAnnotationRule();
        TestFileContext context = new TestFileContext(testFile, new JavaParser());

        boolean changed = rule.apply(context);
        context.saveIfDirty();

        String updated = Files.readString(testFile);
        assertFalse(changed);
        assertTrue(updated.contains("@Test"));
    }

    @Test
    void removesLongRunsOfDanglingAnnotations() throws IOException {
        Path testFile = projectDir.resolve("src/test/java/com/example/ExampleTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, String.join(System.lineSeparator(),
                "package com.example;",
                "class ExampleTest {",
                "    @Test",
                "    ",
                "    @Test",
                "    // stray",
                "    @Test",
                "    ",
                "    // Test finding existing user by username",
                "    void shouldLeaveOnlyValidAnnotation() {",
                "        assertTrue(true);",
                "    }",
                "}"));

        DanglingTestAnnotationRule rule = new DanglingTestAnnotationRule();
        TestFileContext context = new TestFileContext(testFile, new JavaParser());

        rule.apply(context);
        context.saveIfDirty();

        String updated = Files.readString(testFile);
        int occurrences = updated.split("@Test", -1).length - 1;
        assertTrue(occurrences == 1);
    }
}
