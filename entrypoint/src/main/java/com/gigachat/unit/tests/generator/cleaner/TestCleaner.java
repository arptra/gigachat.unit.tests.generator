package com.gigachat.unit.tests.generator.cleaner;

import com.gigachat.unit.tests.generator.cleaner.rules.DanglingTestAnnotationRule;
import com.gigachat.unit.tests.generator.cleaner.rules.ImportAnalyzerCleanerRule;
import com.gigachat.unit.tests.generator.cleaner.rules.MissingImportRule;
import com.gigachat.unit.tests.generator.cleaner.rules.StubAssertionRemovalRule;
import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.compile.GradleCompilerInvoker;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.example.importanalyzer.core.AsyncImportAnalyzerService;
import com.example.importanalyzer.core.ImportAnalyzerBuilder;
import com.gigachat.unit.tests.generator.cleaner.parser.CompilationFailureLocation;
import com.gigachat.unit.tests.generator.cleaner.parser.CompilationFailureLogParser;
import com.gigachat.unit.tests.generator.cleaner.parser.ExecutionFailureLogParser;
import com.gigachat.unit.tests.generator.cleaner.parser.ExecutionFailureParseResult;
import com.gigachat.unit.tests.generator.cleaner.parser.TestFailure;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.gigachat.unit.tests.generator.report.parser.ExecutionReportParser;
import com.gigachat.unit.tests.generator.report.parser.TestReportFailure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
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
    private final BiFunction<ProjectClassIndex, AsyncImportAnalyzerService, List<CleanerRule>> rulesProvider;
    private final CompilationFailureLogParser failureLogParser = new CompilationFailureLogParser();
    private final ExecutionFailureLogParser executionFailureLogParser = new ExecutionFailureLogParser();
    private final ExecutionReportParser executionReportParser = new ExecutionReportParser();

    public TestCleaner(PipelineLogger logger,
                       CompilerInvoker compilerInvoker,
                       ExecutionInvoker executionInvoker) {
        this(logger, compilerInvoker, executionInvoker,
                (index, service) -> defaultRules(index, compilerInvoker, service, logger));
    }

    TestCleaner(PipelineLogger logger,
                CompilerInvoker compilerInvoker,
                ExecutionInvoker executionInvoker,
                BiFunction<ProjectClassIndex, AsyncImportAnalyzerService, List<CleanerRule>> rulesProvider) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.executionInvoker = Objects.requireNonNull(executionInvoker, "executionInvoker");
        this.rulesProvider = Objects.requireNonNull(rulesProvider, "rulesProvider");
    }

    public void clean(AgentConfig config) throws IOException {
        Path projectRoot = config.getProjectPath();
        logger.info("Starting cleaner mode for project " + projectRoot);
        List<Path> moduleRoots = determineModuleRoots(projectRoot, config.getIncludeModules());
        List<Path> testFiles = discoverTestFiles(moduleRoots);
        if (testFiles.isEmpty()) {
            logger.warn("No tests discovered under " + projectRoot + ". Nothing to clean.");
            return;
        }
        ProjectClassIndex classIndex = new ProjectClassIndex(projectRoot);
        AsyncImportAnalyzerService analyzerService = createAnalyzerService(projectRoot, moduleRoots);
        List<CleanerRule> rules = rulesProvider.apply(classIndex, analyzerService);
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

    private static List<CleanerRule> defaultRules(ProjectClassIndex index,
                                                 CompilerInvoker compilerInvoker,
                                                 AsyncImportAnalyzerService analyzerService,
                                                 PipelineLogger logger) {
        return List.of(
                new ImportAnalyzerCleanerRule(analyzerService, logger),
                new MissingImportRule(index, compilerInvoker),
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
        Path projectRoot = config.getProjectPath();
        Path compilationTarget = resolveCompilationTarget(config);
        GradleCompilerInvoker gradleCompiler = compilerInvoker instanceof GradleCompilerInvoker
                ? (GradleCompilerInvoker) compilerInvoker
                : new GradleCompilerInvoker(logger);

        boolean compilationFinished = false;
        while (!compilationFinished) {
            CompileResult result = gradleCompiler.compile(projectRoot, compilationTarget, "");
            result.messages().forEach(logger::info);
            if (result.success()) {
                compilationFinished = true;
                continue;
            }

            String combinedOutput = (result.stdout() + System.lineSeparator() + result.stderr()).trim();
            logger.warn("Compilation failed. Parsing log for failing tests." + (combinedOutput.isBlank() ? "" : " Output: " + combinedOutput));
            List<CompilationFailureLocation> failing = failureLogParser.parse(combinedOutput);
            logger.info("Parsed failing locations: " + failing);
            if (failing.isEmpty()) {
                logger.error("Compilation failed but no failing tests could be parsed. Aborting cleaner compilation stage.");
                return;
            }
            boolean removed = removeFailingTests(config, failing);
            if (!removed) {
                logger.error("Failed to remove any tests for parsed compilation failures. Aborting cleaner compilation stage.");
                return;
            }
        }
    }

    private Path resolveCompilationTarget(AgentConfig config) throws IOException {
        Path projectRoot = config.getProjectPath();
        for (Path moduleRoot : determineModuleRoots(projectRoot, config.getIncludeModules())) {
            Path testRoot = moduleRoot.resolve(Path.of("src", "test", "java"));
            if (Files.exists(testRoot)) {
                return testRoot;
            }
        }
        return projectRoot;
    }

    private void runExecutionStage(AgentConfig config) throws IOException {
        ExecuteResult result = executionInvoker.execute(config.getProjectPath(), config.getProjectPath(), "");
        if (result.success()) {
            return;
        }

        String combinedOutput = (result.stdout() + System.lineSeparator() + result.stderr()).trim();
        ExecutionFailureParseResult parsedLog = executionFailureLogParser.parse(combinedOutput);
        List<TestFailure> failingTests = new ArrayList<>(parsedLog.failures());

        parsedLog.reportPath().ifPresent(reportPath -> {
            try {
                List<TestReportFailure> reportFailures = executionReportParser.parse(reportPath);
                for (TestReportFailure failure : reportFailures) {
                    failingTests.add(new TestFailure(failure.className(), failure.methodName()));
                }
            } catch (IOException exception) {
                logger.warn("Unable to parse execution report at " + reportPath + ": " + exception.getMessage());
            }
        });

        if (failingTests.isEmpty()) {
            logger.warn("Execution failed but no failing tests could be parsed. Skipping removal.");
            return;
        }

        removeExecutionFailures(config, failingTests);
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
        List<String> failingMethodNames = evaluator.failingMethods(config, testFile, testMethods);
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

    private List<Path> discoverTestFiles(List<Path> moduleRoots) throws IOException {
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

    private AsyncImportAnalyzerService createAnalyzerService(Path projectRoot, List<Path> moduleRoots) {
        ImportAnalyzerBuilder builder = new ImportAnalyzerBuilder()
                .projectRoot(projectRoot)
                .includeDependencies(true)
                .threads(Runtime.getRuntime().availableProcessors());
        for (Path moduleRoot : moduleRoots) {
            builder.sourceRoot(moduleRoot.resolve(Path.of("src", "main", "java")));
            builder.testSourceRoot(moduleRoot.resolve(Path.of("src", "test", "java")));
        }
        AsyncImportAnalyzerService service = new AsyncImportAnalyzerService(builder.buildConfig());
        service.startScan();
        return service;
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

    private boolean removeFailingTests(AgentConfig config, List<CompilationFailureLocation> failing) throws IOException {
        List<Path> discoveredTests = discoverTestFiles(determineModuleRoots(config.getProjectPath(), config.getIncludeModules()));
        logger.info("Found " + discoveredTests.size() + " test files while removing failures");

        List<Path> failingFiles = failing.stream()
                .map(CompilationFailureLocation::filePath)
                .filter(Objects::nonNull)
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .collect(Collectors.toList());

        boolean removedAnything = false;
        for (Path failingFile : failingFiles) {
            Path testFile = resolveTestFile(failingFile, discoveredTests);
            if (testFile == null) {
                logger.warn("Unable to resolve failing test file " + failingFile + " against discovered tests");
                continue;
            }

            TestFileContext context = new TestFileContext(testFile, javaParser);
            Optional<CompilationUnit> unitOpt = context.getCompilationUnit();
            if (unitOpt.isEmpty()) {
                continue;
            }
            CompilationUnit unit = unitOpt.get();
            List<CompilationFailureLocation> fileFailures = failing.stream()
                    .filter(failure -> pathsMatch(testFile, failure.filePath()))
                    .collect(Collectors.toList());
            if (fileFailures.isEmpty()) {
                continue;
            }

            logger.info("Cleaning failing targets " + fileFailures + " from " + testFile.getFileName());

            List<MethodDeclaration> testMethods = findTestMethods(unit);
            List<MethodDeclaration> methodsToRemove = new ArrayList<>();
            boolean removeWholeClass = false;
            for (CompilationFailureLocation failure : fileFailures) {
                boolean matchedMethod = false;
                for (MethodDeclaration method : testMethods) {
                    if (method.getRange().isEmpty()) {
                        continue;
                    }
                    int beginLine = method.getRange().get().begin.line;
                    int endLine = method.getRange().get().end.line;
                    if (failure.lineNumber() >= beginLine && failure.lineNumber() <= endLine) {
                        methodsToRemove.add(method);
                        matchedMethod = true;
                    }
                }
                if (!matchedMethod) {
                    removeWholeClass = true;
                }
            }

            if (removeWholeClass || methodsToRemove.size() == testMethods.size()) {
                context.deleteFile();
                removedAnything = true;
                continue;
            }

            if (!methodsToRemove.isEmpty()) {
                methodsToRemove.stream().distinct().forEach(MethodDeclaration::remove);
                context.markAstDirty();
                context.saveIfDirty();
                removedAnything = true;
            }
        }
        return removedAnything;
    }

    private void removeExecutionFailures(AgentConfig config, List<TestFailure> failingTests) throws IOException {
        List<Path> discoveredTests = discoverTestFiles(determineModuleRoots(config.getProjectPath(), config.getIncludeModules()));
        logger.info("Processing execution failures across " + discoveredTests.size() + " discovered tests");

        for (TestFailure failure : failingTests) {
            if (failure.className().isBlank()) {
                continue;
            }
            Path testFile = resolveTestFileByClassName(failure.className(), discoveredTests);
            if (testFile == null) {
                logger.warn("Unable to resolve test class for failure " + failure.className());
                continue;
            }

            TestFileContext context = new TestFileContext(testFile, javaParser);
            Optional<CompilationUnit> unitOpt = context.getCompilationUnit();
            if (unitOpt.isEmpty()) {
                continue;
            }
            CompilationUnit unit = unitOpt.get();
            List<MethodDeclaration> testMethods = findTestMethods(unit);
            List<MethodDeclaration> matching = testMethods.stream()
                    .filter(method -> method.getNameAsString().equals(failure.methodName()))
                    .collect(Collectors.toList());

            if (matching.isEmpty()) {
                logger.warn("No matching method found for failure " + failure + " in " + testFile.getFileName() + ". Skipping.");
                continue;
            }

            matching.forEach(MethodDeclaration::remove);
            context.markAstDirty();

            List<MethodDeclaration> remaining = findTestMethods(unit);
            if (remaining.isEmpty()) {
                context.deleteFile();
                continue;
            }

            context.saveIfDirty();
        }
    }

    private Path resolveTestFileByClassName(String className, List<Path> discoveredTests) {
        if (className == null || className.isBlank()) {
            return null;
        }

        String trimmed = className.trim();
        String relativePath = trimmed.replace('.', '/') + (trimmed.endsWith(".java") ? "" : ".java");
        String normalizedRelative = relativePath.replace('\\', '/');

        Optional<Path> fqcnMatch = discoveredTests.stream()
                .filter(path -> path.toString().replace('\\', '/').endsWith(normalizedRelative))
                .findFirst();
        if (fqcnMatch.isPresent()) {
            return fqcnMatch.get();
        }

        String simpleName = trimmed.contains(".")
                ? trimmed.substring(trimmed.lastIndexOf('.') + 1)
                : trimmed;
        String expectedName = simpleName.endsWith(".java") ? simpleName : simpleName + ".java";
        return discoveredTests.stream()
                .filter(path -> path.getFileName().toString().equals(expectedName))
                .findFirst()
                .orElse(null);
    }

    private Path resolveTestFile(Path failingFile, List<Path> discoveredTests) {
        Path normalizedFailing = failingFile.toAbsolutePath().normalize();
        if (Files.exists(normalizedFailing)) {
            return normalizedFailing;
        }
        return discoveredTests.stream()
                .filter(testPath -> pathsMatch(testPath, normalizedFailing))
                .findFirst()
                .orElse(null);
    }

    private boolean pathsMatch(Path testPath, Path failurePath) {
        if (failurePath == null) {
            return false;
        }
        Path normalizedFailure = failurePath.toAbsolutePath().normalize();
        if (normalizedFailure.equals(testPath)) {
            return true;
        }
        return normalizedFailure.getFileName() != null
                && normalizedFailure.getFileName().equals(testPath.getFileName())
                && normalizedFailure.toString().endsWith(testPath.getFileName().toString());
    }

    @FunctionalInterface
    private interface StageEvaluator {
        List<String> failingMethods(AgentConfig config, Path testFile, List<MethodDeclaration> methods);
    }
}
