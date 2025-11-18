package com.gigachat.unit.tests.generator.cleaner;

import com.gigachat.unit.tests.generator.cleaner.rules.DanglingTestAnnotationRule;
import com.gigachat.unit.tests.generator.cleaner.rules.MissingImportRule;
import com.gigachat.unit.tests.generator.cleaner.rules.StubAssertionRemovalRule;
import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Coordinates the cleaner mode. The cleaner performs the following steps:
 * <ol>
 *     <li>Applies a configurable list of {@link CleanerRule rules} to every discovered test class.</li>
 *     <li>Compiles each remaining test method individually and removes tests that fail to compile.</li>
 *     <li>Executes the surviving tests and removes flaky methods, deleting a class entirely when all
 *     test methods fail.</li>
 * </ol>
 */
public class TestCleaner {
    private final PipelineLogger logger;
    private final CompilerInvoker compilerInvoker;
    private final ExecutionInvoker executionInvoker;
    private final JavaParser javaParser = new JavaParser();
    private final Function<ProjectClassIndex, List<CleanerRule>> rulesProvider;

    public TestCleaner(PipelineLogger logger,
                       CompilerInvoker compilerInvoker,
                       ExecutionInvoker executionInvoker) {
        this(logger, compilerInvoker, executionInvoker, TestCleaner::defaultRules);
    }

