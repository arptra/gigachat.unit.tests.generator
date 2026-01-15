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
            analyzeReport(reportFile, classCounts, methodCounts, errorTypeCounts, errorMessageCounts);
        }

        printSummary(classCounts, methodCounts, errorTypeCounts, errorMessageCounts);
    }

    private static void analyzeReport(Path reportFile,
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

        Elements rows = document.select("table#tests tbody tr, table.test tbody tr");
        if (rows.isEmpty()) {
            rows = document.select("table#tests tr, table.test tr");
        }

        for (Element row : rows) {
            Elements cells = row.select("td");
            if (cells.isEmpty()) {
                continue;
            }

            String methodName = cells.get(0).text().trim();
            if (methodName.isEmpty()) {
                continue;
            }

            String status = cells.get(cells.size() - 1).text().trim().toLowerCase();

            incrementCount(classCounts, className);
            incrementCount(methodCounts, simpleClassName + "#" + methodName);

            if (status.contains("failed")) {
                ErrorDetails details = extractErrorDetails(document, methodName);
                incrementCount(errorTypeCounts, details.type());
                incrementCount(errorMessageCounts, details.message());
            }
        }
    }

    private static ErrorDetails extractErrorDetails(Document document, String methodName) {
        Element detail = document.getElementById(methodName);
        if (detail == null) {
            for (Element testDiv : document.select("div.test")) {
                Element title = testDiv.selectFirst("h3");
                if (title != null && title.text().contains(methodName)) {
                    detail = testDiv;
                    break;
                }
            }
        }

        if (detail == null) {
            return new ErrorDetails("UnknownError", "Unknown error");
        }

        Element stacktrace = detail.selectFirst(".stacktrace pre");
        if (stacktrace == null) {
            stacktrace = detail.selectFirst("pre");
        }
        if (stacktrace == null) {
            Element error = detail.selectFirst(".error");
            if (error != null) {
                return parseErrorLine(error.text());
            }
            return new ErrorDetails("UnknownError", "Unknown error");
        }

        String firstLine = stacktrace.text().stripLeading();
        int newLineIndex = firstLine.indexOf('\n');
        if (newLineIndex != -1) {
            firstLine = firstLine.substring(0, newLineIndex).trim();
        }

        return parseErrorLine(firstLine);
    }

    private static ErrorDetails parseErrorLine(String line) {
        if (line == null || line.isBlank()) {
            return new ErrorDetails("UnknownError", "Unknown error");
        }

        String trimmed = line.trim();
        int separatorIndex = trimmed.indexOf(':');
        if (separatorIndex == -1) {
            return new ErrorDetails(trimmed, "No message");
        }

        String type = trimmed.substring(0, separatorIndex).trim();
        String message = trimmed.substring(separatorIndex + 1).trim();
        if (message.isEmpty()) {
            message = "No message";
        }
        return new ErrorDetails(type, message);
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

    private record ErrorDetails(String type, String message) {
        ErrorDetails {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(message, "message");
        }
    }
}
