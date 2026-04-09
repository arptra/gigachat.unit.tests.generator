package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.cleaner.parser.ExecutionFailureParseResult;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.report.parser.TestReportFailure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds an execution-failure payload tailored for the reasoning loop so the LLM can inspect
 * runtime failures, mock misconfigurations and the related project classes before proposing a fix.
 */
public class ExecutionFailureContextCollector {

    private static final Pattern STACKTRACE_CLASS_PATTERN = Pattern.compile("\\bat\\s+([\\w$]+(?:\\.[\\w$]+)+)\\.[\\w$<>]+\\(");
    private static final Pattern FQCN_PATTERN = Pattern.compile("\\b([a-zA-Z_]\\w*(?:\\.[a-zA-Z_]\\w*){2,})\\b");
    private static final int MAX_RELATED_CLASSES = 4;
    private static final int MAX_SOURCE_CHARS = 2400;

    public ActionExecutionResult collect(Path projectRoot,
                                         TestClassInfo classInfo,
                                         TestMethodInfo methodInfo,
                                         Analyze.AnalysisSummary analysisSummary,
                                         ExecuteResult executeResult,
                                         ExecutionFailureParseResult failureParseResult,
                                         List<TestReportFailure> reportFailures) {
        Objects.requireNonNull(projectRoot, "projectRoot");

        LinkedHashMap<String, Object> information = new LinkedHashMap<>();
        information.put("failureStage", "execute");
        information.put("executionSummary", buildExecutionSummary(classInfo, methodInfo, executeResult, failureParseResult, reportFailures));

        List<Map<String, Object>> failures = buildFailures(reportFailures, failureParseResult, executeResult);
        if (!failures.isEmpty()) {
            information.put("executionFailures", failures);
        }

        Map<String, Object> mockContext = buildMockContext(analysisSummary, executeResult, reportFailures);
        if (!mockContext.isEmpty()) {
            information.put("mockContext", mockContext);
        }

        List<String> relatedClasses = collectRelatedClasses(projectRoot, classInfo, analysisSummary, executeResult, reportFailures);
        if (!relatedClasses.isEmpty()) {
            information.put("relatedClassesToInspect", relatedClasses);
        }

        List<Map<String, Object>> relatedClassSources = buildRelatedClassSources(projectRoot, relatedClasses);
        if (!relatedClassSources.isEmpty()) {
            information.put("relatedClassSources", relatedClassSources);
            information.put("contextCacheUpdates", toContextCacheUpdates(relatedClassSources));
        }

        return new ActionExecutionResult(information);
    }

    private Map<String, Object> buildExecutionSummary(TestClassInfo classInfo,
                                                      TestMethodInfo methodInfo,
                                                      ExecuteResult executeResult,
                                                      ExecutionFailureParseResult failureParseResult,
                                                      List<TestReportFailure> reportFailures) {
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        if (classInfo != null) {
            summary.put("testedClass", classInfo.getClassName());
            summary.put("generatedTestClass", classInfo.getTestClassName());
            summary.put("testFile", classInfo.getTargetPath().toAbsolutePath().normalize().toString());
        }
        if (methodInfo != null) {
            summary.put("testedMethodSignature", methodInfo.getSignature());
        }
        if (executeResult != null) {
            summary.put("failedTests", executeResult.failedTests());
            summary.put("stdout", executeResult.stdout());
            summary.put("stderr", executeResult.stderr());
        }
        if (failureParseResult != null && !failureParseResult.failures().isEmpty()) {
            summary.put("parsedFailures", failureParseResult.failures().stream()
                    .map(failure -> failure.className() + "." + failure.methodName())
                    .toList());
        }
        if (reportFailures != null && !reportFailures.isEmpty()) {
            TestReportFailure first = reportFailures.get(0);
            if (!first.message().isBlank()) {
                summary.put("primaryFailureMessage", first.message());
            }
            if (!first.stackTrace().isEmpty()) {
                summary.put("primaryStackTrace", first.stackTrace());
            }
        }
        return summary;
    }

    private List<Map<String, Object>> buildFailures(List<TestReportFailure> reportFailures,
                                                    ExecutionFailureParseResult failureParseResult,
                                                    ExecuteResult executeResult) {
        List<Map<String, Object>> failures = new ArrayList<>();
        if (reportFailures != null && !reportFailures.isEmpty()) {
            for (TestReportFailure reportFailure : reportFailures) {
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("className", reportFailure.className());
                item.put("methodName", reportFailure.methodName());
                item.put("message", reportFailure.message());
                item.put("stackTrace", reportFailure.stackTrace());
                String exceptionType = detectExceptionType(reportFailure.message(), reportFailure.stackTrace());
                if (!exceptionType.isBlank()) {
                    item.put("exceptionType", exceptionType);
                }
                failures.add(item);
            }
            return failures;
        }
        if (failureParseResult != null && !failureParseResult.failures().isEmpty()) {
            failureParseResult.failures().forEach(failure -> {
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("className", failure.className());
                item.put("methodName", failure.methodName());
                failures.add(item);
            });
            return failures;
        }
        if (executeResult != null && !executeResult.failedTests().isEmpty()) {
            executeResult.failedTests().forEach(failure -> failures.add(Map.of("name", failure)));
        }
        return failures;
    }

