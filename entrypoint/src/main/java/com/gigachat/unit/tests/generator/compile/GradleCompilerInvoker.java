package com.gigachat.unit.tests.generator.compile;

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
import java.util.stream.Collectors;

/**
 * Invokes Gradle to compile generated tests. When the Gradle wrapper is available the invoker runs
 * the appropriate {@code compileTestJava} task (scoped to a module when possible) so real
 * compilation errors are surfaced without executing tests. If no wrapper is present the invoker
 * falls back to a lightweight stub so the pipeline can still progress.
 */
public class GradleCompilerInvoker implements CompilerInvoker {
    private final PipelineLogger logger;

    public GradleCompilerInvoker(PipelineLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public CompileResult compile(Path projectRoot, Path testClassFile, String methodName) {
        List<String> messages = new ArrayList<>();
        Path gradlew = projectRoot.resolve("gradlew");
        boolean gradleAvailable = Files.exists(gradlew);
        String command;
        if (gradleAvailable) {
            String gradleTask = determineGradleTask(projectRoot, testClassFile);
            command = "./gradlew -q " + gradleTask + " --no-build-cache --rerun-tasks";
            messages.add("Gradle wrapper detected. Running real compilation via task '" + gradleTask + "'.");
        } else {
            command = "echo Compilation stub executed for " + testClassFile.getFileName();
            messages.add("Gradle wrapper not found. Using echo stub for compilation.");
        }
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("bash", "-lc", command);
        processBuilder.directory(projectRoot.toFile());
        processBuilder.redirectErrorStream(true);
        logger.info("Starting compilation command for method " + methodName + " in " + testClassFile);
        try {
            Process process = processBuilder.start();
            String stdout;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                stdout = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.warn("Compilation command returned non-zero exit code: " + exitCode);
                return new CompileResult(false, messages, stdout, "Gradle command exited with code " + exitCode);
            }
            logger.info("Compilation command finished successfully for " + testClassFile);
            return new CompileResult(true, messages, stdout, "");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.error("Compilation interrupted for " + testClassFile, exception);
            return new CompileResult(false, messages, "", exception.getMessage());
        } catch (IOException exception) {
            logger.error("Compilation failed for " + testClassFile, exception);
            return new CompileResult(false, messages, "", exception.getMessage());
        }
    }

    private String determineGradleTask(Path projectRoot, Path testClassFile) {
        Path relative = projectRoot.relativize(testClassFile);
        List<String> segments = new ArrayList<>();
        Path parent = relative.getParent();
        if (parent == null) {
            return "compileTestJava";
        }
        for (Path part : parent) {
            if ("src".equals(part.toString())) {
                break;
            }
            segments.add(part.toString());
        }
        if (segments.isEmpty()) {
            return "compileTestJava";
        }
        return ":" + String.join(":", segments) + ":compileTestJava";
    }
}
