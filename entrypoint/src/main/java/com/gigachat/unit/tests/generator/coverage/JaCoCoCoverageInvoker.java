package com.gigachat.unit.tests.generator.coverage;

import com.gigachat.unit.tests.generator.execute.JUnitExecutionInvoker;
import com.gigachat.unit.tests.generator.gradle.GradleBuildLocator;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import java.io.BufferedReader;
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

public class JaCoCoCoverageInvoker implements CoverageInvoker {

    private final PipelineLogger logger;

    public JaCoCoCoverageInvoker(PipelineLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public CoverageResult measure(Path projectRoot,
                                  Path testClassFile,
                                  String generatedTestMethodName,
                                  String targetClassName,
                                  String targetMethodSignature) {
        Path buildRoot = GradleBuildLocator.findInvocationRoot(projectRoot);
        CoverageTarget target = resolveCoverageTarget(buildRoot, projectRoot, testClassFile);
        String testPattern = determineTestPattern(target.moduleRoot(), testClassFile, generatedTestMethodName);
        CoverageScope coverageScope = createCoverageScope(target.moduleRoot(), testClassFile, targetClassName, testPattern);
        List<String> command = buildGradleCommand(target.workingDirectory(), target, testPattern, coverageScope);
        if (command.isEmpty()) {
            return new CoverageResult(false, false, null, null, "", "Gradle executable was not found");
        }
        Path workingDirectory = target.workingDirectory();
        logger.trace("STATE", generatedTestMethodName, "COVERAGE", "running jacoco coverage");
        try {
            logger.info("[COVERAGE] Command: " + String.join(" ", command));
            logger.info("[COVERAGE] Working directory: " + workingDirectory);
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(workingDirectory.toFile());
            configureJavaHome(processBuilder);
            Process process = processBuilder.start();
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> readStream(process.getInputStream()));
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream()));
            int exitCode = process.waitFor();
            String stdout = stdoutFuture.get();
            String stderr = stderrFuture.get();
            if (exitCode != 0) {
                return new CoverageResult(false, false, null, null, stdout, stderr);
            }
            Path xmlReport = resolveXmlReport(target.moduleRoot());
            if (!Files.isRegularFile(xmlReport)) {
                return new CoverageResult(true, false, null, xmlReport, stdout, stderr);
            }
            CoverageSummary summary = parseSummary(xmlReport, targetClassName, targetMethodSignature);
            return new CoverageResult(true, true, summary, xmlReport, stdout, stderr);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new CoverageResult(false, false, null, null, "", exception.getMessage());
        } catch (ExecutionException | IOException exception) {
            return new CoverageResult(false, false, null, null, "", exception.getMessage());
        } finally {
            deleteTemporaryInitScript(coverageScope);
        }
    }

    private List<String> buildGradleCommand(Path buildRoot,
                                            CoverageTarget target,
                                            String testPattern,
                                            CoverageScope coverageScope) {
        List<String> command = new ArrayList<>();
        String gradleCommand = GradleBuildLocator.resolveGradleCommand(buildRoot);
        if (gradleCommand == null) {
            return List.of();
        }
        command.add(gradleCommand);
        command.add("--no-daemon");
        command.add("--console=plain");
        if (target.settingsFile() != null) {
            command.add("--settings-file");
            command.add(target.settingsFile().toAbsolutePath().normalize().toString());
        }
        if (coverageScope != null && coverageScope.initScript() != null) {
            command.add("--init-script");
            command.add(coverageScope.initScript().toAbsolutePath().normalize().toString());
            if (!coverageScope.classPatterns().isEmpty()) {
                command.add("-Dgigachat.coverage.classPatterns=" + String.join(",", coverageScope.classPatterns()));
            }
            if (!coverageScope.sourcePatterns().isEmpty()) {
                command.add("-Dgigachat.coverage.sourcePatterns=" + String.join(",", coverageScope.sourcePatterns()));
            }
            if (coverageScope.testPattern() != null && !coverageScope.testPattern().isBlank()) {
                command.add("-Dgigachat.coverage.testPattern=" + coverageScope.testPattern());
            }
        }
        // Force a fresh scoped test run before generating the report so coverage repair measures
        // the updated generated test class rather than stale JaCoCo/test task outputs.
        command.add("--rerun-tasks");
        if (target.testTaskName() != null && !target.testTaskName().isBlank()) {
            command.add(target.testTaskName());
        }
        command.add(target.reportTaskName());
        return command;
    }

    private String determineTestPattern(Path projectRoot, Path testClassFile, String methodName) {
        String className = determineTestClassName(projectRoot, testClassFile);
        return methodName == null || methodName.isBlank() ? className : className + "." + methodName;
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

    private Optional<String> readPackage(Path javaFile) {
        if (javaFile == null || !Files.isRegularFile(javaFile)) {
            return Optional.empty();
        }
        try {
            for (String line : Files.readAllLines(javaFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                    return Optional.of(trimmed.substring("package ".length(), trimmed.length() - 1).trim());
                }
            }
        } catch (IOException exception) {
            logger.warn("Unable to read package from " + javaFile + ": " + exception.getMessage());
        }
        return Optional.empty();
    }

    private CoverageTarget resolveCoverageTarget(Path buildRoot, Path projectRoot, Path testClassFile) {
        Path normalizedBuildRoot = buildRoot.toAbsolutePath().normalize();
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        if (normalizedBuildRoot.equals(normalizedProjectRoot)) {
            return createCoverageTarget(normalizedBuildRoot, normalizedBuildRoot, null, testClassFile);
        }
        if (hasStandaloneBuild(normalizedProjectRoot) && !isIncludedGradleProject(normalizedBuildRoot, normalizedProjectRoot)) {
            return createCoverageTarget(normalizedProjectRoot,
                    normalizedProjectRoot,
                    ensureStandaloneSettingsFile(normalizedProjectRoot),
                    testClassFile);
        }
        return createCoverageTarget(normalizedBuildRoot, normalizedBuildRoot, null, testClassFile);
    }

    private CoverageTarget createCoverageTarget(Path invocationRoot,
                                                Path taskRoot,
                                                Path settingsFile,
                                                Path testClassFile) {
        Path moduleRoot = findModuleRoot(taskRoot, testClassFile);
        String testTaskName = resolveGradleTask(taskRoot, moduleRoot, "test");
        String reportTaskName = resolveGradleTask(taskRoot, moduleRoot, "jacocoTestReport");
        return new CoverageTarget(testTaskName, reportTaskName, invocationRoot, settingsFile, moduleRoot);
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

    private String resolveGradleTask(Path projectRoot, Path moduleRoot, String baseTask) {
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedModuleRoot = moduleRoot.toAbsolutePath().normalize();
        if (normalizedModuleRoot.equals(normalizedProjectRoot)) {
            return baseTask;
        }
        String relative = normalizedProjectRoot.relativize(normalizedModuleRoot).toString().replace('\\', ':');
        if (relative.isBlank()) {
            return baseTask;
        }
        return ":" + relative + ":" + baseTask;
    }

    private Path resolveXmlReport(Path moduleRoot) {
        return moduleRoot.resolve("build/reports/jacoco/test/jacocoTestReport.xml").toAbsolutePath().normalize();
    }

    private CoverageSummary parseSummary(Path xmlReport, String targetClassName, String targetMethodSignature) {
        String expectedMethodName = extractMethodName(targetMethodSignature);
        String normalizedTargetClass = normalizeClassName(targetClassName);
        String simpleTargetClass = simpleName(targetClassName);
        try {
            Document document = createSecureDocumentBuilderFactory().newDocumentBuilder().parse(xmlReport.toFile());
            NodeList methodNodes = document.getElementsByTagName("method");
            for (int index = 0; index < methodNodes.getLength(); index++) {
                Element method = (Element) methodNodes.item(index);
                String methodName = method.getAttribute("name");
                if (!expectedMethodName.equals(methodName)) {
                    continue;
                }
                Element classElement = (Element) method.getParentNode();
                if (classElement == null || !"class".equals(classElement.getTagName())) {
                    continue;
                }
                String actualClassName = normalizeClassName(classElement.getAttribute("name"));
                boolean matchesFqcn = !normalizedTargetClass.isBlank() && normalizedTargetClass.equals(actualClassName);
                boolean matchesSimple = simpleTargetClass.equals(simpleName(actualClassName));
                if (!matchesFqcn && !matchesSimple) {
                    continue;
                }
                Counter lines = readCounter(method, "LINE");
                Counter branches = readCounter(method, "BRANCH");
                return new CoverageSummary(simpleTargetClass,
                        methodName,
                        lines.covered(),
                        lines.missed(),
                        branches.covered(),
                        branches.missed());
            }
        } catch (Exception exception) {
            logger.warn("Unable to parse JaCoCo XML report " + xmlReport + ": " + exception.getMessage());
        }
        return null;
    }

    private DocumentBuilderFactory createSecureDocumentBuilderFactory() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to configure XML parser for JaCoCo coverage report", exception);
        }
    }

    private Counter readCounter(Element parent, String type) {
        NodeList counters = parent.getElementsByTagName("counter");
        for (int index = 0; index < counters.getLength(); index++) {
            Element counter = (Element) counters.item(index);
            if (!type.equals(counter.getAttribute("type"))) {
                continue;
            }
            int covered = parseInt(counter.getAttribute("covered"));
            int missed = parseInt(counter.getAttribute("missed"));
            return new Counter(covered, missed);
        }
        return new Counter(0, 0);
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private String simpleName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = normalizeClassName(value);
        int lastDot = normalized.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < normalized.length()) {
            return normalized.substring(lastDot + 1);
        }
        return normalized;
    }

    private String normalizeClassName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replace('/', '.').replace('\\', '.');
        if (normalized.endsWith(".class")) {
            normalized = normalized.substring(0, normalized.length() - ".class".length());
        }
        return normalized;
    }

    private String readStream(java.io.InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().reduce((left, right) -> left + System.lineSeparator() + right).orElse("");
        } catch (IOException exception) {
            return exception.getMessage();
        }
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

    private CoverageScope createCoverageScope(Path moduleRoot,
                                              Path testClassFile,
                                              String targetClassName,
                                              String testPattern) {
        List<String> classPatterns = determineTargetClassPatterns(moduleRoot, testClassFile, targetClassName);
        if (classPatterns.isEmpty()) {
            return new CoverageScope(List.of(), List.of(), null, testPattern);
        }
        List<String> sourcePatterns = determineTargetSourcePatterns(moduleRoot, testClassFile, targetClassName);
        Path initScript = createTemporaryCoverageInitScript(moduleRoot);
        return new CoverageScope(classPatterns, sourcePatterns, initScript, testPattern);
    }

    private List<String> determineTargetClassPatterns(Path moduleRoot, Path testClassFile, String targetClassName) {
        String packagePath = determinePackagePath(moduleRoot, testClassFile, targetClassName);
        String simpleClassName = simpleName(targetClassName);
        if (simpleClassName.isBlank()) {
            return List.of();
        }
        String basePath = packagePath.isBlank() ? simpleClassName : packagePath + "/" + simpleClassName;
        return List.of(basePath + ".class", basePath + "$*.class");
    }

    private List<String> determineTargetSourcePatterns(Path moduleRoot, Path testClassFile, String targetClassName) {
        String packagePath = determinePackagePath(moduleRoot, testClassFile, targetClassName);
        String simpleClassName = simpleName(targetClassName);
        if (simpleClassName.isBlank()) {
            return List.of();
        }
        String basePath = packagePath.isBlank() ? simpleClassName : packagePath + "/" + simpleClassName;
        return List.of(basePath + ".java", basePath + ".kt");
    }

    private String determinePackagePath(Path moduleRoot, Path testClassFile, String targetClassName) {
        String normalizedTargetClass = normalizeClassName(targetClassName);
        int lastDot = normalizedTargetClass.lastIndexOf('.');
        if (lastDot > 0) {
            return normalizedTargetClass.substring(0, lastDot).replace('.', '/');
        }
        if (testClassFile == null || moduleRoot == null) {
            return "";
        }
        Path normalizedModuleRoot = moduleRoot.toAbsolutePath().normalize();
        Path normalizedTestFile = testClassFile.toAbsolutePath().normalize();
        if (!normalizedTestFile.startsWith(normalizedModuleRoot)) {
            return "";
        }
        Path relative = normalizedModuleRoot.relativize(normalizedTestFile);
        String relativeString = relative.toString().replace('\\', '/');
        String prefix = "src/test/java/";
        if (!relativeString.startsWith(prefix)) {
            return "";
        }
        String typePath = relativeString.substring(prefix.length());
        int lastSlash = typePath.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "";
        }
        return typePath.substring(0, lastSlash);
    }

    private Path createTemporaryCoverageInitScript(Path moduleRoot) {
        String script = """
                import org.gradle.testing.jacoco.tasks.JacocoReport
                import org.gradle.api.tasks.testing.Test

                gradle.projectsEvaluated {
                    allprojects { project ->
                        def testPattern = System.getProperty("gigachat.coverage.testPattern", "").trim()
                        project.tasks.withType(Test).configureEach { testTask ->
                            if (!testPattern.isEmpty()) {
                                testTask.filter {
                                    includeTestsMatching testPattern
                                    failOnNoMatchingTests = false
                                }
                            }
                        }
                        project.tasks.withType(JacocoReport).configureEach { reportTask ->
                            def classPatterns = System.getProperty("gigachat.coverage.classPatterns", "")
                                    .split(',')
                                    .findAll { !it.trim().isEmpty() }
                            def sourcePatterns = System.getProperty("gigachat.coverage.sourcePatterns", "")
                                    .split(',')
                                    .findAll { !it.trim().isEmpty() }
                            if (!classPatterns.isEmpty()) {
                                def originalClassDirs = reportTask.classDirectories.files
                                reportTask.classDirectories.setFrom(
                                        project.files(originalClassDirs.collect { file ->
                                            file.isDirectory()
                                                    ? project.fileTree(dir: file, include: classPatterns)
                                                    : project.files()
                                        })
                                )
                            }
                            if (!sourcePatterns.isEmpty()) {
                                def originalSourceDirs = reportTask.sourceDirectories.files
                                reportTask.sourceDirectories.setFrom(
                                        project.files(originalSourceDirs.collect { file ->
                                            file.isDirectory()
                                                    ? project.fileTree(dir: file, include: sourcePatterns)
                                                    : project.files()
                                        })
                                )
                            }
                        }
                    }
                }
                """;
        try {
            Path tempDir = moduleRoot.resolve(".agent/tmp");
            Files.createDirectories(tempDir);
            Path scriptPath = Files.createTempFile(tempDir, "gigachat-jacoco-scope-", ".init.gradle");
            Files.writeString(scriptPath, script, StandardCharsets.UTF_8);
            return scriptPath;
        } catch (IOException exception) {
            logger.warn("Unable to create temporary coverage init script under " + moduleRoot + ": " + exception.getMessage());
            return null;
        }
    }

    private void deleteTemporaryInitScript(CoverageScope coverageScope) {
        if (coverageScope == null || coverageScope.initScript() == null) {
            return;
        }
        try {
            Files.deleteIfExists(coverageScope.initScript());
        } catch (IOException exception) {
            logger.warn("Unable to delete temporary coverage init script " + coverageScope.initScript()
                    + ": " + exception.getMessage());
        }
    }

    private void configureJavaHome(ProcessBuilder processBuilder) {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            return;
        }
        processBuilder.environment().put("JAVA_HOME", javaHome);
    }

    private record CoverageTarget(String testTaskName,
                                  String reportTaskName,
                                  Path workingDirectory,
                                  Path settingsFile,
                                  Path moduleRoot) {
    }

    private record CoverageScope(List<String> classPatterns,
                                 List<String> sourcePatterns,
                                 Path initScript,
                                 String testPattern) {
    }

    private record Counter(int covered, int missed) {
    }

    private String extractMethodName(String signature) {
        if (signature == null || signature.isBlank()) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").matcher(signature);
        String methodName = "";
        while (matcher.find()) {
            methodName = matcher.group(1);
        }
        return methodName;
    }
}
