package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Owns read-only tool actions so source inspection and symbol discovery stay separate from
 * mutation/rerun routing.
 */
public class ToolContextActionExecutor {

    private final SourceFileEditor sourceFileEditor;
    private final SymbolLookupService symbolLookupService;
    private final Path projectRoot;

    public ToolContextActionExecutor(SourceFileEditor sourceFileEditor,
                                     SymbolLookupService symbolLookupService,
                                     Path projectRoot) {
        this.sourceFileEditor = Objects.requireNonNull(sourceFileEditor, "sourceFileEditor");
        this.symbolLookupService = Objects.requireNonNull(symbolLookupService, "symbolLookupService");
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
    }

    public ActionExecutionResult handleShowFile(Map<String, Object> args) {
        String pathValue = requireString(args, "path");
        if (pathValue == null) {
            return ActionExecutionResult.error("Missing required argument: path");
        }
        Path path = resolve(pathValue);
        String content = sourceFileEditor.readFile(path);
        Map<String, Object> entry = new HashMap<>();
        entry.put("path", path.toString());
        entry.put("content", content);
        return listPayload("fileContents", entry);
    }

    public ActionExecutionResult handleShowImports(Map<String, Object> args) {
        String pathValue = requireString(args, "path");
        if (pathValue == null) {
            return ActionExecutionResult.error("Missing required argument: path");
        }
        Path path = resolve(pathValue);
        List<String> imports = sourceFileEditor.readImports(path);
        Map<String, Object> entry = new HashMap<>();
        entry.put("path", path.toString());
        entry.put("imports", imports.stream()
                .map(value -> value.endsWith(";") ? value : value + ";")
                .collect(Collectors.toList()));
        return listPayload("imports", entry);
    }

    public ActionExecutionResult handleSearchSymbol(Map<String, Object> args) {
        String symbol = requireString(args, "symbol");
        if (symbol == null || symbol.isBlank()) {
            return ActionExecutionResult.error("Missing required argument: symbol");
        }
        Map<String, Object> payload = new HashMap<>();
        List<String> projectCandidates = symbolLookupService.lookupProjectCandidates(symbol);
        List<String> classpathCandidates = symbolLookupService.lookupClasspathCandidates(symbol);
        List<String> candidates = !projectCandidates.isEmpty() ? projectCandidates : classpathCandidates;
        String status = "NOT_FOUND";
        String source = "PROJECT_SOURCE";
        if (!candidates.isEmpty()) {
            status = candidates.size() == 1 ? "FOUND_ONE" : "FOUND_MANY";
            if (projectCandidates.isEmpty() && !classpathCandidates.isEmpty()) {
                source = "CLASSPATH";
            }
        }

        payload.put("searchStatus", status);
        payload.put("symbol", symbol);
        payload.put("candidates", candidates);
        payload.put("source", status.equals("NOT_FOUND") ? "" : source);
        return new ActionExecutionResult(Map.of("symbolSearchResults", List.of(payload)));
    }

    public ActionExecutionResult handleReadClass(Map<String, Object> args) {
        String className = requireString(args, "className");
        if (className == null) {
            return ActionExecutionResult.error("Missing required argument: className");
        }
        Path path = resolveClassToPath(className);
        if (path == null || !Files.exists(path)) {
            return ActionExecutionResult.error("Class not found in sources: " + className);
        }
        String content = sourceFileEditor.readFile(path);
        Map<String, String> cacheUpdate = Map.of(path.toString(), content);
        return new ActionExecutionResult(Map.of("contextCacheUpdates", cacheUpdate));
    }

    public ActionExecutionResult handleReadMethod(Map<String, Object> args) {
        String className = requireString(args, "className");
        String methodName = requireString(args, "methodName");
        if (className == null || methodName == null) {
            return ActionExecutionResult.error("Missing required arguments: className and methodName");
        }
        Path path = resolveClassToPath(className);
        if (path == null || !Files.exists(path)) {
            return ActionExecutionResult.error("Class not found in sources: " + className);
        }
        String content = sourceFileEditor.readFile(path);
        String snippet = extractMethod(content, methodName);
        Map<String, String> cacheUpdate = Map.of(path + "#" + methodName, snippet);
        return new ActionExecutionResult(Map.of("contextCacheUpdates", cacheUpdate));
    }

    public ActionExecutionResult handleListMethods(Map<String, Object> args) {
        String className = requireString(args, "className");
        if (className == null) {
            return ActionExecutionResult.error("Missing required argument: className");
        }
        Path path = resolveClassToPath(className);
        if (path == null || !Files.exists(path)) {
            return ActionExecutionResult.error("Class not found in sources: " + className);
        }
        String content = sourceFileEditor.readFile(path);
        List<String> methods = content.lines()
                .filter(line -> line.contains("(") && line.contains(")") && line.contains("{"))
                .map(String::trim)
                .toList();
        return new ActionExecutionResult(Map.of("contextCacheUpdates", Map.of(path + "#methods", String.join("\n", methods))));
    }

    private ActionExecutionResult listPayload(String key, Object entry) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(key, List.of(entry));
        return new ActionExecutionResult(payload);
    }

    private Path resolve(String pathValue) {
        Path path = Path.of(pathValue);
        if (!path.isAbsolute()) {
            path = projectRoot.resolve(pathValue);
        }
        return path.normalize().toAbsolutePath();
    }

    private Path resolveClassToPath(String className) {
        for (String candidateClassName : resolveProjectClassCandidates(className)) {
            String relative = candidateClassName.replace('.', '/') + ".java";
            for (String sourceRoot : List.of("src/main/java", "src/test/java")) {
                Path candidate = projectRoot.resolve(sourceRoot).resolve(relative).normalize().toAbsolutePath();
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private List<String> resolveProjectClassCandidates(String className) {
        if (className == null || className.isBlank()) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        if (className.contains(".")) {
            candidates.add(className);
        }
        candidates.addAll(symbolLookupService.lookupProjectCandidates(className));
        if (!className.contains(".")) {
            candidates.add(className);
        }
        return candidates.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private String requireString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : value.toString();
    }

    private String extractMethod(String source, String methodName) {
        StringBuilder builder = new StringBuilder();
        boolean started = false;
        int braceDepth = 0;
        for (String line : source.split("\\r?\\n")) {
            if (!started && line.contains(methodName + "(")) {
                started = true;
            }
            if (started) {
                builder.append(line).append("\n");
                braceDepth += count(line, '{');
                braceDepth -= count(line, '}');
                if (braceDepth <= 0 && line.contains("}")) {
                    break;
                }
            }
        }
        return builder.toString().trim();
    }

    private int count(String line, char symbol) {
        int total = 0;
        for (int index = 0; index < line.length(); index++) {
            if (line.charAt(index) == symbol) {
                total++;
            }
        }
        return total;
    }
}
