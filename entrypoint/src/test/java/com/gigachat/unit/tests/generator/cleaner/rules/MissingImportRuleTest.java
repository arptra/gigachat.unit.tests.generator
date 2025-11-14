package com.gigachat.unit.tests.generator.cleaner.rules;

import com.gigachat.unit.tests.generator.cleaner.ProjectClassIndex;
import com.gigachat.unit.tests.generator.cleaner.TestFileContext;
import com.github.javaparser.JavaParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissingImportRuleTest {

    @TempDir
    Path projectDir;

    @Test
    void removesImportsThatDoNotExistInProject() throws IOException {
        Path mainClass = projectDir.resolve("src/main/java/com/example/app/UserService.java");
        Files.createDirectories(mainClass.getParent());
        Files.writeString(mainClass, "package com.example.app; class UserService {}");

        Path testFile = projectDir.resolve("src/test/java/com/example/app/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, String.join(System.lineSeparator(),
                "package com.example.app;",
                "import com.example.app.UserService;",
                "import com.example.missing.DoesNotExist;",
                "import java.util.List;",
                "class UserServiceTest {}"));

        ProjectClassIndex index = new ProjectClassIndex(projectDir);
        MissingImportRule rule = new MissingImportRule(index);
        TestFileContext context = new TestFileContext(testFile, new JavaParser());

        rule.apply(context);
        context.saveIfDirty();

        String updated = Files.readString(testFile);
        assertTrue(updated.contains("import com.example.app.UserService;"), updated);
        assertTrue(updated.contains("import java.util.List;"), updated);
        assertFalse(updated.contains("com.example.missing"), updated);
    }
}
