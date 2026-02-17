package com.gigachat.unit.tests.generator.cleaner.rules;

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

        boolean changed = rule.apply(context);

        assertTrue(changed, "Rule should delete empty test classes with dangling annotations");
        assertFalse(Files.exists(testFile), "File should be removed when no methods remain");
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

    @Test
    void deletesTestClassWithoutMethods() throws IOException {
        Path testFile = projectDir.resolve("src/test/java/com/example/EmptyTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, String.join(System.lineSeparator(),
                "package com.example;",
                "class EmptyTest {",
                "    // no methods here",
                "}"));

        DanglingTestAnnotationRule rule = new DanglingTestAnnotationRule();
        TestFileContext context = new TestFileContext(testFile, new JavaParser());

        boolean changed = rule.apply(context);

        assertTrue(changed, "Expected rule to delete empty test class");
        assertFalse(Files.exists(testFile), "Test file should be removed when no methods are present");
    }
}
