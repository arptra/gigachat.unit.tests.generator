package com.gigachat.unit.tests.generator.gradle;

import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.eclipse.EclipseSourceDirectory;
import org.gradle.tooling.model.idea.IdeaCompilerOutput;
import org.gradle.tooling.model.idea.IdeaDependency;
import org.gradle.tooling.model.idea.IdeaModule;
import org.gradle.tooling.model.idea.IdeaProject;
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Resolves the runtime classpath required to execute a compiled generated test.
 */
public final class TestRuntimeClasspathResolver {
    private static final ConcurrentMap<String, Set<Path>> CLASSPATH_CACHE = new ConcurrentHashMap<>();

    private final PipelineLogger logger;

    public TestRuntimeClasspathResolver(PipelineLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public ResolvedTestRuntimeClasspath resolve(Path projectRoot, Path testClassFile) {
        Path buildRoot = GradleBuildLocator.findInvocationRoot(projectRoot);
        String modulePath = determineGradlePath(buildRoot, testClassFile);
        Path moduleRoot = resolveModuleRoot(buildRoot, modulePath);
        List<String> messages = new ArrayList<>();

        Set<Path> entries = resolveTestRuntimeClasspath(buildRoot, modulePath, messages);
        entries.add(moduleRoot.resolve("build/classes/java/test"));
        entries.add(moduleRoot.resolve("build/resources/test"));
        entries.add(moduleRoot.resolve("build/classes/java/main"));
        entries.add(moduleRoot.resolve("build/resources/main"));

        Set<Path> existingEntries = entries.stream()
                .filter(Files::exists)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new ResolvedTestRuntimeClasspath(buildRoot, moduleRoot, modulePath, existingEntries, messages);
    }

    private Set<Path> resolveTestRuntimeClasspath(Path buildRoot, String modulePath, List<String> messages) {
        String cacheKey = buildRoot.toAbsolutePath().normalize() + "|" + modulePath;
        Set<Path> cached = CLASSPATH_CACHE.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            return new LinkedHashSet<>(cached);
        }

        Set<Path> entries = new LinkedHashSet<>();
        String gradleCommand = GradleBuildLocator.resolveGradleCommand(buildRoot);
        if (gradleCommand != null) {
            Path initScript = null;
            try {
                initScript = createClasspathInitScript();
                String moduleTask = modulePath.isBlank() ? "printTestClasspath" : ":" + modulePath + ":printTestClasspath";
                GradleTaskResult result = runGradleCommand(buildRoot,
                        gradleCommand + " -q " + moduleTask + " --init-script " + initScript.toAbsolutePath());
                if (!result.success() && !modulePath.isBlank()) {
                    result = runGradleCommand(buildRoot,
                            gradleCommand + " -q printTestClasspath --init-script " + initScript.toAbsolutePath());
                }
                if (result.success()) {
                    String classpathLine = result.stdout().lines()
                            .filter(line -> !line.isBlank())
                            .reduce((first, second) -> second)
                            .orElse("");
                    if (!classpathLine.isBlank()) {
                        entries.addAll(Arrays.stream(classpathLine.split(File.pathSeparator))
                                .filter(value -> !value.isBlank())
                                .map(Path::of)
                                .collect(Collectors.toCollection(LinkedHashSet::new)));
                        String resolutionMode = "gradle".equals(gradleCommand)
                                ? "installed Gradle"
                                : "Gradle wrapper";
                        messages.add("Resolved execution runtime classpath via printTestClasspath using " + resolutionMode + ".");
                    }
                } else {
                    messages.add("printTestClasspath failed with exit code " + result.exitCode() + ".");
                }
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                messages.add("Gradle runtime classpath resolution failed: " + exception.getMessage());
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

        if (entries.isEmpty()) {
            Set<Path> toolingEntries = resolveViaToolingApi(buildRoot, messages);
            if (!toolingEntries.isEmpty()) {
                entries.addAll(toolingEntries);
            }
        }

        if (!entries.isEmpty()) {
            CLASSPATH_CACHE.put(cacheKey, new LinkedHashSet<>(entries));
        }
        return entries;
    }

    private Set<Path> resolveViaToolingApi(Path projectRoot, List<String> messages) {
        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(projectRoot.toFile());
        if (Files.exists(projectRoot.resolve("gradle/wrapper/gradle-wrapper.properties"))) {
            connector.useBuildDistribution();
        }

        try (ProjectConnection connection = connector.connect()) {
            Set<Path> ideaEntries = collectIdeaModelClasspath(connection);
            if (!ideaEntries.isEmpty()) {
                messages.add("Resolved execution runtime classpath via Tooling API IDEA model.");
                return ideaEntries;
            }

            Set<Path> eclipseEntries = collectEclipseModelClasspath(connection);
            if (!eclipseEntries.isEmpty()) {
                messages.add("Resolved execution runtime classpath via Tooling API Eclipse model.");
                return eclipseEntries;
            }

            messages.add("Tooling API did not expose runtime classpath entries.");
            return Set.of();
        } catch (Exception exception) {
            messages.add("Tooling API runtime classpath resolution failed: " + exception.getMessage());
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
                    if (output.getOutputDir() != null) {
                        entries.add(output.getOutputDir().toPath());
                    }
                    if (output.getTestOutputDir() != null) {
                        entries.add(output.getTestOutputDir().toPath());
                    }
                }

                for (IdeaDependency dependency : module.getDependencies()) {
                    if (dependency instanceof IdeaSingleEntryLibraryDependency libraryDependency && libraryDependency.getFile() != null) {
                        entries.add(libraryDependency.getFile().toPath());
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
            EclipseProject project = connection.getModel(EclipseProject.class);
            if (project == null) {
                return Set.of();
            }
            Set<Path> entries = new LinkedHashSet<>();
            collectEclipseProjectClasspath(project, entries, new LinkedHashSet<>());
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
        configureJavaHome(builder);
        Process process = builder.start();
        String stdout;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            stdout = reader.lines().collect(Collectors.joining(System.lineSeparator()));
        }
        int exitCode = process.waitFor();
        return new GradleTaskResult(exitCode == 0, exitCode, stdout);
    }

    private void configureJavaHome(ProcessBuilder processBuilder) {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            return;
        }
        processBuilder.environment().put("JAVA_HOME", javaHome);
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
        Path tempScript = Files.createTempFile("print-runtime-classpath", ".gradle");
        Files.writeString(tempScript, script, StandardCharsets.UTF_8);
        return tempScript;
    }

    private String determineGradlePath(Path buildRoot, Path testClassFile) {
        Path relative = buildRoot.toAbsolutePath().normalize().relativize(testClassFile.toAbsolutePath().normalize());
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

    private Path resolveModuleRoot(Path buildRoot, String modulePath) {
        return modulePath.isBlank()
                ? buildRoot.toAbsolutePath().normalize()
                : buildRoot.toAbsolutePath().normalize().resolve(Path.of(modulePath.replace(":", "/")));
    }

    private record GradleTaskResult(boolean success, int exitCode, String stdout) {
    }
}
