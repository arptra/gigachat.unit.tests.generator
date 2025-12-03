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
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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
        return compile(projectRoot, testClassFile, methodName, false);
    }

    public CompileResult compileAllTests(Path projectRoot, Path testClassFile, String methodName) {
        return compile(projectRoot, testClassFile, methodName, true);
    }

    private CompileResult compile(Path projectRoot, Path testClassFile, String methodName, boolean includeAllTestClasses) {
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

        Path cacheKey = deriveCacheKey(compilationTargets, testClassFile);
        CompileResult cachedResult = getCachedResult(cacheKey);
        if (cachedResult != null) {
            return cachedResult;
        }

        Path representative = compilationTargets.getFirst();
        String modulePath = determineGradlePath(projectRoot, representative);
        Path moduleRoot = modulePath.isBlank() ? projectRoot : projectRoot.resolve(Path.of(modulePath.replace(":", "/")));
        Path outputDir = moduleRoot.resolve("build/classes/java/test");

        boolean outputDirPreexisted = Files.exists(outputDir);
        try {
            Files.createDirectories(outputDir);

            Set<Path> classpathEntries = resolveTestClasspath(projectRoot, modulePath, messages);
            if (includeAllTestClasses) {
                classpathEntries.addAll(resolveAllTestOutputs(projectRoot, messages));
            }
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
                return cacheResult(cacheKey, new CompileResult(compilationSucceeded, messages, stdout, stderr));
            } catch (IOException exception) {
                logger.error("Compilation failed for " + testClassFile, exception);
                return cacheResult(cacheKey, new CompileResult(false, messages, "", exception.getMessage()));
            }
        } catch (IOException exception) {
            logger.error("Compilation failed for " + testClassFile, exception);
            return cacheResult(cacheKey, new CompileResult(false, messages, "", exception.getMessage()));
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

    @Override
    public List<CompileResult> compileParallel(Path projectRoot, List<Path> testClassFiles, String methodName) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(testClassFiles, "testClassFiles");
        if (testClassFiles.isEmpty()) {
            return List.of();
        }

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(Math.max(1, Runtime.getRuntime().availableProcessors()), testClassFiles.size()));
        try {
            List<CompletableFuture<CompileResult>> futures = testClassFiles.stream()
                    .map(file -> CompletableFuture.supplyAsync(
                            () -> compileInForkedJvm(projectRoot, file, methodName), executor))
                    .toList();

            return futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
        } finally {
            executor.shutdown();
        }
    }

    private CompileResult compileInForkedJvm(Path projectRoot, Path testClassFile, String methodName) {
        List<String> messages = new ArrayList<>();
        if (!Files.exists(testClassFile)) {
            String message = "Target test path does not exist: " + testClassFile;
            logger.warn(message);
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

        Path cacheKey = deriveCacheKey(compilationTargets, testClassFile);
        CompileResult cachedResult = getCachedResult(cacheKey);
        if (cachedResult != null) {
            return cachedResult;
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

            String classpath = existingClasspath.stream()
                    .map(Path::toString)
                    .collect(Collectors.joining(java.io.File.pathSeparator));

            List<String> command = new ArrayList<>();
            command.add("javac");
            command.add("--release");
            command.add(String.valueOf(Runtime.version().feature()));
            command.add("-g");
            command.add("-d");
            command.add(outputDir.toString());
            if (!classpath.isBlank()) {
                command.add("-cp");
                command.add(classpath);
            }
            compilationTargets.forEach(path -> command.add(path.toString()));

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(moduleRoot.toFile());
            try {
                Process process = builder.start();
                logger.info("Forking javac process (pid=" + process.pid() + ") for " + testClassFile);
                String stdout;
                String stderr;
                try (BufferedReader outReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                     BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    stdout = outReader.lines().collect(Collectors.joining(System.lineSeparator()));
                    stderr = errReader.lines().collect(Collectors.joining(System.lineSeparator()));
                }
                int exitCode = process.waitFor();
                boolean compilationSucceeded = exitCode == 0;
                if (!compilationSucceeded) {
                    logger.warn("javac exited with code " + exitCode + " for " + testClassFile);
                }
                return cacheResult(cacheKey, new CompileResult(compilationSucceeded, messages, stdout, stderr));
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                logger.error("Forked compilation failed for " + testClassFile, exception);
                return cacheResult(cacheKey, new CompileResult(false, messages, "", exception.getMessage()));
            }
        } catch (IOException exception) {
            logger.error("Forked compilation failed for " + testClassFile, exception);
            return cacheResult(cacheKey, new CompileResult(false, messages, "", exception.getMessage()));
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

    private CompileResult getCachedResult(Path cacheKey) {
        CompilationCacheEntry cachedEntry = COMPILATION_CACHE.get(cacheKey);
        if (cachedEntry == null) {
            return null;
        }
        if (cachedEntry.isStale(cacheKey)) {
            logger.info("Cached compilation result for " + cacheKey + " is stale; recompiling.");
            COMPILATION_CACHE.remove(cacheKey);
            return null;
        }
        logger.info("Returning cached compilation result for " + cacheKey);
        return cachedEntry.result();
    }

    private Path deriveCacheKey(List<Path> compilationTargets, Path requestedPath) {
        return compilationTargets.isEmpty()
                ? requestedPath.toAbsolutePath().normalize()
                : compilationTargets.getFirst().toAbsolutePath().normalize();
    }

    private CompileResult cacheResult(Path cacheKey, CompileResult result) {
        try {
            COMPILATION_CACHE.put(cacheKey, CompilationCacheEntry.from(cacheKey, result));
        } catch (IOException exception) {
            logger.warn("Failed to cache compilation result for " + cacheKey + ": " + exception.getMessage());
        }
        return result;
    }

    private String determineGradlePath(Path projectRoot, Path testClassFile) {
        Path relative = projectRoot.relativize(testClassFile);
        Path parent = relative.getParent();
        if (parent == null) {
            return "";
        }
        List<String> segments = StreamSupport.stream(parent.spliterator(), false)
                .takeWhile(part -> !"src".equals(part.toString()))
                .map(Path::toString)
                .toList();
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
            entries.addAll(Arrays.stream(classpathLine.split(java.io.File.pathSeparator))
                    .parallel()
                    .filter(path -> !path.isBlank())
                    .map(Path::of)
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
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

    private Set<Path> resolveAllTestOutputs(Path projectRoot, List<String> messages) {
        Path gradlew = projectRoot.resolve("gradlew");
        if (Files.exists(gradlew)) {
            Path initScript = null;
            try {
                initScript = createAllTestOutputsInitScript();
                String command = "./gradlew -q printAllTestOutputs --init-script " + initScript.toAbsolutePath();
                ProcessBuilder builder = new ProcessBuilder("bash", "-lc", command);
                builder.directory(projectRoot.toFile());
                builder.redirectErrorStream(true);
                Process process = builder.start();
                String stdout;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    stdout = reader.lines().collect(Collectors.joining(System.lineSeparator()));
                }
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    String outputsLine = stdout.lines()
                            .filter(line -> !line.isBlank())
                            .reduce((first, second) -> second)
                            .orElse("");
                    if (!outputsLine.isBlank()) {
                        messages.add("Gradle returned aggregated test outputs via printAllTestOutputs task.");
                        return Arrays.stream(outputsLine.split(java.io.File.pathSeparator))
                                .parallel()
                                .filter(entry -> !entry.isBlank())
                                .map(Path::of)
                                .collect(Collectors.toCollection(LinkedHashSet::new));
                    }
                    messages.add("Gradle did not report any compiled test outputs; falling back to filesystem scan.");
                } else {
                    messages.add("Gradle test output task exited with code " + exitCode + "; falling back to filesystem scan.");
                }
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                messages.add("Failed to resolve aggregated test outputs via Gradle: " + exception.getMessage());
            } finally {
                if (initScript != null) {
                    try {
                        Files.deleteIfExists(initScript);
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                }
            }
        }

        try (Stream<Path> stream = Files.walk(projectRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(this::isCompiledTestOutput)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException exception) {
            logger.warn("Failed to collect test output directories: " + exception.getMessage());
            return Set.of();
        }
    }

    private Path createAllTestOutputsInitScript() throws IOException {
        String script = "gradle.projectsEvaluated {\n" +
                "    def outputs = rootProject.allprojects\n" +
                "        .findAll { it.plugins.hasPlugin('java') }\n" +
                "        .collectMany { it.sourceSets.test.output.classesDirs.files }\n" +
                "        .collect { it.absolutePath }\n" +
                "    rootProject.tasks.register('printAllTestOutputs') {\n" +
                "        doLast { println outputs.join(File.pathSeparator) }\n" +
                "    }\n" +
                "}\n";
        Path tempScript = Files.createTempFile("print-all-test-outputs", ".gradle");
        Files.writeString(tempScript, script, StandardCharsets.UTF_8);
        return tempScript;
    }

    private boolean isCompiledTestOutput(Path path) {
        Path javaOutput = Path.of("build", "classes", "java", "test");
        Path kotlinOutput = Path.of("build", "classes", "kotlin", "test");
        return path.endsWith(javaOutput) || path.endsWith(kotlinOutput);
    }

    private static Set<Path> defaultClasspath() {
        String jvmClasspath = System.getProperty("java.class.path", "");
        return Arrays.stream(jvmClasspath.split(java.io.File.pathSeparator))
                .parallel()
                .filter(part -> !part.isBlank())
                .map(Path::of)
                .collect(Collectors.toCollection(LinkedHashSet::new));
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
