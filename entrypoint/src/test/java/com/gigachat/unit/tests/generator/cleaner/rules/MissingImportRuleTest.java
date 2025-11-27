package com.gigachat.unit.tests.generator.cleaner.rules;

import com.gigachat.unit.tests.generator.cleaner.ProjectClassIndex;
import com.gigachat.unit.tests.generator.cleaner.TestFileContext;
import com.github.javaparser.JavaParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissingImportRuleTest {

    @TempDir
    Path projectDir;

    @Test
    void removesImportsWithCompilationErrors() throws IOException {
        Path testFile = projectDir.resolve("src/test/java/com/example/app/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, String.join(System.lineSeparator(),
                "package com.example.app;",
                "import com.example.missing.DoesNotExist;",
                "import java.util.List;",
                "class UserServiceTest { List<String> values; }"));

        MissingImportRule rule = new MissingImportRule(new ProjectClassIndex(projectDir));
        TestFileContext context = new TestFileContext(testFile, new JavaParser());

        boolean changed = rule.apply(context);
        context.saveIfDirty();

        String updated = Files.readString(testFile);
        assertTrue(changed, "Rule should remove failing import");
        assertFalse(updated.contains("com.example.missing"), updated);
        assertTrue(updated.contains("java.util.List"), updated);
    }

    @Test
    void keepsImportsAvailableOnProjectClasspath() throws Exception {
        Path libsDir = projectDir.resolve("libs");
        Files.createDirectories(libsDir);

        Path classesDir = Files.createTempDirectory(projectDir, "classes");
        Path sourceFile = classesDir.resolve("com/external/lib/Utility.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, String.join(System.lineSeparator(),
                "package com.external.lib;",
                "public class Utility {",
                "    public static final String NAME = \"utility\";",
                "}"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(classesDir.toFile()));
            compiler.getTask(null, fileManager, null, null, null, fileManager.getJavaFileObjects(sourceFile.toFile())).call();
        }

        Path jarPath = libsDir.resolve("custom-lib.jar");
        try (JarOutputStream jarOutputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            Path compiledClass = classesDir.resolve("com/external/lib/Utility.class");
            jarOutputStream.putNextEntry(new JarEntry("com/external/lib/Utility.class"));
            Files.copy(compiledClass, jarOutputStream);
            jarOutputStream.closeEntry();
        }

        Path testFile = projectDir.resolve("src/test/java/com/example/app/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, String.join(System.lineSeparator(),
                "package com.example.app;",
                "import com.external.lib.Utility;",
                "class UserServiceTest { Utility utility; }"));

        MissingImportRule rule = new MissingImportRule(new ProjectClassIndex(projectDir));
        TestFileContext context = new TestFileContext(testFile, new JavaParser());

        boolean changed = rule.apply(context);
        context.saveIfDirty();

        String updated = Files.readString(testFile);
        assertFalse(changed, "Import backed by library should remain");
        assertTrue(updated.contains("import com.external.lib.Utility;"), updated);
    }

    @Test
    void ignoresCompilationErrorsOutsideImports() throws IOException {
        Path testFile = projectDir.resolve("src/test/java/com/example/app/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, String.join(System.lineSeparator(),
                "package com.example.app;",
                "import java.util.List;",
                "class UserServiceTest { List<String> values = List.of(\"a\", \"b\"  }"));

        MissingImportRule rule = new MissingImportRule(new ProjectClassIndex(projectDir));
        TestFileContext context = new TestFileContext(testFile, new JavaParser());

        boolean changed = rule.apply(context);

        assertFalse(changed, "Syntax error unrelated to imports should not trigger removal");
    }
}
