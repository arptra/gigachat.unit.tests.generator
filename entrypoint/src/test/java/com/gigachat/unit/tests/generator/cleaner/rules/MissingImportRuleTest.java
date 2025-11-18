package com.gigachat.unit.tests.generator.cleaner.rules;

import com.gigachat.unit.tests.generator.cleaner.ProjectClassIndex;
import com.gigachat.unit.tests.generator.cleaner.TestFileContext;
import com.github.javaparser.JavaParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void keepsImportsFromCustomLibraries() throws Exception {
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
        assertNotNull(compiler, "Java compiler is not available");
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

        ProjectClassIndex index = new ProjectClassIndex(projectDir);
        MissingImportRule rule = new MissingImportRule(index);
        TestFileContext context = new TestFileContext(testFile, new JavaParser());

        rule.apply(context);
        context.saveIfDirty();

        String updated = Files.readString(testFile);
        assertTrue(updated.contains("import com.external.lib.Utility;"), updated);
    }

    @Test
    void keepsImportsFromGradleDependenciesInCache() throws Exception {
        Path cacheDir = projectDir.resolve(".gradle/caches/modules-2/files-2.1/com/sample/lib/1.0");
        Files.createDirectories(cacheDir);

        Path classesDir = Files.createTempDirectory(projectDir, "gradle-cache-classes");
        Path sourceFile = classesDir.resolve("com/sample/lib/CacheUtility.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, String.join(System.lineSeparator(),
                "package com.sample.lib;",
                "public class CacheUtility {",
                "    public static final String NAME = \"cache\";",
                "}"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Java compiler is not available");
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(classesDir.toFile()));
            compiler.getTask(null, fileManager, null, null, null, fileManager.getJavaFileObjects(sourceFile.toFile())).call();
        }

        Path jarPath = cacheDir.resolve("lib-1.0.jar");
        try (JarOutputStream jarOutputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            Path compiledClass = classesDir.resolve("com/sample/lib/CacheUtility.class");
            jarOutputStream.putNextEntry(new JarEntry("com/sample/lib/CacheUtility.class"));
            Files.copy(compiledClass, jarOutputStream);
            jarOutputStream.closeEntry();
        }

        Path testFile = projectDir.resolve("src/test/java/com/example/app/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, String.join(System.lineSeparator(),
                "package com.example.app;",
                "import com.sample.lib.CacheUtility;",
                "class UserServiceTest { CacheUtility utility; }"));

        ProjectClassIndex index = new ProjectClassIndex(projectDir);
        MissingImportRule rule = new MissingImportRule(index);
        TestFileContext context = new TestFileContext(testFile, new JavaParser());

        rule.apply(context);
        context.saveIfDirty();

        String updated = Files.readString(testFile);
        assertTrue(updated.contains("import com.sample.lib.CacheUtility;"), updated);
    }
}
