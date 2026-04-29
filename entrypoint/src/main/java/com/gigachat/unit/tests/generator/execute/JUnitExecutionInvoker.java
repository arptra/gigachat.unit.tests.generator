package com.gigachat.unit.tests.generator.execute;

import com.gigachat.unit.tests.generator.gradle.ResolvedTestRuntimeClasspath;
import com.gigachat.unit.tests.generator.gradle.TestRuntimeClasspathResolver;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.gradle.GradleBuildLocator;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Executes generated tests using Gradle.
 */
public class JUnitExecutionInvoker implements ExecutionInvoker {
    private final PipelineLogger logger;
    private final TestRuntimeClasspathResolver classpathResolver;

    public JUnitExecutionInvoker(PipelineLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.classpathResolver = new TestRuntimeClasspathResolver(logger);
    }

    @Override
    public ExecuteResult execute(Path projectRoot, Path testClassFile, String methodName) {
        boolean executeWholeSuite = shouldExecuteWholeSuite(testClassFile, methodName);
        Path buildRoot = GradleBuildLocator.findInvocationRoot(projectRoot);
        if (!executeWholeSuite && canExecuteViaForkedRunner(projectRoot, testClassFile)) {
            ResolvedTestRuntimeClasspath runtimeClasspath = classpathResolver.resolve(projectRoot, testClassFile);
            runtimeClasspath.messages().forEach(message -> logger.info("[EXECUTION][classpath] " + message));
            List<String> directCommand = buildDirectCommand(runtimeClasspath, projectRoot, testClassFile, methodName);
            if (!directCommand.isEmpty()) {
                return executeCommand(directCommand,
                        runtimeClasspath.moduleRoot(),
                        testClassFile,
                        determineExecutionLabel(projectRoot, testClassFile, methodName, executeWholeSuite),
                        failedTests(projectRoot, testClassFile, methodName, executeWholeSuite),
                        "Forked JUnit execution");
            }
        }

        ExecutionTarget executionTarget = resolveExecutionTarget(buildRoot, projectRoot, testClassFile);
        List<String> command = buildGradleCommand(buildRoot, executionTarget, testClassFile, methodName, executeWholeSuite);
        if (command.isEmpty()) {
            String message = "Gradle wrapper or executable was not found for project " + buildRoot;
            logger.error(message);
            return new ExecuteResult(false,
                    failedTests(buildRoot, testClassFile, methodName, executeWholeSuite),
                    "",
                    message);
        }

        return executeCommand(command,
                executionTarget.workingDirectory(),
                testClassFile,
                determineExecutionLabel(buildRoot, testClassFile, methodName, executeWholeSuite),
                failedTests(buildRoot, testClassFile, methodName, executeWholeSuite),
                "Gradle test execution");
    }