    TestCleaner(PipelineLogger logger,
                CompilerInvoker compilerInvoker,
                ExecutionInvoker executionInvoker,
                Function<ProjectClassIndex, List<CleanerRule>> rulesProvider) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.executionInvoker = Objects.requireNonNull(executionInvoker, "executionInvoker");
        this.rulesProvider = Objects.requireNonNull(rulesProvider, "rulesProvider");
    }

    public void clean(AgentConfig config) throws IOException {
        Path projectRoot = config.getProjectPath();
        logger.info("Starting cleaner mode for project " + projectRoot);
        List<Path> testFiles = discoverTestFiles(projectRoot, config.getIncludeModules());
        if (testFiles.isEmpty()) {
            logger.warn("No tests discovered under " + projectRoot + ". Nothing to clean.");
            return;
        }
        ProjectClassIndex classIndex = new ProjectClassIndex(projectRoot);
        List<CleanerRule> rules = rulesProvider.apply(classIndex);
        logger.info("Applying " + rules.size() + " cleaner rules to " + testFiles.size() + " test files.");
        for (Path testFile : testFiles) {
            applyRules(testFile, rules);
        }
        logger.info("Cleaner rules applied. Starting compilation pass.");
        runCompilationStage(config);
        logger.info("Compilation pass finished. Starting execution pass.");
        runExecutionStage(config);
        logger.info("Cleaner mode finished.");
    }

    private static List<CleanerRule> defaultRules(ProjectClassIndex index) {
        return List.of(
                new MissingImportRule(index),
                new DanglingTestAnnotationRule(),
                new StubAssertionRemovalRule()
        );
    }

    private void applyRules(Path testFile, List<CleanerRule> rules) {
        TestFileContext context = new TestFileContext(testFile, javaParser);
        for (CleanerRule rule : rules) {
            try {
                rule.apply(context);
            } catch (IOException exception) {
                logger.error("Failed to apply rule " + rule.getClass().getSimpleName() +
                        " to " + testFile + ": " + exception.getMessage());
            }
        }
        try {
            context.saveIfDirty();
        } catch (IOException exception) {
            logger.error("Unable to persist changes for " + testFile + ": " + exception.getMessage());
        }
    }

    private void runCompilationStage(AgentConfig config) throws IOException {
        List<Path> testFiles = discoverTestFiles(config.getProjectPath(), config.getIncludeModules());
        for (Path testFile : testFiles) {
            processStage(config, testFile, "compilation", (cfg, file, method) -> {
                CompileResult result = compilerInvoker.compile(cfg.getProjectPath(), file, method.getNameAsString());
                if (result.success()) {
                    return List.of();
                }
                String combinedOutput = (result.stdout() + System.lineSeparator() + result.stderr()).trim();
                logger.warn("Compilation failed for " + method.getNameAsString() +
                        " in " + file + ": " + combinedOutput);
                List<String> parsed = parseFailingMethods(combinedOutput);
                return parsed.isEmpty() ? List.of(method.getNameAsString()) : parsed;
            });
        }
    }

    private void runExecutionStage(AgentConfig config) throws IOException {
        List<Path> testFiles = discoverTestFiles(config.getProjectPath(), config.getIncludeModules());
        for (Path testFile : testFiles) {
            processStage(config, testFile, "execution", (cfg, file, method) -> {
                ExecuteResult result = executionInvoker.execute(cfg.getProjectPath(), file, method.getNameAsString());
                if (result.success()) {
                    return List.of();
                }
                String combinedOutput = (result.stdout() + System.lineSeparator() + result.stderr()).trim();
                logger.warn("Execution failed for " + method.getNameAsString() +
                        " in " + file + ": " + combinedOutput);
                return List.of(method.getNameAsString());
            });
        }
    }

    private void processStage(AgentConfig config,
                              Path testFile,
                              String stageName,
                              StageEvaluator evaluator) {
        if (!Files.exists(testFile)) {
            return;
        }
        TestFileContext context = new TestFileContext(testFile, javaParser);
        Optional<CompilationUnit> maybeUnit;
        try {
            maybeUnit = context.getCompilationUnit();
        } catch (IOException exception) {
            logger.error("Unable to parse " + testFile + ": " + exception.getMessage());
            return;
        }
        CompilationUnit unit = maybeUnit.orElse(null);
        if (unit == null) {
            logger.warn("Skipping " + testFile + " because it could not be parsed.");
            return;
        }
        List<MethodDeclaration> testMethods = findTestMethods(unit);
        if (testMethods.isEmpty()) {
            return;
        }
        List<String> failingMethodNames = new ArrayList<>();
        for (MethodDeclaration method : testMethods) {
            failingMethodNames.addAll(evaluator.failingMethods(config, testFile, method));
        }
        if (failingMethodNames.isEmpty()) {
            return;
        }
        List<MethodDeclaration> toRemove = testMethods.stream()
                .filter(method -> failingMethodNames.contains(method.getNameAsString()))
                .collect(Collectors.toList());
        toRemove.forEach(MethodDeclaration::remove);
        context.markAstDirty();
        List<MethodDeclaration> remaining = findTestMethods(unit);
        try {
            if (remaining.isEmpty()) {
                logger.warn("Removing test class " + testFile + " because all tests failed during " + stageName + ".");
                context.deleteFile();
            } else {
                logger.warn("Removed " + toRemove.size() + " failing tests from " + testFile + " during " + stageName + ".");
                context.saveIfDirty();
            }
        } catch (IOException exception) {
            logger.error("Unable to persist stage results for " + testFile + ": " + exception.getMessage());
        }
    }

    private List<Path> discoverTestFiles(Path projectRoot, List<String> includeModules) throws IOException {
        List<Path> moduleRoots = determineModuleRoots(projectRoot, includeModules);
        List<Path> testFiles = new ArrayList<>();
        for (Path moduleRoot : moduleRoots) {
            Path testRoot = moduleRoot.resolve(Path.of("src", "test", "java"));
            if (!Files.exists(testRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(testRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(testFiles::add);
            }
        }
        return List.copyOf(testFiles);
    }

    private List<Path> determineModuleRoots(Path projectRoot, List<String> includeModules) throws IOException {
        if (includeModules == null || includeModules.isEmpty()) {
            return List.of(projectRoot);
        }
        List<Path> modules = new ArrayList<>();
        for (String module : includeModules) {
            Path modulePath = projectRoot.resolve(module);
            if (Files.exists(modulePath)) {
                modules.add(modulePath);
            }
        }
        if (modules.isEmpty()) {
            return List.of(projectRoot);
        }
        return List.copyOf(modules);
    }

    private List<MethodDeclaration> findTestMethods(CompilationUnit unit) {
        return unit.findAll(MethodDeclaration.class).stream()
                .filter(this::isTestMethod)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private boolean isTestMethod(MethodDeclaration methodDeclaration) {
        return methodDeclaration.getAnnotations().stream()
                .map(annotation -> annotation.getName().getIdentifier())
                .anyMatch(name -> name.endsWith("Test"));
    }

    private List<String> parseFailingMethods(String logOutput) {
        if (logOutput == null || logOutput.isBlank()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        List<String> lines = logOutput.lines().collect(Collectors.toList());
        for (String line : lines) {
            String trimmed = line.trim();
            // Pattern: com.example.SampleTest > failsToCompile FAILED
            if (trimmed.contains(">")) {
                String[] parts = trimmed.split(">", 2);
                if (parts.length == 2) {
                    String candidate = parts[1].trim();
                    String methodName = candidate.split("\\s+")[0].replace("()", "");
                    if (!methodName.isBlank()) {
                        matches.add(methodName);
                        continue;
                    }
                }
            }
            // Pattern: com.example.SampleTest.failsToCompile FAILED
            if (trimmed.contains(".")) {
                String[] parts = trimmed.split("\\.");
                String methodName = parts[parts.length - 1].split("\\s+")[0].replace("()", "");
                if (!methodName.isBlank()) {
                    matches.add(methodName);
                }
            }
        }
        return matches.stream().distinct().collect(Collectors.toList());
    }

    @FunctionalInterface
    private interface StageEvaluator {
        List<String> failingMethods(AgentConfig config, Path testFile, MethodDeclaration method);
    }
}