    private Map<String, Object> buildMockContext(Analyze.AnalysisSummary analysisSummary,
                                                 ExecuteResult executeResult,
                                                 List<TestReportFailure> reportFailures) {
        if (analysisSummary == null) {
            return Map.of();
        }
        MockPlan mockPlan = analysisSummary.mockPlan();
        if (mockPlan == null) {
            return Map.of();
        }

        String combinedText = buildCombinedFailureText(executeResult, reportFailures).toLowerCase(Locale.ROOT);
        LinkedHashMap<String, Object> mockContext = new LinkedHashMap<>();
        mockContext.put("strategy", mockPlan.strategy() == null ? MockStrategy.NONE.name() : mockPlan.strategy().name());
        mockContext.put("shouldMock", mockPlan.shouldMock());
        mockContext.put("shouldNotMock", mockPlan.shouldNotMock());

        List<String> suspectedProblems = new ArrayList<>();
        if (combinedText.contains("notamockexception")) {
            suspectedProblems.add("A real object is being stubbed or verified as a mock.");
        }
        if (combinedText.contains("wanted but not invoked")) {
            suspectedProblems.add("Mockito verification expects an interaction that never happened.");
        }
        if (combinedText.contains("potentialstubbingproblem") || combinedText.contains("unnecessarystubbingexception")) {
            suspectedProblems.add("Mockito stubbing does not match the real invocation flow.");
        }
        if (combinedText.contains("missingmethodinvocationexception")) {
            suspectedProblems.add("when()/doReturn() is applied to something that Mockito cannot intercept.");
        }
        if (combinedText.contains("nullpointerexception")) {
            suspectedProblems.add("A collaborator or prepared fixture may be null during test execution.");
        }
        if (!suspectedProblems.isEmpty()) {
            mockContext.put("suspectedProblems", suspectedProblems);
        }

        List<String> reasoningHints = new ArrayList<>();
        reasoningHints.add("Mock only external collaborators listed in shouldMock.");
        reasoningHints.add("Keep DTOs, entities, value objects, collections, and internal state real when they appear in shouldNotMock.");
        if (!suspectedProblems.isEmpty()) {
            reasoningHints.add("If Mockito is involved, inspect the failing collaborator class and constructor/setup path before patching assertions.");
        }
        List<String> mentionedCollaborators = mockPlan.shouldMock().stream()
                .filter(candidate -> combinedText.contains(candidate.toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        if (!mentionedCollaborators.isEmpty()) {
            mockContext.put("mentionedCollaborators", mentionedCollaborators);
            reasoningHints.add("Prioritise collaborators mentioned in the failure output: " + mentionedCollaborators);
        }
        mockContext.put("reasoningHints", reasoningHints);
        return mockContext;
    }

    private List<String> collectRelatedClasses(Path projectRoot,
                                               TestClassInfo classInfo,
                                               Analyze.AnalysisSummary analysisSummary,
                                               ExecuteResult executeResult,
                                               List<TestReportFailure> reportFailures) {
        LinkedHashSet<String> relatedClasses = new LinkedHashSet<>();
        if (classInfo != null && classInfo.getClassName() != null && !classInfo.getClassName().isBlank()) {
            relatedClasses.add(classInfo.getClassName());
        }

        String combinedText = buildCombinedFailureText(executeResult, reportFailures);
        extractFqcnsFromStackTrace(combinedText).stream()
                .map(this::normalizeClassName)
                .filter(name -> resolveSourceFile(projectRoot, name) != null)
                .forEach(relatedClasses::add);

        if (analysisSummary != null && analysisSummary.mockPlan() != null) {
            addPlanCandidates(projectRoot, analysisSummary.mockPlan().shouldMock(), combinedText, relatedClasses);
            addPlanCandidates(projectRoot, analysisSummary.mockPlan().shouldNotMock(), combinedText, relatedClasses);
        }

        return relatedClasses.stream().limit(MAX_RELATED_CLASSES).toList();
    }

    private void addPlanCandidates(Path projectRoot,
                                   List<String> candidates,
                                   String combinedText,
                                   Set<String> relatedClasses) {
        if (candidates == null) {
            return;
        }
        String lowered = combinedText.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String simpleName = simpleName(candidate);
            if (!lowered.contains(simpleName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String resolved = resolveCandidateClass(projectRoot, candidate);
            if (resolved != null) {
                relatedClasses.add(resolved);
            }
        }
    }

    private List<Map<String, Object>> buildRelatedClassSources(Path projectRoot, List<String> relatedClasses) {
        List<Map<String, Object>> relatedSources = new ArrayList<>();
        if (relatedClasses == null) {
            return relatedSources;
        }
        for (String className : relatedClasses) {
            Path sourceFile = resolveSourceFile(projectRoot, className);
            if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
                continue;
            }
            try {
                String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("className", className);
                item.put("path", sourceFile.toAbsolutePath().normalize().toString());
                item.put("sourceExcerpt", truncate(source));
                relatedSources.add(item);
            } catch (IOException ignored) {
                // best effort only
            }
        }
        return relatedSources;
    }

    private Map<String, String> toContextCacheUpdates(List<Map<String, Object>> relatedClassSources) {
        LinkedHashMap<String, String> cache = new LinkedHashMap<>();
        for (Map<String, Object> source : relatedClassSources) {
            Object path = source.get("path");
            Object excerpt = source.get("sourceExcerpt");
            if (path != null && excerpt != null) {
                cache.put(path.toString(), excerpt.toString());
            }
        }
        return cache;
    }

    private Set<String> extractFqcnsFromStackTrace(String text) {
        LinkedHashSet<String> classes = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return classes;
        }
        Matcher stacktraceMatcher = STACKTRACE_CLASS_PATTERN.matcher(text);
        while (stacktraceMatcher.find()) {
            classes.add(stacktraceMatcher.group(1));
        }
        Matcher fqcnMatcher = FQCN_PATTERN.matcher(text);
        while (fqcnMatcher.find()) {
            classes.add(fqcnMatcher.group(1));
        }
        return classes;
    }

    private String buildCombinedFailureText(ExecuteResult executeResult, List<TestReportFailure> reportFailures) {
        StringBuilder builder = new StringBuilder();
        if (executeResult != null) {
            builder.append(executeResult.stdout()).append(System.lineSeparator());
            builder.append(executeResult.stderr()).append(System.lineSeparator());
            executeResult.failedTests().forEach(item -> builder.append(item).append(System.lineSeparator()));
        }
        if (reportFailures != null) {
            reportFailures.forEach(failure -> {
                builder.append(failure.className()).append('.').append(failure.methodName()).append(System.lineSeparator());
                builder.append(failure.message()).append(System.lineSeparator());
                failure.stackTrace().forEach(line -> builder.append(line).append(System.lineSeparator()));
            });
        }
        return builder.toString();
    }

    private String detectExceptionType(String message, List<String> stackTrace) {
        if (message != null && message.contains(":")) {
            String prefix = message.substring(0, message.indexOf(':')).trim();
            if (prefix.endsWith("Exception") || prefix.endsWith("Error")) {
                return prefix;
            }
        }
        if (stackTrace != null) {
            for (String line : stackTrace) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.endsWith("Exception") || trimmed.endsWith("Error")) {
                    return trimmed;
                }
                int separator = trimmed.indexOf(':');
                if (separator > 0) {
                    String prefix = trimmed.substring(0, separator).trim();
                    if (prefix.endsWith("Exception") || prefix.endsWith("Error")) {
                        return prefix;
                    }
                }
            }
        }
        return "";
    }

    private String resolveCandidateClass(Path projectRoot, String candidate) {
        String normalized = normalizeClassName(candidate);
        if (resolveSourceFile(projectRoot, normalized) != null) {
            return normalized;
        }
        String simpleName = simpleName(normalized);
        for (Path root : List.of(projectRoot.resolve("src/main/java"), projectRoot.resolve("src/test/java"))) {
            if (!Files.exists(root)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                    if (!path.getFileName().toString().equals(simpleName + ".java")) {
                        continue;
                    }
                    String relative = root.relativize(path).toString().replace('\\', '/').replace(".java", "");
                    return relative.replace('/', '.');
                }
            } catch (IOException ignored) {
                // best effort only
            }
        }
        return null;
    }

    private Path resolveSourceFile(Path projectRoot, String className) {
        if (className == null || className.isBlank()) {
            return null;
        }
        String relative = normalizeClassName(className).replace('.', '/') + ".java";
        for (Path root : List.of(projectRoot.resolve("src/main/java"), projectRoot.resolve("src/test/java"))) {
            Path candidate = root.resolve(relative).normalize().toAbsolutePath();
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String normalizeClassName(String className) {
        if (className == null) {
            return "";
        }
        String normalized = className.trim();
        int dollarIndex = normalized.indexOf('$');
        if (dollarIndex >= 0) {
            normalized = normalized.substring(0, dollarIndex);
        }
        return normalized;
    }

    private String simpleName(String className) {
        String normalized = normalizeClassName(className);
        int lastDot = normalized.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < normalized.length()) {
            return normalized.substring(lastDot + 1);
        }
        return normalized;
    }

    private String truncate(String source) {
        if (source == null || source.length() <= MAX_SOURCE_CHARS) {
            return source == null ? "" : source;
        }
        return source.substring(0, MAX_SOURCE_CHARS) + System.lineSeparator() + "// ... truncated ...";
    }
}