    private ExecuteResult executeCommand(List<String> command,
                                         Path workingDirectory,
                                         Path testClassFile,
                                         String executionLabel,
                                         List<String> failedTests,
                                         String failurePrefix) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory.toFile());
        configureJavaHome(processBuilder);
        logger.info("[EXECUTION] Starting test execution for " + executionLabel + " in " + testClassFile);
        logger.trace("EXECUTION", executionLabel, "RUN_TEST", "starting test execution");
        logger.info("[EXECUTION] Command: " + String.join(" ", command));
        logger.info("[EXECUTION] Working directory: " + workingDirectory.toAbsolutePath().normalize());
        try {
            Process process = processBuilder.start();
            logger.info("[EXECUTION] Process started with pid=" + process.pid());
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> readStream(process.getInputStream(),
                    "stdout",
                    false));
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream(),
                    "stderr",
                    true));
            int exitCode = process.waitFor();
            String stdout = stdoutFuture.get();
            String stderr = stderrFuture.get();
            logger.info("[EXECUTION] Process finished with exitCode=" + exitCode);
            if (exitCode != 0) {
                logger.warn("[EXECUTION] Test execution returned non-zero exit code: " + exitCode);
                logger.trace("RESULT", executionLabel, "EXECUTION_FAILED", "test execution failed exitCode=" + exitCode);
                String failureMessage = stderr.isBlank()
                        ? failurePrefix + " exited with code " + exitCode
                        : stderr;
                return new ExecuteResult(false, failedTests, stdout, failureMessage);
            }
            logger.info("[EXECUTION] Test execution finished successfully for " + testClassFile);
            logger.trace("RESULT", executionLabel, "EXECUTION_SUCCESS", "test execution finished successfully");
            return new ExecuteResult(true, List.of(), stdout, stderr);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.error("[EXECUTION] Execution interrupted for " + testClassFile, exception);
            logger.trace("RESULT", executionLabel, "EXECUTION_FAILED", "execution interrupted");
            return new ExecuteResult(false, failedTests, "", exception.getMessage());
        } catch (ExecutionException exception) {
            logger.error("[EXECUTION] Execution output collection failed for " + testClassFile, exception);
            logger.trace("RESULT", executionLabel, "EXECUTION_FAILED", "execution output collection failed");
            return new ExecuteResult(false, failedTests, "", exception.getMessage());
        } catch (IOException exception) {
            logger.error("[EXECUTION] Execution failed for " + testClassFile, exception);
            logger.trace("RESULT", executionLabel, "EXECUTION_FAILED", "execution command failed");
            return new ExecuteResult(false, failedTests, "", exception.getMessage());
        }
    }

    List<String> buildGradleCommand(Path projectRoot, Path testClassFile, String methodName, boolean executeWholeSuite) {
        Path buildRoot = GradleBuildLocator.findInvocationRoot(projectRoot);
        ExecutionTarget executionTarget = resolveExecutionTarget(buildRoot, projectRoot, testClassFile);
        return buildGradleCommand(buildRoot, executionTarget, testClassFile, methodName, executeWholeSuite);
    }

    private List<String> buildGradleCommand(Path buildRoot,
                                            ExecutionTarget executionTarget,
                                            Path testClassFile,
                                            String methodName,
                                            boolean executeWholeSuite) {
        List<String> command = new ArrayList<>();
        String gradleCommand = GradleBuildLocator.resolveGradleCommand(buildRoot);
        if (gradleCommand == null) {
            return List.of();
        }
        command.add(gradleCommand);
        command.add("--no-daemon");
        command.add("--console=plain");
        if (executionTarget.settingsFile() != null) {
            command.add("--settings-file");
            command.add(executionTarget.settingsFile().toAbsolutePath().normalize().toString());
        }
        command.add(executionTarget.taskName());
        if (!executeWholeSuite && testClassFile != null) {
            command.add("--tests");
            command.add(determineTestPattern(buildRoot, testClassFile, methodName));
        }
        return command;
    }

    String determineTestPattern(Path projectRoot, Path testClassFile, String methodName) {
        String className = determineTestClassName(projectRoot, testClassFile);
        if (methodName == null || methodName.isBlank()) {
            return className;
        }
        return className + "." + methodName;
    }

    private List<String> failedTests(Path projectRoot, Path testClassFile, String methodName, boolean executeWholeSuite) {
        if (executeWholeSuite || testClassFile == null) {
            return List.of();
        }
        return List.of(determineTestPattern(projectRoot, testClassFile, methodName));
    }

    private String determineTestClassName(Path projectRoot, Path testClassFile) {
        String className = testClassFile.getFileName().toString().replace(".java", "");
        Optional<String> pkg = readPackage(testClassFile);
        if (pkg.isPresent() && !pkg.get().isBlank()) {
            return pkg.get() + "." + className;
        }

        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedTestFile = testClassFile.toAbsolutePath().normalize();
        Path rootRelative = normalizedProjectRoot.relativize(normalizedTestFile);
        String relative = rootRelative.toString().replace('\\', '/');
        int testRootIndex = relative.indexOf("src/test/java/");
        if (testRootIndex >= 0) {
            String typePath = relative.substring(testRootIndex + "src/test/java/".length())
                    .replace('/', '.')
                    .replace(".java", "");
            if (!typePath.isBlank()) {
                return typePath;
            }
        }
        return className;
    }

    private boolean canExecuteViaForkedRunner(Path projectRoot, Path testClassFile) {
        if (testClassFile == null || !Files.isRegularFile(testClassFile)) {
            return false;
        }
        ResolvedTestRuntimeClasspath runtimeClasspath = classpathResolver.resolve(projectRoot, testClassFile);
        if (runtimeClasspath.entries().isEmpty()) {
            return false;
        }
        return Files.exists(resolveCompiledTestClass(runtimeClasspath.moduleRoot(), projectRoot, testClassFile));
    }

    private List<String> buildDirectCommand(ResolvedTestRuntimeClasspath runtimeClasspath,
                                            Path projectRoot,
                                            Path testClassFile,
                                            String methodName) {
        if (runtimeClasspath.entries().isEmpty()) {
            return List.of();
        }
        Path javaExecutable = resolveJavaExecutable();
        String currentClasspath = System.getProperty("java.class.path", "");
        LinkedHashSet<String> classpathEntries = new LinkedHashSet<>();
        runtimeClasspath.entries().stream()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .forEach(classpathEntries::add);
        if (!currentClasspath.isBlank()) {
            for (String part : currentClasspath.split(File.pathSeparator)) {
                if (!part.isBlank()) {
                    classpathEntries.add(part);
                }
            }
        }
        String combinedClasspath = String.join(File.pathSeparator, classpathEntries);
        if (combinedClasspath.isBlank()) {
            return List.of();
        }
        return List.of(
                javaExecutable.toAbsolutePath().normalize().toString(),
                "-cp",
                combinedClasspath,
                ForkedJUnitRunner.class.getName(),
                determineTestClassName(projectRoot, testClassFile),
                methodName == null ? "" : methodName
        );
    }

    private Path resolveCompiledTestClass(Path moduleRoot, Path projectRoot, Path testClassFile) {
        String className = testClassFile.getFileName().toString().replace(".java", "") + ".class";
        Path outputRoot = moduleRoot.resolve("build/classes/java/test");
        Optional<String> pkg = readPackage(testClassFile);
        if (pkg.isPresent() && !pkg.get().isBlank()) {
            return outputRoot.resolve(pkg.get().replace('.', File.separatorChar)).resolve(className);
        }
        String fqcn = determineTestClassName(projectRoot, testClassFile);
        int lastDot = fqcn.lastIndexOf('.');
        if (lastDot >= 0) {
            return outputRoot.resolve(fqcn.substring(0, lastDot).replace('.', File.separatorChar)).resolve(className);
        }
        return outputRoot.resolve(className);
    }

    private Path resolveJavaExecutable() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path executable = javaHome.resolve("bin").resolve("java");
        if (Files.exists(executable)) {
            return executable;
        }
        return Path.of("java");
    }

    private String determineExecutionLabel(Path projectRoot, Path testClassFile, String methodName, boolean executeWholeSuite) {
        if (executeWholeSuite || testClassFile == null) {
            return "all tests";
        }
        return determineTestPattern(projectRoot, testClassFile, methodName);
    }

    private boolean shouldExecuteWholeSuite(Path testClassFile, String methodName) {
        return methodName == null && (testClassFile == null || !Files.isRegularFile(testClassFile));
    }

    private String resolveGradleTestTask(Path projectRoot, Path testClassFile) {
        Path moduleRoot = findModuleRoot(projectRoot, testClassFile);
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedModuleRoot = moduleRoot.toAbsolutePath().normalize();
        if (normalizedModuleRoot.equals(normalizedProjectRoot)) {
            return "test";
        }
        String relative = normalizedProjectRoot.relativize(normalizedModuleRoot).toString().replace('\\', ':');
        if (relative.isBlank()) {
            return "test";
        }
        return ":" + relative + ":test";
    }

    private void configureJavaHome(ProcessBuilder processBuilder) {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            return;
        }
        processBuilder.environment().put("JAVA_HOME", javaHome);
    }

    private ExecutionTarget resolveExecutionTarget(Path buildRoot, Path projectRoot, Path testClassFile) {
        Path normalizedBuildRoot = buildRoot.toAbsolutePath().normalize();
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        if (normalizedBuildRoot.equals(normalizedProjectRoot)) {
            return new ExecutionTarget(resolveGradleTestTask(normalizedBuildRoot, testClassFile),
                    normalizedBuildRoot,
                    null);
        }
        if (hasStandaloneBuild(normalizedProjectRoot) && !isIncludedGradleProject(normalizedBuildRoot, normalizedProjectRoot)) {
            return new ExecutionTarget(resolveGradleTestTask(normalizedProjectRoot, testClassFile),
                    normalizedProjectRoot,
                    ensureStandaloneSettingsFile(normalizedProjectRoot));
        }
        return new ExecutionTarget(resolveGradleTestTask(normalizedBuildRoot, testClassFile),
                normalizedBuildRoot,
                null);
    }

    private Path findModuleRoot(Path projectRoot, Path testClassFile) {
        Path current = testClassFile == null ? projectRoot : testClassFile.toAbsolutePath().normalize().getParent();
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        while (current != null && current.startsWith(normalizedProjectRoot)) {
            Path srcTestJava = current.resolve("src/test/java");
            if (testClassFile != null && testClassFile.toAbsolutePath().normalize().startsWith(srcTestJava.toAbsolutePath().normalize())) {
                return current;
            }
            current = current.getParent();
        }
        return normalizedProjectRoot;
    }

    private boolean hasStandaloneBuild(Path projectRoot) {
        return Files.exists(projectRoot.resolve("build.gradle"))
                || Files.exists(projectRoot.resolve("build.gradle.kts"));
    }

    private Path ensureStandaloneSettingsFile(Path projectRoot) {
        Path groovySettings = projectRoot.resolve("settings.gradle");
        if (Files.exists(groovySettings)) {
            return groovySettings;
        }
        Path kotlinSettings = projectRoot.resolve("settings.gradle.kts");
        if (Files.exists(kotlinSettings)) {
            return kotlinSettings;
        }
        Path generatedSettings = projectRoot.resolve(".gigachat-standalone-settings.gradle");
        if (Files.exists(generatedSettings)) {
            return generatedSettings;
        }
        String projectName = projectRoot.getFileName() == null
                ? "generated-tests"
                : projectRoot.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "-");
        try {
            Files.writeString(generatedSettings,
                    "rootProject.name = '" + projectName + "'" + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            logger.warn("Unable to create standalone settings file for " + projectRoot + ": " + exception.getMessage());
            return null;
        }
        return generatedSettings;
    }

    private boolean isIncludedGradleProject(Path buildRoot, Path projectRoot) {
        Path normalizedBuildRoot = buildRoot.toAbsolutePath().normalize();
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        if (normalizedBuildRoot.equals(normalizedProjectRoot)) {
            return true;
        }
        Path relative = normalizedBuildRoot.relativize(normalizedProjectRoot);
        if (relative.getNameCount() == 0) {
            return true;
        }
        String candidatePath = ":" + relative.toString().replace('\\', ':').replace('/', ':');
        return readIncludedProjects(normalizedBuildRoot).contains(candidatePath);
    }

    private Set<String> readIncludedProjects(Path buildRoot) {
        Set<String> includes = new LinkedHashSet<>();
        for (String fileName : List.of("settings.gradle", "settings.gradle.kts")) {
            Path settingsFile = buildRoot.resolve(fileName);
            if (!Files.isRegularFile(settingsFile)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(settingsFile, StandardCharsets.UTF_8)) {
                    if (!line.contains("include")) {
                        continue;
                    }
                    extractQuotedValues(line).forEach(value -> includes.add(normalizeProjectPath(value)));
                }
            } catch (IOException exception) {
                logger.warn("Unable to inspect Gradle settings at " + settingsFile + ": " + exception.getMessage());
            }
        }
        return includes;
    }

    private List<String> extractQuotedValues(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < line.length(); index++) {
            char currentChar = line.charAt(index);
            if (quote == 0 && (currentChar == '\'' || currentChar == '"')) {
                quote = currentChar;
                current.setLength(0);
                continue;
            }
            if (quote != 0 && currentChar == quote) {
                values.add(current.toString());
                quote = 0;
                continue;
            }
            if (quote != 0) {
                current.append(currentChar);
            }
        }
        return values;
    }

    private String normalizeProjectPath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.startsWith(":") ? trimmed : ":" + trimmed;
    }

    private Optional<String> readPackage(Path testClassFile) {
        if (testClassFile == null || !Files.isRegularFile(testClassFile)) {
            return Optional.empty();
        }
        try {
            return Files.readAllLines(testClassFile, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> line.startsWith("package ") && line.endsWith(";"))
                    .map(line -> line.substring("package ".length(), line.length() - 1).trim())
                    .filter(line -> !line.isBlank())
                    .findFirst();
        } catch (IOException exception) {
            logger.warn("Unable to resolve package name from " + testClassFile + ": " + exception.getMessage());
            return Optional.empty();
        }
    }

    private String readStream(java.io.InputStream stream, String channel, boolean stderrChannel) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) {
                    output.append(System.lineSeparator());
                }
                output.append(line);
                logStreamLine(channel, line, stderrChannel);
            }
            return output.toString();
        } catch (IOException exception) {
            logger.warn("[EXECUTION][" + channel + "] Failed to read process output: " + exception.getMessage());
            return "";
        }
    }

    private void logStreamLine(String channel, String line, boolean stderrChannel) {
        String message = "[EXECUTION][" + channel + "] " + line;
        if (stderrChannel) {
            logger.warn(message);
            return;
        }
        logger.info(message);
    }

    private record ExecutionTarget(String taskName, Path workingDirectory, Path settingsFile) {
    }
}
