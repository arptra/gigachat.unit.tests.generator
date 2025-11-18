package com.gigachat.unit.tests.generator.report.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Gradle HTML test reports and extracts detailed failures (class, method, message, stacktrace).
 */
public class ExecutionReportParser {

    private static final Pattern LINK_PATTERN = Pattern.compile("href=\\\"classes/([^\\\"#]+)#([^\\\"]+)\\\"");
    private static final Pattern MESSAGE_PATTERN = Pattern.compile("<div\\s+class=\\\"message\\\">(.*?)</div>", Pattern.DOTALL);
    private static final Pattern STACKTRACE_PATTERN = Pattern.compile("<pre[^>]*>(.*?)</pre>", Pattern.DOTALL);

    public List<TestReportFailure> parse(Path reportPath) throws IOException {
        Path indexPath = normalizeIndexPath(reportPath);
        if (indexPath == null || !Files.exists(indexPath)) {
            return List.of();
        }

        String indexContent = Files.readString(indexPath);
        Path reportRoot = indexPath.getParent();
        List<TestReportFailure> failures = new ArrayList<>();
        Matcher matcher = LINK_PATTERN.matcher(indexContent);
        while (matcher.find()) {
            String classRelPath = matcher.group(1);
            String methodName = normalizeMethodName(matcher.group(2));
            Path classReport = reportRoot.resolve("classes").resolve(classRelPath);
            failures.add(parseClassFailure(classReport, methodName));
        }
        return List.copyOf(failures);
    }

    private String normalizeMethodName(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.endsWith("()")) {
            return trimmed.substring(0, trimmed.length() - 2);
        }
        return trimmed;
    }

    private Path normalizeIndexPath(Path reportPath) {
        if (reportPath == null) {
            return null;
        }
        if (Files.isDirectory(reportPath)) {
            return reportPath.resolve("index.html");
        }
        return reportPath;
    }

    private TestReportFailure parseClassFailure(Path classReport, String methodName) throws IOException {
        String className = deriveClassName(classReport);
        if (classReport == null || !Files.exists(classReport)) {
            return new TestReportFailure(className, methodName, "", List.of());
        }
        String content = Files.readString(classReport);
        String section = extractMethodSection(content, methodName);
        String message = extractMessage(section);
        List<String> stackTrace = extractStackTrace(section);
        return new TestReportFailure(className, methodName, message, stackTrace);
    }

    private String deriveClassName(Path classReport) {
        if (classReport == null) {
            return "";
        }
        String name = classReport.getFileName().toString();
        if (name.endsWith(".html")) {
            return name.substring(0, name.length() - 5);
        }
        return name;
    }

    private String extractMethodSection(String content, String methodName) {
        String escaped = Pattern.quote(methodName);
        Pattern anchorPattern = Pattern.compile("(?s)(<a\\s+(?:name|id)\\s*=\\s*\"" + escaped + "\".*?)(?=<a\\s+(?:name|id)=|$)");
        Matcher anchorMatcher = anchorPattern.matcher(content);
        if (anchorMatcher.find()) {
            return anchorMatcher.group(1);
        }
        Pattern headerPattern = Pattern.compile("(?s)(<h3[^>]*>\\s*" + escaped + "\\s*</h3>.*?)(?=<h3|$)");
        Matcher headerMatcher = headerPattern.matcher(content);
        if (headerMatcher.find()) {
            return headerMatcher.group(1);
        }
        return content;
    }

    private String extractMessage(String section) {
        Matcher matcher = MESSAGE_PATTERN.matcher(section);
        if (matcher.find()) {
            return cleanHtml(matcher.group(1), false);
        }
        return "";
    }

    private List<String> extractStackTrace(String section) {
        Matcher matcher = STACKTRACE_PATTERN.matcher(section);
        if (!matcher.find()) {
            return List.of();
        }
        String traceHtml = matcher.group(1);
        String normalized = cleanHtml(traceHtml, true).replace("\\u00a0", " ");
        String[] lines = normalized.split("\\R");
        List<String> cleaned = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                cleaned.add(trimmed);
            }
        }
        return Collections.unmodifiableList(cleaned);
    }

    private String cleanHtml(String html, boolean preserveNewlines) {
        String withBreaks = html.replaceAll("(?i)<br\\s*/?>", "\n");
        String withoutTags = withBreaks.replaceAll("<[^>]+>", " ");
        String unescaped = unescape(withoutTags);
        if (preserveNewlines) {
            String normalized = unescaped.replaceAll("\\r", "");
            normalized = normalized.replaceAll("[\\t ]+", " ");
            return normalized.replaceAll(" *\\n *", "\n").trim();
        }
        return unescaped.replaceAll("\\s+", " ").trim();
    }

    private String unescape(String text) {
        return text
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }
}
