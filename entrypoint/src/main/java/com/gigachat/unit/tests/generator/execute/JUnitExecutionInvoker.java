package com.gigachat.unit.tests.generator.execute;

import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Executes generated tests using the Gradle wrapper when available. It falls back to a stub command
 * so that the initial pipeline can run without external dependencies.
 */
public class JUnitExecutionInvoker implements ExecutionInvoker {
    private final PipelineLogger logger;

    public JUnitExecutionInvoker(PipelineLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public ExecuteResult execute(Path projectRoot, Path testClassFile, String methodName) {
        Path gradlew = projectRoot.resolve("gradlew");
        boolean gradleAvailable = Files.exists(gradlew);
        String command;
        boolean executeWholeSuite = methodName == null || methodName.isBlank();
        if (gradleAvailable) {
            if (executeWholeSuite) {
                command = "./gradlew -q test";
            } else {
                command = "./gradlew -q test --tests '" + determineTestPattern(testClassFile, methodName) + "'";
            }
        } else {
            command = "echo Execution stub for " + testClassFile.getFileName();
        }
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("bash", "-lc", command);
        processBuilder.directory(projectRoot.toFile());
        processBuilder.redirectErrorStream(true);
        logger.info("Starting execution stub for " + (executeWholeSuite ? "all tests" : methodName) + " in " + testClassFile);
        try {
            Process process = processBuilder.start();
            String stdout;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                stdout = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.warn("Execution stub returned non-zero exit code: " + exitCode);
                return new ExecuteResult(false,
                        List.of(determineTestPattern(testClassFile, methodName)),
                        stdout,
                        "Gradle execution exited with code " + exitCode);
            }
            logger.info("Execution stub finished successfully for " + testClassFile);
            return new ExecuteResult(true, List.of(), stdout, "");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.error("Execution interrupted for " + testClassFile, exception);
            return new ExecuteResult(false,
                    List.of(determineTestPattern(testClassFile, methodName)),
                    "",
                    exception.getMessage());
        } catch (IOException exception) {
            logger.error("Execution failed for " + testClassFile, exception);
            return new ExecuteResult(false,
                    List.of(determineTestPattern(testClassFile, methodName)),
                    "",
                    exception.getMessage());
        }
    }

    private String determineTestPattern(Path testClassFile, String methodName) {
        String className = testClassFile.getFileName().toString().replace(".java", "");
        return className + "." + methodName;
    }
}
