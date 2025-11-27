package com.gigachat.unit.tests.generator.cleaner.rules;

import com.gigachat.unit.tests.generator.cleaner.CleanerRule;
import com.gigachat.unit.tests.generator.cleaner.ProjectClassIndex;
import com.gigachat.unit.tests.generator.cleaner.TestFileContext;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Removes imports that fail real compilation. The rule compiles the target test file with a
 * classpath built from the provided project root (including Gradle caches and local libraries).
 * Any compilation errors that point at import statements cause the corresponding imports to be
 * removed.
 */
public final class MissingImportRule implements CleanerRule {
    private final ProjectClassIndex classIndex;

    public MissingImportRule(ProjectClassIndex classIndex) {
        this.classIndex = classIndex;
    }

    @Override
    public boolean apply(TestFileContext context) throws IOException {
        CompilationUnit unit = context.getCompilationUnit().orElse(null);
        if (unit == null) {
            return false;
        }

        List<ImportDeclaration> imports = unit.getImports();
        if (imports == null || imports.isEmpty()) {
            return false;
        }

        Set<ImportDeclaration> toRemove = detectInvalidImports(context.getFile(), imports);
        if (toRemove.isEmpty()) {
            return false;
        }

        toRemove.forEach(Node::remove);
        context.markAstDirty();
        return true;
    }

    private Set<ImportDeclaration> detectInvalidImports(Path file, List<ImportDeclaration> imports) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return Collections.emptySet();
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            List<Path> classpath = buildClasspathEntries();
            if (!classpath.isEmpty()) {
                fileManager.setLocation(StandardLocation.CLASS_PATH, classpath.stream().map(Path::toFile).toList());
            }

            Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromPaths(List.of(file));
            compiler.getTask(null, fileManager, diagnostics, null, null, sources).call();
        }

        if (diagnostics.getDiagnostics().isEmpty()) {
            return Collections.emptySet();
        }

        Set<Long> importLines = new HashSet<>();
        for (ImportDeclaration declaration : imports) {
            declaration.getBegin().ifPresent(position -> importLines.add((long) position.line));
        }

        Set<ImportDeclaration> toRemove = new HashSet<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }
            if (diagnostic.getSource() == null || !file.equals(Path.of(diagnostic.getSource().toUri()))) {
                continue;
            }
            long line = diagnostic.getLineNumber();
            if (!importLines.contains(line)) {
                continue;
            }
            imports.stream()
                    .filter(importDecl -> importDecl.getBegin().map(pos -> pos.line == line).orElse(false))
                    .forEach(toRemove::add);
        }

        return toRemove;
    }

    private List<Path> buildClasspathEntries() {
        Path root = classIndex.getProjectRoot();
        if (root == null) {
            return Collections.emptyList();
        }

        List<Path> entries = new ArrayList<>();

        List<Path> jarDirs = List.of(
                root.resolve("libs"),
                root.resolve("lib"),
                root.resolve("build/libs")
        );
        List<Path> classDirs = List.of(
                root.resolve("build/classes/java/main"),
                root.resolve("build/classes/java/test"),
                root.resolve("build/resources/main"),
                root.resolve("build/resources/test")
        );

        addGradleCacheJars(root, entries);
        for (Path dir : jarDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(path -> path.toString().endsWith(".jar"))
                        .forEach(entries::add);
            } catch (IOException ignored) {
                // ignore and continue with other directories
            }
        }

        for (Path dir : classDirs) {
            if (Files.isDirectory(dir)) {
                entries.add(dir);
            }
        }

        return entries;
    }

    private static void addGradleCacheJars(Path projectRoot, List<Path> paths) {
        Path userHome = Path.of(System.getProperty("user.home"));
        String gradleHomeProperty = System.getProperty("gradle.user.home");
        Path gradleUserHome = (gradleHomeProperty == null || gradleHomeProperty.isBlank())
                ? userHome.resolve(".gradle")
                : Path.of(gradleHomeProperty);

        List<Path> candidates = List.of(
                projectRoot.resolve(".gradle"),
                gradleUserHome.resolve("caches")
        );

        for (Path candidate : candidates) {
            if (!Files.isDirectory(candidate)) {
                continue;
            }
            collectJarPaths(candidate, paths, path -> path.toString().endsWith(".jar"));
        }
    }

    private static void collectJarPaths(Path root, List<Path> paths, Predicate<Path> matcher) {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(matcher)
                    .forEach(paths::add);
        } catch (IOException ignored) {
            // If we cannot walk the cache directory we simply skip it.
        }
    }
}
