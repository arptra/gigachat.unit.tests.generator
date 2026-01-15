package analyzer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TestReportAnalyzer {
    private static final Path REPORTS_DIR = Paths.get("..", "build", "reports", "tests", "test", "classes");

    public static void main(String[] args) {
        boolean debug = hasDebugFlag(args);
        if (!Files.exists(REPORTS_DIR)) {
            System.out.println("No test reports found at: " + REPORTS_DIR.toAbsolutePath());
            return;
        }

        Map<String, Integer> classCounts = new LinkedHashMap<>();
        Map<String, Integer> methodCounts = new LinkedHashMap<>();
        Map<String, Integer> errorTypeCounts = new LinkedHashMap<>();
        Map<String, Integer> errorMessageCounts = new LinkedHashMap<>();

        List<Path> reportFiles;
        try (Stream<Path> paths = Files.walk(REPORTS_DIR)) {
            reportFiles = paths
                    .filter(path -> Files.isRegularFile(path))
                    .filter(path -> path.getFileName().toString().endsWith(".html"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            System.out.println("Failed to scan reports at: " + REPORTS_DIR.toAbsolutePath());
            return;
        }

        if (reportFiles.isEmpty()) {
            System.out.println("No test reports found at: " + REPORTS_DIR.toAbsolutePath());
            return;
        }

        for (Path reportFile : reportFiles) {
            analyzeReport(reportFile, debug, classCounts, methodCounts, errorTypeCounts, errorMessageCounts);
        }

        printSummary(classCounts, methodCounts, errorTypeCounts, errorMessageCounts);
    }

    private static void analyzeReport(Path reportFile,
                                      boolean debug,
                                      Map<String, Integer> classCounts,
                                      Map<String, Integer> methodCounts,
                                      Map<String, Integer> errorTypeCounts,
                                      Map<String, Integer> errorMessageCounts) {
        String className = stripExtension(reportFile.getFileName().toString());
        String simpleClassName = simpleClassName(className);

        Document document;
        try {
            document = Jsoup.parse(reportFile.toFile(), StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return;
        }

        List<TestCase> testCases = extractTestCases(document, className, debug, reportFile);
        if (testCases.isEmpty() && debug) {
            System.out.println("DEBUG: No test cases extracted from " + reportFile.toAbsolutePath());
        }

        for (TestCase testCase : testCases) {
            incrementCount(classCounts, className);
            incrementCount(methodCounts, simpleClassName + "#" + testCase.methodName());

            if (testCase.status() == Status.FAILED) {
                incrementCount(errorTypeCounts, testCase.errorType());
                incrementCount(errorMessageCounts, testCase.errorMessage());
            }
        }
    }

    private static List<TestCase> extractTestCases(Document document,
                                                   String className,
                                                   boolean debug,
                                                   Path reportFile) {
        Map<String, FailureDetails> failureDetails = extractFailures(document, debug, reportFile);
        List<TestCase> cases = extractFromTestSections(document, className, failureDetails, debug, reportFile);
        if (!cases.isEmpty()) {
            return cases;
        }

        List<TestCase> fallbackCases = extractFromFallbackAnchors(document, className, failureDetails, debug, reportFile);
        if (debug && fallbackCases.isEmpty() && !failureDetails.isEmpty()) {
            System.out.println("DEBUG: Failures section found in " + reportFile.toAbsolutePath()
                    + " but no failed tests extracted.");
        }
        return fallbackCases;
    }

    private static List<TestCase> extractFromTestSections(Document document,
                                                          String className,
                                                          Map<String, FailureDetails> failureDetails,
                                                          boolean debug,
                                                          Path reportFile) {
        List<Element> containers = document.select("div.tab, div.tab-content, div#tab, section")
                .stream()
                .collect(Collectors.toList());
        if (containers.isEmpty()) {
            containers = List.of(document.body());
        }

        int totalFound = 0;
        List<TestCase> results = new java.util.ArrayList<>();

        for (Element container : containers) {
            Elements candidates = container.select("div.test, tr, li");
            for (Element candidate : candidates) {
                if (isDurationCandidate(candidate)) {
                    continue;
                }
                String methodName = extractMethodName(candidate);
                if (methodName == null) {
                    continue;
                }
                Status status = detectStatus(candidate);
                FailureDetails details = failureDetails.get(methodName);
                if (details == null && status == Status.FAILED) {
                    details = extractFailureFromContainer(candidate);
                }
                if (status == Status.UNKNOWN && details == null) {
                    continue;
                }
                TestCase testCase = buildTestCase(className, methodName, status, details);
                if (testCase != null) {
                    results.add(testCase);
                    totalFound++;
                }
            }
        }

        if (debug) {
            System.out.println("DEBUG: Strategy 1 (sections) for " + reportFile.toAbsolutePath()
                    + " found " + totalFound + " test candidates.");
        }

        return results;
    }

    private static Map<String, FailureDetails> extractFailures(Document document,
                                                               boolean debug,
                                                               Path reportFile) {
        Map<String, FailureDetails> failures = new LinkedHashMap<>();
        Elements failureHeaders = document.select("h2, h3, h4");
        for (Element header : failureHeaders) {
            if (!header.text().toLowerCase().contains("failures")) {
                continue;
            }
            Element section = header.parent();
            if (section == null) {
                continue;
            }
            Elements anchors = section.select("a[href^=#]");
            for (Element anchor : anchors) {
                String methodName = normalizeMethodName(anchor.text());
                if (methodName == null) {
                    continue;
                }
                Element target = document.getElementById(anchor.attr("href").substring(1));
                FailureDetails details = extractFailureFromContainer(target != null ? target : anchor.parent());
                failures.putIfAbsent(methodName, details);
            }
        }

        if (debug) {
            System.out.println("DEBUG: Strategy 2 (failures) for " + reportFile.toAbsolutePath()
                    + " found " + failures.size() + " failed tests.");
        }
        return failures;
    }

    private static List<TestCase> extractFromFallbackAnchors(Document document,
                                                             String className,
                                                             Map<String, FailureDetails> failureDetails,
                                                             boolean debug,
                                                             Path reportFile) {
        Elements anchors = document.select("[id], [name]");
        int totalFound = 0;
        List<TestCase> results = new java.util.ArrayList<>();

        for (Element anchor : anchors) {
            String methodName = normalizeMethodName(anchor.id());
            if (methodName == null) {
                methodName = normalizeMethodName(anchor.attr("name"));
            }
            if (methodName == null) {
                continue;
            }
            FailureDetails details = failureDetails.get(methodName);
            if (details == null) {
                details = extractFailureFromContainer(anchor);
            }
            Status status = details != null ? Status.FAILED : Status.UNKNOWN;
            if (status == Status.UNKNOWN && details == null) {
                continue;
            }
            TestCase testCase = buildTestCase(className, methodName, status, details);
            if (testCase != null) {
                results.add(testCase);
                totalFound++;
            }
        }

        if (debug) {
            System.out.println("DEBUG: Strategy 3 (fallback anchors) for " + reportFile.toAbsolutePath()
                    + " found " + totalFound + " test candidates.");
        }

        return results;
    }

    private static String extractMethodName(Element candidate) {
        if (candidate == null) {
            return null;
        }
        String direct = normalizeMethodName(candidate.selectFirst("a, span, td, li, h3") != null
                ? candidate.selectFirst("a, span, td, li, h3").text()
                : null);
        if (direct != null) {
            return direct;
        }
        return normalizeMethodName(candidate.text());
    }

    private static Status detectStatus(Element element) {
        if (element == null) {
            return Status.UNKNOWN;
        }
        String classText = element.className().toLowerCase();
        String text = element.text().toLowerCase();
        if (classText.contains("failed") || text.contains("failed")) {
            return Status.FAILED;
        }
        if (classText.contains("skipped") || text.contains("skipped")) {
            return Status.SKIPPED;
        }
        if (classText.contains("success") || classText.contains("passed") || text.contains("passed")) {
            return Status.PASSED;
        }
        return Status.UNKNOWN;
    }

    private static FailureDetails extractFailureFromContainer(Element container) {
        if (container == null) {
            return null;
        }
        Element stacktrace = container.selectFirst(".stacktrace pre");
        if (stacktrace == null) {
            stacktrace = container.selectFirst("pre");
        }
        if (stacktrace == null) {
            Element error = container.selectFirst(".error");
            if (error != null) {
                return parseErrorLine(error.text());
            }
            return null;
        }

        String firstLine = stacktrace.text().stripLeading();
        int newLineIndex = firstLine.indexOf('\n');
        if (newLineIndex != -1) {
            firstLine = firstLine.substring(0, newLineIndex).trim();
        }

        return parseErrorLine(firstLine);
    }

    private static FailureDetails parseErrorLine(String line) {
        if (line == null || line.isBlank()) {
            return new FailureDetails("UnknownError", "Unknown error");
        }

        String trimmed = line.trim();
        String type;
        String message = "No message";
        int separatorIndex = trimmed.indexOf(':');
        if (separatorIndex == -1) {
            type = extractExceptionType(trimmed);
            return new FailureDetails(type, message);
        }

        type = extractExceptionType(trimmed.substring(0, separatorIndex).trim());
        message = trimmed.substring(separatorIndex + 1).trim();
        if (message.isEmpty()) {
            message = "No message";
        }
        return new FailureDetails(type, message);
    }

    private static TestCase buildTestCase(String className,
                                          String methodName,
                                          Status status,
                                          FailureDetails details) {
        String normalizedMethod = normalizeMethodName(methodName);
        if (normalizedMethod == null) {
            return null;
        }
        FailureDetails safeDetails = details;
        if (status == Status.UNKNOWN && safeDetails == null) {
            return null;
        }
        if (status != Status.FAILED) {
            safeDetails = new FailureDetails("UnknownError", "Unknown error");
        }
        return new TestCase(className, normalizedMethod, status, safeDetails.type(), safeDetails.message());
    }

    private static String normalizeMethodName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String lower = trimmed.toLowerCase();
        if (lower.contains("duration") || lower.contains("execution time") || lower.contains("total time")) {
            return null;
        }
        if (trimmed.endsWith("()")) {
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        }
        return trimmed.replaceAll("\\s+", " ");
    }

    private static boolean isDurationCandidate(Element candidate) {
        if (candidate == null) {
            return false;
        }
        String text = candidate.text().toLowerCase();
        return text.contains("duration")
                || text.contains("execution time")
                || text.contains("total time")
                || text.contains("total duration")
                || text.contains("test duration");
    }

    private static String extractExceptionType(String value) {
        if (value == null || value.isBlank()) {
            return "UnknownError";
        }
        String trimmed = value.trim();
        String type = trimmed;
        int spaceIndex = trimmed.indexOf(' ');
        if (spaceIndex != -1) {
            type = trimmed.substring(0, spaceIndex);
        }
        if (!type.contains(".")) {
            return type;
        }
        return type;
    }

    private static boolean hasDebugFlag(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if ("--debug".equalsIgnoreCase(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void printSummary(Map<String, Integer> classCounts,
                                     Map<String, Integer> methodCounts,
                                     Map<String, Integer> errorTypeCounts,
                                     Map<String, Integer> errorMessageCounts) {
        System.out.println("===== TEST REPORT SUMMARY =====");
        System.out.println();
        System.out.println("Top test classes:");
        printSection(classCounts);
        System.out.println();
        System.out.println("Top test methods:");
        printSection(methodCounts);
        System.out.println();
        System.out.println("Top error types:");
        printSection(errorTypeCounts);
        System.out.println();
        System.out.println("Top error messages:");
        printSection(errorMessageCounts);
    }

    private static void printSection(Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            System.out.println("  (no data)");
            return;
        }

        counts.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry<String, Integer>::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> System.out.printf("%4d  %s%n", entry.getValue(), entry.getKey()));
    }

    private static void incrementCount(Map<String, Integer> map, String key) {
        map.merge(key, 1, Integer::sum);
    }

    private static String stripExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index == -1) {
            return fileName;
        }
        return fileName.substring(0, index);
    }

    private static String simpleClassName(String className) {
        int index = className.lastIndexOf('.');
        if (index == -1) {
            return className;
        }
        return className.substring(index + 1);
    }

    private record FailureDetails(String type, String message) {
        FailureDetails {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(message, "message");
        }
    }

    private record TestCase(String className, String methodName, Status status, String errorType, String errorMessage) {
        TestCase {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(methodName, "methodName");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(errorType, "errorType");
            Objects.requireNonNull(errorMessage, "errorMessage");
        }
    }

    private enum Status {
        PASSED,
        FAILED,
        SKIPPED,
        UNKNOWN
    }
}
