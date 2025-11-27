package com.gigachat.unit.tests.generator.compile;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Invokes the local {@link JavaCompiler} to compile a single source file using the project's
 * classpath. Diagnostics are returned via the {@link CompileResult} stderr field in the standard
 * {@code path:line: error: message} format so callers can parse failing lines.
 */
public class InProcessCompilerInvoker implements CompilerInvoker {

    @Override
    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            String message = "System compiler is not available";
            return new CompileResult(false, List.of(message), "", message);
        }

        Path tempOutput;
        try {
            tempOutput = Files.createTempDirectory("missing-import-rule-classes");
        } catch (IOException exception) {
            String message = "Unable to create temporary output directory: " + exception.getMessage();
            return new CompileResult(false, List.of(message), "", message);
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<String> messages = new ArrayList<>();
        StringWriter stdout = new StringWriter();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            List<Path> classpath = buildClasspathEntries(projectRoot);
            if (!classpath.isEmpty()) {
                fileManager.setLocation(StandardLocation.CLASS_PATH, classpath.stream().map(Path::toFile).toList());
                messages.add("Configured in-process compiler classpath with " + classpath.size() + " entries.");
            }
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(tempOutput.toFile()));
            fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(tempOutput.toFile()));

            Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromPaths(List.of(testClassFile));
            boolean success = Boolean.TRUE.equals(compiler.getTask(stdout, fileManager, diagnostics, null, null, sources).call());
            return new CompileResult(success, messages, stdout.toString(), formatDiagnostics(diagnostics));
        } catch (IOException exception) {
            String message = "Compilation failed: " + exception.getMessage();
            return new CompileResult(false, messages, stdout.toString(), message);
        } finally {
            deleteQuietly(tempOutput);
        }
    }

    private static String formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        return diagnostics.getDiagnostics().stream()
                .map(diagnostic -> formatDiagnostic(diagnostic))
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private static String formatDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
        JavaFileObject source = diagnostic.getSource();
        if (source == null) {
            return "";
        }
        return Path.of(source.toUri())
                + ":"
                + diagnostic.getLineNumber()
                + ": error: "
                + diagnostic.getMessage(Locale.getDefault());
    }

    private static List<Path> buildClasspathEntries(Path projectRoot) {
        if (projectRoot == null) {
            return List.of();
        }

        List<Path> entries = new ArrayList<>();

        List<Path> jarDirs = List.of(
                projectRoot.resolve("libs"),
                projectRoot.resolve("lib"),
                projectRoot.resolve("build/libs")
        );
        List<Path> classDirs = List.of(
                projectRoot.resolve("build/classes/java/main"),
                projectRoot.resolve("build/classes/java/test"),
                projectRoot.resolve("build/resources/main"),
                projectRoot.resolve("build/resources/test")
        );

        addGradleCacheJars(projectRoot, entries);
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

    private static void deleteQuietly(Path root) {
        if (root == null) {
            return;
        }

        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // best-effort cleanup
                        }
                    });
        } catch (IOException ignored) {
            // ignore cleanup issues
        }
    }
}
