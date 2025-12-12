package com.gigachat.unit.tests.generator.cleaner.rules.packagelevel;

import com.gigachat.unit.tests.generator.cleaner.ProjectClassIndex;
import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        boolean changed = rule.apply(projectDir, List.of(testFile));

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

        writeGradleWrapper(projectDir, jarPath.toString());

        MissingImportRule rule = new MissingImportRule(new ProjectClassIndex(projectDir));

        boolean changed = rule.apply(projectDir, List.of(testFile));

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

        boolean changed = rule.apply(projectDir, List.of(testFile));

        assertFalse(changed, "Syntax error unrelated to imports should not trigger removal");
    }

    @Test
    void doesNotLeaveCompilationArtifacts() throws IOException {
        Path testFile = projectDir.resolve("src/test/java/com/example/app/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, String.join(System.lineSeparator(),
                "package com.example.app;",
                "import java.util.List;",
                "class UserServiceTest { List<String> values; }"));

        MissingImportRule rule = new MissingImportRule(new ProjectClassIndex(projectDir));

        boolean changed = rule.apply(projectDir, List.of(testFile));

        assertFalse(changed, "Valid imports should remain untouched");

        try (Stream<Path> stream = Files.walk(projectDir)) {
            assertTrue(stream.noneMatch(path -> path.toString().endsWith(".class")),
                    "Compilation artifacts should not remain inside the project directory");
        }
    }

    @Test
    void compilesAllTestsOnceAcrossPackage() throws IOException {
        Path failingTest = projectDir.resolve("src/test/java/com/example/app/UserServiceTest.java");
        Files.createDirectories(failingTest.getParent());
        Files.writeString(failingTest, String.join(System.lineSeparator(),
                "package com.example.app;",
                "import com.example.missing.DoesNotExist;",
                "class UserServiceTest { }"));

        Path passingTest = projectDir.resolve("src/test/java/com/example/app/UserProfileTest.java");
        Files.createDirectories(passingTest.getParent());
        Files.writeString(passingTest, String.join(System.lineSeparator(),
                "package com.example.app;",
                "import java.util.List;",
                "class UserProfileTest { List<String> values; }"));

        RecordingCompilerInvoker compilerInvoker = new RecordingCompilerInvoker(failingTest);
        MissingImportRule rule = new MissingImportRule(new ProjectClassIndex(projectDir), compilerInvoker);

        boolean changed = rule.apply(projectDir, List.of(failingTest, passingTest));

        assertTrue(changed, "Missing import should be removed based on a package-wide compilation");
        assertEquals(1, compilerInvoker.compileAllInvocationCount, "compileAllTests should run once for the package");
        assertFalse(Files.readString(failingTest).contains("com.example.missing"));
        assertTrue(Files.readString(passingTest).contains("java.util.List"));
    }

    private static final class RecordingCompilerInvoker implements CompilerInvoker {
        private final Path failingFile;
        private int compileAllInvocationCount;

        RecordingCompilerInvoker(Path failingFile) {
            this.failingFile = failingFile.toAbsolutePath().normalize();
        }

        @Override
        public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
            throw new AssertionError("compile should not be invoked for MissingImportRule");
        }

        @Override
        public CompileResult compileAllTests(Path projectRoot, String methodName) {
            compileAllInvocationCount++;
            String log = failingFile + ":2: error: cannot find symbol";
            return new CompileResult(false, List.of(), "", log);
        }
    }

    private void writeGradleWrapper(Path projectRoot, String classpath) throws IOException {
        Path gradleWrapper = projectRoot.resolve("gradlew");
        Files.writeString(gradleWrapper, String.join(System.lineSeparator(),
                "#!/bin/bash",
                "echo \"" + classpath + "\""));
        try {
            Files.setPosixFilePermissions(gradleWrapper,
                    EnumSet.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            gradleWrapper.toFile().setExecutable(true, true);
        }
    }
}
