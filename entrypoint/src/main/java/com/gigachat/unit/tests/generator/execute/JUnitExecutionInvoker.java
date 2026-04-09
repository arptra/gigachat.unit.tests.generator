package com.gigachat.unit.tests.generator.execute;

import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Executes generated tests using Gradle.
 */
public class JUnitExecutionInvoker implements ExecutionInvoker {
    private final PipelineLogger logger;

    public JUnitExecutionInvoker(PipelineLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public ExecuteResult execute(Path projectRoot, Path testClassFile, String methodName) {
        boolean executeWholeSuite = methodName == null || methodName.isBlank();
        List<String> command = buildGradleCommand(projectRoot, testClassFile, methodName, executeWholeSuite);
        if (command.isEmpty()) {
            String message = "Gradle wrapper or executable was not found for project " + projectRoot;
            logger.error(message);
            return new ExecuteResult(false,
                    failedTests(projectRoot, testClassFile, methodName, executeWholeSuite),
                    "",
                    message);
        }

        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command(command);
        processBuilder.directory(projectRoot.toFile());
        logger.info("[EXECUTION] Starting test execution for "
                + (executeWholeSuite ? "all tests" : determineTestPattern(projectRoot, testClassFile, methodName))
                + " in " + testClassFile);
        logger.info("[EXECUTION] Command: " + String.join(" ", command));
        logger.info("[EXECUTION] Working directory: " + projectRoot.toAbsolutePath().normalize());
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
                return new ExecuteResult(false,
                        failedTests(projectRoot, testClassFile, methodName, executeWholeSuite),
                        stdout,
                        stderr.isBlank() ? "Gradle test execution exited with code " + exitCode : stderr);
            }
            logger.info("[EXECUTION] Test execution finished successfully for " + testClassFile);
            return new ExecuteResult(true, List.of(), stdout, stderr);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.error("[EXECUTION] Execution interrupted for " + testClassFile, exception);
            return new ExecuteResult(false,
                    failedTests(projectRoot, testClassFile, methodName, executeWholeSuite),
                    "",
                    exception.getMessage());
        } catch (ExecutionException exception) {
            logger.error("[EXECUTION] Execution output collection failed for " + testClassFile, exception);
            return new ExecuteResult(false,
                    failedTests(projectRoot, testClassFile, methodName, executeWholeSuite),
                    "",
                    exception.getMessage());
        } catch (IOException exception) {
            logger.error("[EXECUTION] Execution failed for " + testClassFile, exception);
            return new ExecuteResult(false,
                    failedTests(projectRoot, testClassFile, methodName, executeWholeSuite),
                    "",
                    exception.getMessage());
        }
    }

    List<String> buildGradleCommand(Path projectRoot, Path testClassFile, String methodName, boolean executeWholeSuite) {
        List<String> command = new ArrayList<>();
        Path gradleExecutable = resolveGradleExecutable(projectRoot);
        if (gradleExecutable != null) {
            command.add(gradleExecutable.toAbsolutePath().normalize().toString());
        } else {
            command.add("gradle");
        }
        command.add("--no-daemon");
        command.add("--console=plain");
        command.add(resolveGradleTestTask(projectRoot, testClassFile));
        if (!executeWholeSuite) {
            command.add("--tests");
            command.add(determineTestPattern(projectRoot, testClassFile, methodName));
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
        if (executeWholeSuite) {
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

    private Path resolveGradleExecutable(Path projectRoot) {
        Path gradlew = projectRoot.resolve("gradlew");
        if (Files.exists(gradlew)) {
            return gradlew;
        }
        Path gradlewBat = projectRoot.resolve("gradlew.bat");
        if (Files.exists(gradlewBat)) {
            return gradlewBat;
        }
        return null;
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
}
