package com.gigachat.unit.tests.generator.cleaner.rules.classlevel;

import com.gigachat.unit.tests.generator.cleaner.TestFileContext;
import com.github.javaparser.JavaParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvalidImportCleanupRuleTest {

    @TempDir
    Path projectDir;

    @Test
    void removesObviouslyMalformedImports() throws IOException {
        Path testFile = projectDir.resolve("src/test/java/com/example/ExampleTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, String.join(System.lineSeparator(),
                "package com.example;",
                "import com.example.Valid;",
                "import //dfergfer",
                "import",
                "import 0-0-",
                "class ExampleTest {",
                "    @Test void shouldStay() {}",
                "}"));

        InvalidImportCleanupRule rule = new InvalidImportCleanupRule();
        TestFileContext context = new TestFileContext(testFile, new JavaParser());

        boolean changed = rule.apply(context);
        context.saveIfDirty();

        String updated = Files.readString(testFile);
        assertTrue(changed);
        assertTrue(updated.contains("import com.example.Valid;"));
        assertFalse(updated.contains("import //dfergfer"));
        assertFalse(updated.contains("import 0-0-"));
    }

    @Test
    void keepsWellFormedImports() throws IOException {
        Path testFile = projectDir.resolve("src/test/java/com/example/ExampleTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, String.join(System.lineSeparator(),
                "package com.example;",
                "import static org.assertj.core.api.Assertions.assertThat;",
                "import java.util.List;",
                "class ExampleTest {",
                "    @Test void shouldStay() {",
                "        assertThat(List.of()).isEmpty();",
                "    }",
                "}"));

        InvalidImportCleanupRule rule = new InvalidImportCleanupRule();
        TestFileContext context = new TestFileContext(testFile, new JavaParser());

        boolean changed = rule.apply(context);
        context.saveIfDirty();

        String updated = Files.readString(testFile);
        assertFalse(changed);
        assertTrue(updated.contains("import static org.assertj.core.api.Assertions.assertThat;"));
        assertTrue(updated.contains("import java.util.List;"));
        assertEquals(2, updated.split("import", -1).length - 1);
    }
}
