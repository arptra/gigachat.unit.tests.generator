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
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.eclipse.EclipseSourceDirectory;
import org.gradle.tooling.model.idea.IdeaCompilerOutput;
import org.gradle.tooling.model.idea.IdeaDependency;
import org.gradle.tooling.model.idea.IdeaModule;
import org.gradle.tooling.model.idea.IdeaProject;
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency;

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
    private static final CompilationEnvironment COMPILATION_ENVIRONMENT = new CompilationEnvironment();
    private static final ConcurrentMap<String, ClasspathResolution> CLASSPATH_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Path, Long> DEPENDENCY_FILE_TIMESTAMPS = new ConcurrentHashMap<>();
    private static final Set<Path> DEFAULT_CLASSPATH = defaultClasspath();
    private static final Set<String> DEPENDENCY_FILE_NAMES = Set.of(
            "gradle.dependencies",
            "dependencies.gradle",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "libs.versions.toml"
    );

    public GradleCompilerInvoker(PipelineLogger logger) {
        this(logger, false);
    }

    public GradleCompilerInvoker(PipelineLogger logger, boolean cleanupOutputs) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.cleanupOutputs = cleanupOutputs;
    }

    @Override
    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
        return compile(projectRoot, testClassFile, methodName, false, true);
    }

    @Override
    public CompileResult compileWithoutCache(Path projectRoot, Path testClassFile, String methodName) {
        return compile(projectRoot, testClassFile, methodName, false, false);
    }

    public CompileResult compileAllTests(Path projectRoot, String methodName) {
        Objects.requireNonNull(projectRoot, "projectRoot");

        List<String> messages = new ArrayList<>();
        List<Path> testSources;
        try {
            testSources = findJavaTestSources(projectRoot);
        } catch (IOException exception) {
            logger.error("Unable to enumerate test sources", exception);
            return new CompileResult(false, List.of(), "", exception.getMessage());
        }

        if (testSources.isEmpty()) {
            String message = "No Java tests discovered under src/test/java";
            logger.warn(message);
            return new CompileResult(false, List.of(message), "", message);
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        boolean overallSuccess = true;

        var targetsByModule = testSources.stream()
                .collect(Collectors.groupingBy(source -> determineGradlePath(projectRoot, source), LinkedHashMap::new, Collectors.toList()));

        for (var entry : targetsByModule.entrySet()) {
            String modulePath = entry.getKey();
            List<Path> moduleTargets = entry.getValue();

            boolean dependenciesChanged = dependencyFileUpdated(projectRoot);
            if (dependenciesChanged) {
                invalidateClasspathCacheForProject(projectRoot);
                moduleTargets.forEach(COMPILATION_CACHE::remove);
            }

            List<CompileResult> cachedResults = dependenciesChanged
                    ? List.of()
                    : moduleTargets.stream()
                            .map(target -> getCachedResult(deriveCacheKey(List.of(target), target)))
                            .filter(Objects::nonNull)
                            .toList();

            List<Path> staleTargets = dependenciesChanged
                    ? moduleTargets
                    : moduleTargets.stream()
                            .filter(target -> getCachedResult(deriveCacheKey(List.of(target), target)) == null)
                            .toList();

            if (staleTargets.isEmpty()) {
                moduleTargets.forEach(target -> messages.add("Using cached compilation result for " + target.toAbsolutePath().normalize()));
                for (CompileResult cachedResult : cachedResults) {
                    overallSuccess &= cachedResult.success();
                    appendOutputs(stdout, stderr, cachedResult);
                }
                continue;
            }

            Path moduleRoot = modulePath.isBlank()
                    ? projectRoot
                    : projectRoot.resolve(Path.of(modulePath.replace(":", "/")));

            ModuleCompilationOutcome moduleOutcome = compileModuleTests(projectRoot, modulePath, moduleRoot, moduleTargets, methodName, messages, dependenciesChanged);
            CompileResult moduleResult = moduleOutcome.result();
            overallSuccess &= moduleResult.success();
            appendOutputs(stdout, stderr, moduleResult);

            for (CompileResult cachedResult : cachedResults) {
                overallSuccess &= cachedResult.success();
                appendOutputs(stdout, stderr, cachedResult);
            }

            moduleTargets.forEach(target -> cacheResultForTestSource(target, moduleOutcome, messages));
        }

        return new CompileResult(overallSuccess, messages, stdout.toString(), stderr.toString());
    }

    private CompileResult compile(Path projectRoot, Path testClassFile, String methodName, boolean includeAllTestClasses, boolean useCache) {
        List<String> messages = new ArrayList<>();
        if (!Files.exists(testClassFile)) {
            String message = "Target test path does not exist: " + testClassFile;
            logger.warn(message);
            return new CompileResult(false, List.of(message), "", message);
        }

        JavaCompiler compiler = COMPILATION_ENVIRONMENT.getCompiler();
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
        Path representative = compilationTargets.get(0);
        String modulePath = determineGradlePath(projectRoot, representative);
        Path moduleRoot = modulePath.isBlank() ? projectRoot : projectRoot.resolve(Path.of(modulePath.replace(":", "/")));
        Path outputDir = moduleRoot.resolve("build/classes/java/test");

        boolean dependenciesChanged = dependencyFileUpdated(projectRoot);
        if (dependenciesChanged) {
            invalidateClasspathCacheForProject(projectRoot);
            if (useCache) {
                COMPILATION_CACHE.remove(cacheKey);
            }
        } else if (useCache) {
            CompileResult cachedResult = getCachedResult(cacheKey);
            if (cachedResult != null) {
                return cachedResult;
            }
        }

        boolean outputDirPreexisted = Files.exists(outputDir);
        try {
            Files.createDirectories(outputDir);

            Set<Path> classpathEntries = resolveTestClasspathWithRefresh(projectRoot, modulePath, messages, dependenciesChanged);
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
                List<String> options = COMPILATION_ENVIRONMENT.copyBaseOptions();

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
                CompileResult result = new CompileResult(compilationSucceeded, messages, stdout, stderr);
                return useCache ? cacheResult(cacheKey, result) : result;
            } catch (IOException exception) {
                logger.error("Compilation failed for " + testClassFile, exception);
                CompileResult result = new CompileResult(false, messages, "", exception.getMessage());
                return useCache ? cacheResult(cacheKey, result) : result;
            }
        } catch (IOException exception) {
            logger.error("Compilation failed for " + testClassFile, exception);
            CompileResult result = new CompileResult(false, messages, "", exception.getMessage());
            return useCache ? cacheResult(cacheKey, result) : result;
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

    private ModuleCompilationOutcome compileModuleTests(Path projectRoot, String modulePath, Path moduleRoot,
                                                        List<Path> compilationTargets, String methodName, List<String> messages,
                                                        boolean dependenciesChanged) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            String message = "No system Java compiler available. Ensure a JDK is installed.";
            logger.error(message);
            return new ModuleCompilationOutcome(new CompileResult(false, List.of(message), "", message), List.of());
        }

        Path outputDir = moduleRoot.resolve("build/classes/java/test");
        boolean outputDirPreexisted = Files.exists(outputDir);
        try {
            Files.createDirectories(outputDir);

            if (dependenciesChanged) {
                invalidateClasspathCacheForProject(projectRoot);
            }
            Set<Path> classpathEntries = resolveTestClasspathWithRefresh(projectRoot, modulePath, messages, dependenciesChanged);
            classpathEntries.addAll(resolveAllTestOutputs(projectRoot, messages));
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

                logger.info("Compiling " + compilationTargets.size() + " test source(s) under " + moduleRoot
                        + " for method " + methodName);
                Boolean success = compiler.getTask(compilerOutput, fileManager, diagnostics, options, null, units).call();
                String stdout = compilerOutput.toString();
                List<Diagnostic<? extends JavaFileObject>> diagnosticList = diagnostics.getDiagnostics();
                String stderr = diagnosticList.stream()
                        .map(GradleCompilerInvoker::formatDiagnostic)
                        .collect(Collectors.joining(System.lineSeparator()));
                boolean compilationSucceeded = Boolean.TRUE.equals(success);
                if (!compilationSucceeded) {
                    logger.warn("Compilation failed for module rooted at " + moduleRoot);
                }
                return new ModuleCompilationOutcome(new CompileResult(compilationSucceeded, messages, stdout, stderr), diagnosticList);
            } catch (IOException exception) {
                logger.error("Compilation failed for module rooted at " + moduleRoot, exception);
                return new ModuleCompilationOutcome(new CompileResult(false, messages, "", exception.getMessage()), List.of());
            }
        } catch (IOException exception) {
            logger.error("Compilation failed for module rooted at " + moduleRoot, exception);
            return new ModuleCompilationOutcome(new CompileResult(false, messages, "", exception.getMessage()), List.of());
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
        Path representative = compilationTargets.get(0);
        String modulePath = determineGradlePath(projectRoot, representative);
        Path moduleRoot = modulePath.isBlank() ? projectRoot : projectRoot.resolve(Path.of(modulePath.replace(":", "/")));
        Path outputDir = moduleRoot.resolve("build/classes/java/test");

        boolean dependenciesChanged = dependencyFileUpdated(projectRoot);
        if (dependenciesChanged) {
            invalidateClasspathCacheForProject(projectRoot);
            COMPILATION_CACHE.remove(cacheKey);
        } else {
            CompileResult cachedResult = getCachedResult(cacheKey);
            if (cachedResult != null) {
                return cachedResult;
            }
        }

        boolean outputDirPreexisted = Files.exists(outputDir);
        try {
            Files.createDirectories(outputDir);

            Set<Path> classpathEntries = resolveTestClasspathWithRefresh(projectRoot, modulePath, messages, dependenciesChanged);
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
            command.addAll(COMPILATION_ENVIRONMENT.copyBaseOptions());
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
                : compilationTargets.get(0).toAbsolutePath().normalize();
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

    private List<Path> findJavaTestSources(Path projectRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(projectRoot)) {
            List<Path> testRoots = stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.endsWith(Path.of("src", "test", "java")))
                    .toList();

            List<Path> testSources = new ArrayList<>();
            for (Path root : testRoots) {
                try (Stream<Path> sources = Files.walk(root)) {
                    sources.filter(file -> file.toString().endsWith(".java"))
                            .forEach(testSources::add);
                }
            }
            return testSources;
        }
    }

    private void cacheResultForTestSource(Path target, ModuleCompilationOutcome moduleOutcome, List<String> messages) {
        String normalized = target.toAbsolutePath().normalize().toString();
        String sentinel = File.separator + "src" + File.separator + "test" + File.separator + "java" + File.separator;
        if (!normalized.contains(sentinel)) {
            return;
        }

        Path cacheKey = deriveCacheKey(List.of(target), target);
        CompileResult perFileResult = buildPerFileResult(target, moduleOutcome);
        cacheResult(cacheKey, perFileResult);
        messages.add("Cached compilation result for " + cacheKey);
    }

    private void appendOutputs(StringBuilder stdout, StringBuilder stderr, CompileResult result) {
        stdout.append(result.stdout());
        if (!result.stdout().isBlank()) {
            stdout.append(System.lineSeparator());
        }
        stderr.append(result.stderr());
        if (!result.stderr().isBlank()) {
            stderr.append(System.lineSeparator());
        }
    }

    private CompileResult buildPerFileResult(Path target, ModuleCompilationOutcome moduleOutcome) {
        List<Diagnostic<? extends JavaFileObject>> perFileDiagnostics = moduleOutcome.diagnostics().stream()
                .filter(diagnostic -> {
                    JavaFileObject source = diagnostic.getSource();
                    if (source == null) {
                        return false;
                    }
                    return Path.of(source.toUri()).toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize());
                })
                .toList();

        String perFileStdErr = perFileDiagnostics.stream()
                .map(GradleCompilerInvoker::formatDiagnostic)
                .collect(Collectors.joining(System.lineSeparator()));

        CompileResult moduleResult = moduleOutcome.result();
        if (perFileDiagnostics.isEmpty()) {
            return moduleResult;
        }

        return new CompileResult(moduleResult.success(), moduleResult.messages(), moduleResult.stdout(), perFileStdErr);
    }

    private record ModuleCompilationOutcome(CompileResult result, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
    }

    private Set<Path> resolveTestClasspathWithRefresh(Path projectRoot, String modulePath, List<String> messages, boolean forceRefresh) {
        String cacheKey = projectRoot.toAbsolutePath().normalize() + "|" + modulePath;
        if (forceRefresh) {
            CLASSPATH_CACHE.remove(cacheKey);
        }
        ClasspathResolution cached = CLASSPATH_CACHE.get(cacheKey);
        if (cached != null && !cached.entries().isEmpty()) {
            return new LinkedHashSet<>(cached.entries());
        }

        ClasspathResolution classpath = resolveTestClasspath(projectRoot, modulePath, messages, forceRefresh);
        if (classpath.entries().isEmpty() && Files.exists(projectRoot.resolve("gradlew")) && !forceRefresh) {
            classpath = resolveTestClasspath(projectRoot, modulePath, messages, true);
        }

        if (classpath.derivedFromGradle() && !classpath.entries().isEmpty()) {
            CLASSPATH_CACHE.put(cacheKey, new ClasspathResolution(new LinkedHashSet<>(classpath.entries()), true));
        }
        return classpath.entries();
    }

    private ClasspathResolution resolveTestClasspath(Path projectRoot, String modulePath, List<String> messages, boolean refreshDependencies) {
        Set<Path> entries = new LinkedHashSet<>();
        Path gradlew = projectRoot.resolve("gradlew");
        if (!Files.exists(gradlew)) {
            messages.add("Gradle wrapper not found. Using current JVM classpath only.");
            entries.addAll(DEFAULT_CLASSPATH);
            return new ClasspathResolution(entries, false);
        }

        Path initScript;
        try {
            initScript = createClasspathInitScript();
        } catch (IOException exception) {
            logger.warn("Failed to create classpath init script: " + exception.getMessage());
            entries.addAll(DEFAULT_CLASSPATH);
            return new ClasspathResolution(entries, false);
        }

        String refreshFlag = refreshDependencies ? " --refresh-dependencies" : "";
        String gradleTask = (modulePath.isBlank() ? "" : (":" + modulePath + ":")) + "printTestClasspath";
        String command = "./gradlew -q " + gradleTask + " --init-script " + initScript.toAbsolutePath() + refreshFlag;
        try {
            GradleTaskResult gradleResult = runGradleCommand(projectRoot, command);
            if (!gradleResult.success() && !modulePath.isBlank()) {
                String rootCommand = "./gradlew -q printTestClasspath --init-script " + initScript.toAbsolutePath() + refreshFlag;
                gradleResult = runGradleCommand(projectRoot, rootCommand);
            }
            if (!gradleResult.success()) {
                Set<Path> toolingEntries = resolveTestClasspathViaToolingApi(projectRoot, messages);
                if (!toolingEntries.isEmpty()) {
                    messages.add("Gradle classpath task failed; resolved test classpath via Tooling API.");
                    entries.addAll(toolingEntries);
                    return new ClasspathResolution(entries, true);
                }
                messages.add("Gradle classpath task exited with code " + gradleResult.exitCode() + "; falling back to JVM classpath.");
                entries.addAll(DEFAULT_CLASSPATH);
                return new ClasspathResolution(entries, false);
            }
            String classpathLine = gradleResult.stdout().lines()
                    .filter(line -> !line.isBlank())
                    .reduce((first, second) -> second)
                    .orElse("");
            if (classpathLine.isBlank()) {
                Set<Path> toolingEntries = resolveTestClasspathViaToolingApi(projectRoot, messages);
                if (!toolingEntries.isEmpty()) {
                    messages.add("Gradle task returned no classpath; resolved test classpath via Tooling API.");
                    entries.addAll(toolingEntries);
                    return new ClasspathResolution(entries, true);
                }
                messages.add("Gradle did not return a test classpath; using JVM classpath.");
                entries.addAll(DEFAULT_CLASSPATH);
                return new ClasspathResolution(entries, false);
            }
            entries.addAll(Arrays.stream(classpathLine.split(java.io.File.pathSeparator))
                    .parallel()
                    .filter(path -> !path.isBlank())
                    .map(Path::of)
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
            messages.add("Gradle wrapper detected. Resolved test classpath via printTestClasspath task" + (refreshDependencies ? " with refresh." : "."));
            return new ClasspathResolution(entries, true);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            messages.add("Failed to resolve classpath via Gradle: " + exception.getMessage());
            entries.addAll(DEFAULT_CLASSPATH);
            return new ClasspathResolution(entries, false);
        } finally {
            try {
                Files.deleteIfExists(initScript);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }

    private Set<Path> resolveTestClasspathViaToolingApi(Path projectRoot, List<String> messages) {
        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(projectRoot.toFile());
        if (Files.exists(projectRoot.resolve("gradle/wrapper/gradle-wrapper.properties"))) {
            connector.useBuildDistribution();
        }

        try (ProjectConnection connection = connector.connect()) {
            Set<Path> ideaEntries = collectIdeaModelClasspath(connection);
            if (!ideaEntries.isEmpty()) {
                return ideaEntries;
            }

            Set<Path> eclipseEntries = collectEclipseModelClasspath(connection);
            if (!eclipseEntries.isEmpty()) {
                return eclipseEntries;
            }

            messages.add("Tooling API did not provide test classpath entries.");
            return Set.of();
        } catch (Exception exception) {
            messages.add("Tooling API classpath resolution failed: " + exception.getMessage());
            return Set.of();
        }
    }

    private Set<Path> collectIdeaModelClasspath(ProjectConnection connection) {
        try {
            IdeaProject project = connection.getModel(IdeaProject.class);
            if (project == null) {
                return Set.of();
            }

            Set<Path> entries = new LinkedHashSet<>();
            for (IdeaModule module : project.getModules()) {
                IdeaCompilerOutput output = module.getCompilerOutput();
                if (output != null) {
                    if (output.getTestOutputDir() != null) {
                        entries.add(output.getTestOutputDir().toPath());
                    }
                    if (output.getOutputDir() != null) {
                        entries.add(output.getOutputDir().toPath());
                    }
                }

                for (IdeaDependency dependency : module.getDependencies()) {
                    if (dependency instanceof IdeaSingleEntryLibraryDependency libraryDependency) {
                        String scope = libraryDependency.getScope() == null ? "" : libraryDependency.getScope().getScope();
                        if (scope == null || scope.isBlank() || scope.equalsIgnoreCase("TEST")
                                || scope.equalsIgnoreCase("RUNTIME") || scope.equalsIgnoreCase("COMPILE")) {
                            File file = libraryDependency.getFile();
                            if (file != null) {
                                entries.add(file.toPath());
                            }
                        }
                    }
                }
            }
            return entries;
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private Set<Path> collectEclipseModelClasspath(ProjectConnection connection) {
        try {
            EclipseProject eclipseProject = connection.getModel(EclipseProject.class);
            if (eclipseProject == null) {
                return Set.of();
            }
            Set<Path> entries = new LinkedHashSet<>();
            collectEclipseProjectClasspath(eclipseProject, entries, new LinkedHashSet<>());
            return entries;
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private void collectEclipseProjectClasspath(EclipseProject project, Set<Path> entries, Set<String> visited) {
        if (project == null) {
            return;
        }
        String identity = project.getName() + "|" + project.getProjectDirectory().getAbsolutePath();
        if (!visited.add(identity)) {
            return;
        }

        if (project.getOutputLocation() != null && project.getOutputLocation().getPath() != null) {
            entries.add(Path.of(project.getOutputLocation().getPath()));
        }
        for (EclipseSourceDirectory sourceDirectory : project.getSourceDirectories()) {
            if (sourceDirectory.getOutput() != null) {
                entries.add(Path.of(sourceDirectory.getOutput()));
            }
        }
        project.getClasspath().forEach(dependency -> {
            if (dependency.getFile() != null) {
                entries.add(dependency.getFile().toPath());
            }
        });
        for (EclipseProject child : project.getChildren()) {
            collectEclipseProjectClasspath(child, entries, visited);
        }
    }

    private GradleTaskResult runGradleCommand(Path projectRoot, String command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("bash", "-lc", command);
        builder.directory(projectRoot.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String stdout;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            stdout = reader.lines().collect(Collectors.joining(System.lineSeparator()));
        }
        int exitCode = process.waitFor();
        return new GradleTaskResult(exitCode == 0, exitCode, stdout);
    }


    private Path createClasspathInitScript() throws IOException {
        String script = """
                allprojects { project ->
                    project.afterEvaluate {
                        def taskName = 'printTestClasspath'
                        if (project.tasks.findByName(taskName) != null) return
                        project.tasks.register(taskName) {
                            doLast {
                                def sourceSets = project.extensions.findByName('sourceSets')
                                def testSet = sourceSets == null ? null : sourceSets.findByName('test')
                                def configuration = project.configurations.findByName('testRuntimeClasspath')
                                if (configuration == null) configuration = project.configurations.findByName('testRuntimeOnly')
                                def classpathFiles = [] as Set
                                if (configuration != null) { classpathFiles.addAll(configuration.resolve()) }
                                if (testSet != null) {
                                    classpathFiles.addAll(testSet.output.classesDirs.files)
                                    if (testSet.output.resourcesDir != null) { classpathFiles.add(testSet.output.resourcesDir) }
                                    if (testSet.runtimeClasspath != null) { classpathFiles.addAll(testSet.runtimeClasspath.files) }
                                }
                                println classpathFiles.collect { it.absolutePath }.join(File.pathSeparator)
                            }
                        }
                    }
                }
                """;
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
                "        .collectMany { project ->\n" +
                "            def sourceSets = project.extensions.findByName('sourceSets')\n" +
                "            if (sourceSets == null) return []\n" +
                "            def testSet = sourceSets.findByName('test')\n" +
                "            if (testSet == null) return []\n" +
                "            return testSet.output.classesDirs.files.collect { it.absolutePath }\n" +
                "        }\n" +
                "    if (rootProject.tasks.findByName('printAllTestOutputs') == null) {\n" +
                "        rootProject.tasks.register('printAllTestOutputs') {\n" +
                "            doLast { println outputs.join(File.pathSeparator) }\n" +
                "        }\n" +
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
                .filter(part -> !part.isBlank())
                .map(Path::of)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean dependencyFileUpdated(Path projectRoot) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Set<Path> candidates = collectDependencyFiles(normalizedRoot);
        boolean changed = false;

        for (Path candidate : candidates) {
            try {
                long lastModified = Files.getLastModifiedTime(candidate).toMillis();
                Long previous = DEPENDENCY_FILE_TIMESTAMPS.put(candidate, lastModified);
                if (previous == null || lastModified != previous) {
                    changed = true;
                }
            } catch (IOException exception) {
                logger.warn("Unable to read dependency timestamp for " + candidate + ": " + exception.getMessage());
            }
        }

        for (Path tracked : new ArrayList<>(DEPENDENCY_FILE_TIMESTAMPS.keySet())) {
            if (tracked.startsWith(normalizedRoot) && !candidates.contains(tracked)) {
                DEPENDENCY_FILE_TIMESTAMPS.remove(tracked);
                changed = true;
            }
        }

        return changed;
    }

    private Set<Path> collectDependencyFiles(Path projectRoot) {
        Set<Path> files = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.walk(projectRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> DEPENDENCY_FILE_NAMES.contains(path.getFileName().toString()))
                    .forEach(files::add);
        } catch (IOException ignored) {
            // best-effort collection
        }
        return files;
    }

    private void invalidateClasspathCacheForProject(Path projectRoot) {
        String normalizedRoot = projectRoot.toAbsolutePath().normalize().toString();
        CLASSPATH_CACHE.keySet().removeIf(key -> key.startsWith(normalizedRoot));
    }

    private record ClasspathResolution(Set<Path> entries, boolean derivedFromGradle) {
    }

    private record GradleTaskResult(boolean success, int exitCode, String stdout) {
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

    private static final class CompilationEnvironment {
        private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        private final List<String> baseOptions = List.of("--release", String.valueOf(Runtime.version().feature()), "-g");

        JavaCompiler getCompiler() {
            return compiler;
        }

        List<String> copyBaseOptions() {
            return new ArrayList<>(baseOptions);
        }
    }

}
