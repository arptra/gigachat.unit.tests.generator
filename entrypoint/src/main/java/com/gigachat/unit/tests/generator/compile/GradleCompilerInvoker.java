package com.gigachat.unit.tests.generator.compile;

import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compiles a single generated test file. The invoker resolves the test runtime classpath via a
 * lightweight Gradle helper task (when the wrapper is present) and then delegates to the JDK's
 * {@link JavaCompiler} to compile only the requested file. When Gradle is unavailable the compiler
 * falls back to the current JVM classpath.
 */
public class GradleCompilerInvoker implements CompilerInvoker {
    private final PipelineLogger logger;
    private final boolean cleanupOutputs;
    private static final CompilationCache COMPILATION_CACHE = CompilationCache.getInstance();

    public GradleCompilerInvoker(PipelineLogger logger) {
        this(logger, false);
    }

    public GradleCompilerInvoker(PipelineLogger logger, boolean cleanupOutputs) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.cleanupOutputs = cleanupOutputs;
    }

    @Override
    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
        Path cacheKey = testClassFile.toAbsolutePath().normalize();
        CompilationCacheEntry cachedEntry = COMPILATION_CACHE.get(cacheKey);
        if (cachedEntry != null) {
            if (cachedEntry.isStale(cacheKey)) {
                logger.info("Cached compilation result for " + cacheKey + " is stale; recompiling.");
                COMPILATION_CACHE.remove(cacheKey);
            } else {
                logger.info("Returning cached compilation result for " + cacheKey);
                return cachedEntry.result();
            }
        }

        List<String> messages = new ArrayList<>();
        if (!Files.exists(testClassFile)) {
            String message = "Target test path does not exist: " + testClassFile;
            logger.warn(message);
            return new CompileResult(false, List.of(message), "", message);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            String message = "No system Java compiler available. Ensure a JDK is installed.";
            logger.error(message);
            return new CompileResult(false, List.of(message), "", message);
        }

        List<Path> compilationTargets;
        try {
            compilationTargets = resolveCompilationTargets(testClassFile);
        } catch (IOException exception) {
            logger.error("Unable to read compilation targets from " + testClassFile, exception);
            return new CompileResult(false, List.of(), "", exception.getMessage());
        }
        if (compilationTargets.isEmpty()) {
            String message = "No Java sources found under " + testClassFile;
            logger.warn(message);
            return new CompileResult(false, List.of(message), "", message);
        }

        Path representative = compilationTargets.getFirst();
        String modulePath = determineGradlePath(projectRoot, representative);
        Path moduleRoot = modulePath.isBlank() ? projectRoot : projectRoot.resolve(Path.of(modulePath.replace(":", "/")));
        Path outputDir = moduleRoot.resolve("build/classes/java/test");

        boolean outputDirPreexisted = Files.exists(outputDir);
        try {
            Files.createDirectories(outputDir);

            Set<Path> classpathEntries = resolveTestClasspath(projectRoot, modulePath, messages);
            classpathEntries.add(outputDir);
            classpathEntries.add(moduleRoot.resolve("build/classes/java/main"));
            classpathEntries.add(moduleRoot.resolve("build/resources/test"));
            classpathEntries.add(moduleRoot.resolve("build/resources/main"));

            List<Path> existingClasspath = classpathEntries.stream()
                    .filter(Files::exists)
                    .collect(Collectors.toCollection(ArrayList::new));

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            StringWriter compilerOutput = new StringWriter();
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.getDefault(), StandardCharsets.UTF_8)) {
                if (!existingClasspath.isEmpty()) {
                    fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, existingClasspath);
                }
                fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(outputDir));

                Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(compilationTargets);
                List<String> options = new ArrayList<>();
                options.add("--release");
                options.add(String.valueOf(Runtime.version().feature()));
                options.add("-g");

                logger.info("Compiling " + compilationTargets.size() + " test source(s) starting at " + testClassFile
                        + " for method " + methodName);
                Boolean success = compiler.getTask(compilerOutput, fileManager, diagnostics, options, null, units).call();
                String stdout = compilerOutput.toString();
                String stderr = diagnostics.getDiagnostics().stream()
                        .map(GradleCompilerInvoker::formatDiagnostic)
                        .collect(Collectors.joining(System.lineSeparator()));
                boolean compilationSucceeded = Boolean.TRUE.equals(success);
                if (!compilationSucceeded) {
                    logger.warn("Compilation failed for " + testClassFile);
                }
                return cacheResult(cacheKey, testClassFile, new CompileResult(compilationSucceeded, messages, stdout, stderr));
            } catch (IOException exception) {
                logger.error("Compilation failed for " + testClassFile, exception);
                return cacheResult(cacheKey, testClassFile, new CompileResult(false, messages, "", exception.getMessage()));
            }
        } catch (IOException exception) {
            logger.error("Compilation failed for " + testClassFile, exception);
            return cacheResult(cacheKey, testClassFile, new CompileResult(false, messages, "", exception.getMessage()));
        } finally {
            if (cleanupOutputs && !outputDirPreexisted) {
                try {
                    deleteDirectory(outputDir);
                } catch (IOException cleanupError) {
                    logger.warn("Failed to clean compilation outputs at " + outputDir + ": " + cleanupError.getMessage());
                }
            }
        }
    }

    private CompileResult cacheResult(Path cacheKey, Path sourcePath, CompileResult result) {
        try {
            COMPILATION_CACHE.put(cacheKey, CompilationCacheEntry.from(sourcePath, result));
        } catch (IOException exception) {
            logger.warn("Failed to cache compilation result for " + sourcePath + ": " + exception.getMessage());
        }
        return result;
    }

    private String determineGradlePath(Path projectRoot, Path testClassFile) {
        Path relative = projectRoot.relativize(testClassFile);
        List<String> segments = new ArrayList<>();
        Path parent = relative.getParent();
        if (parent == null) {
            return "";
        }
        for (Path part : parent) {
            if ("src".equals(part.toString())) {
                break;
            }
            segments.add(part.toString());
        }
        if (segments.isEmpty()) {
            return "";
        }
        return String.join(":", segments);
    }

    private List<Path> resolveCompilationTargets(Path testClassFile) throws IOException {
        if (Files.isDirectory(testClassFile)) {
            try (Stream<Path> stream = Files.walk(testClassFile)) {
                return stream
                        .filter(path -> path.toString().endsWith(".java"))
                        .collect(Collectors.toList());
            }
        }
        return List.of(testClassFile);
    }

    private Set<Path> resolveTestClasspath(Path projectRoot, String modulePath, List<String> messages) {
        Set<Path> entries = new LinkedHashSet<>();
        Path gradlew = projectRoot.resolve("gradlew");
        if (!Files.exists(gradlew)) {
            messages.add("Gradle wrapper not found. Using current JVM classpath only.");
            entries.addAll(defaultClasspath());
            return entries;
        }

        Path initScript;
        try {
            initScript = createClasspathInitScript();
        } catch (IOException exception) {
            logger.warn("Failed to create classpath init script: " + exception.getMessage());
            entries.addAll(defaultClasspath());
            return entries;
        }

        String gradleTask = (modulePath.isBlank() ? "" : (":" + modulePath + ":")) + "printTestClasspath";
        String command = "./gradlew -q " + gradleTask + " --init-script " + initScript.toAbsolutePath();
        ProcessBuilder builder = new ProcessBuilder("bash", "-lc", command);
        builder.directory(projectRoot.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String stdout;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                stdout = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                messages.add("Gradle classpath task exited with code " + exitCode + "; falling back to JVM classpath.");
                entries.addAll(defaultClasspath());
                return entries;
            }
            String classpathLine = stdout.lines()
                    .filter(line -> !line.isBlank())
                    .reduce((first, second) -> second)
                    .orElse("");
            if (classpathLine.isBlank()) {
                messages.add("Gradle did not return a test classpath; using JVM classpath.");
                entries.addAll(defaultClasspath());
                return entries;
            }
            for (String path : classpathLine.split(java.io.File.pathSeparator)) {
                if (!path.isBlank()) {
                    entries.add(Path.of(path));
                }
            }
            messages.add("Gradle wrapper detected. Resolved test classpath via printTestClasspath task.");
            return entries;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            messages.add("Failed to resolve classpath via Gradle: " + exception.getMessage());
            entries.addAll(defaultClasspath());
            return entries;
        } finally {
            try {
                Files.deleteIfExists(initScript);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }

    private Path createClasspathInitScript() throws IOException {
        String script = "allprojects { project ->\n" +
                "    project.plugins.withId('java') {\n" +
                "        project.tasks.register('printTestClasspath') {\n" +
                "            doLast { println project.sourceSets.test.runtimeClasspath.asPath }\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
        Path tempScript = Files.createTempFile("print-test-classpath", ".gradle");
        Files.writeString(tempScript, script, StandardCharsets.UTF_8);
        return tempScript;
    }

    private static Set<Path> defaultClasspath() {
        String jvmClasspath = System.getProperty("java.class.path", "");
        Set<Path> entries = new LinkedHashSet<>();
        for (String part : jvmClasspath.split(java.io.File.pathSeparator)) {
            if (!part.isBlank()) {
                entries.add(Path.of(part));
            }
        }
        return entries;
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // best-effort cleanup
                        }
                    });
        }
    }

    private static String formatDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
        String source = diagnostic.getSource() == null ? "" : diagnostic.getSource().getName();
        return source + ":" + diagnostic.getLineNumber() + ": error: " + diagnostic.getMessage(Locale.getDefault());
    }

}
