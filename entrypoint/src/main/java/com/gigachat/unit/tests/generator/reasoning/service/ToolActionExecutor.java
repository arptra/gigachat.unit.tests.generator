package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfoBuilder;
import com.gigachat.unit.tests.generator.reasoning.model.ToolAction;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionStep;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;
import com.gigachat.unit.tests.generator.reasoning.service.action.AddDependencyAction;
import com.gigachat.unit.tests.generator.reasoning.service.action.AddImportAction;
import com.gigachat.unit.tests.generator.reasoning.service.action.ApplyPatchAction;
import com.gigachat.unit.tests.generator.reasoning.service.action.ProjectModificationAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Executes tool actions returned by the reasoning model. Information-gathering steps append data to
 * the execution log while project modifications are applied silently and recorded only as performed
 * actions. No human-facing logs are emitted; all outputs are captured for the next reasoning prompt.
 */
public class ToolActionExecutor {

    private final BuildFileEditor buildFileEditor;
    private final SourceFileEditor sourceFileEditor;
    private final CompilerInvoker compilerInvoker;
    private final ExecutionInvoker executionInvoker;
    private final Path projectRoot;
    private final Path moduleRoot;
    private final Path testFile;
    private final String testFileFqcn;
    private final String methodName;
    private final List<Path> sourceRoots;
    private final List<Path> testRoots;
    private Map<String, List<String>> projectSymbolIndex;
    private Map<String, List<String>> classpathSymbolIndex;

    public ToolActionExecutor(BuildFileEditor buildFileEditor,
                              SourceFileEditor sourceFileEditor,
                              CompilerInvoker compilerInvoker,
                              ExecutionInvoker executionInvoker,
                              Path projectRoot,
                              Path testFile,
                              String testFileFqcn,
                              String methodName) {
        this.buildFileEditor = Objects.requireNonNull(buildFileEditor, "buildFileEditor");
        this.sourceFileEditor = Objects.requireNonNull(sourceFileEditor, "sourceFileEditor");
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.executionInvoker = executionInvoker;
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.testFile = Objects.requireNonNull(testFile, "testFile");
        this.testFileFqcn = testFileFqcn;
        this.methodName = methodName;
        this.moduleRoot = detectModuleRoot(this.projectRoot, this.testFile);
        this.sourceRoots = discoverMainSourceRoots();
        this.testRoots = discoverTestSourceRoots();
    }

    public ActionExecutionResult execute(ToolAction action) {
        if (action == null) {
            return ActionExecutionResult.empty();
        }
        List<ToolActionStep> steps = resolveSteps(action);
        ActionExecutionResult cumulative = ActionExecutionResult.empty();
        for (ToolActionStep step : steps) {
            cumulative = cumulative.merge(executeStep(step));
        }
        return cumulative;
    }

    public ActionExecutionResult executeStep(ToolActionStep step) {
        if (step == null || step.getType() == null) {
            return ActionExecutionResult.empty();
        }
        ToolActionType type = step.getType();
        Map<String, Object> args = ArgumentNormalizer.normalize(step.getArguments());
        return switch (type) {
            case SHOW_FILE -> handleShowFile(args);
            case SHOW_IMPORTS -> handleShowImports(args);
            case SEARCH_SYMBOL -> handleSearchSymbol(args);
            case READ_CLASS -> handleReadClass(args);
            case READ_METHOD -> handleReadMethod(args);
            case LIST_METHODS -> handleListMethods(args);
            case RUN_TEST -> handleRunTests();
            case APPLY_PATCH -> applyModification(createApplyPatchAction(args));
            case ADD_IMPORT -> applyModification(createAddImportAction(args));
            case ADD_DEPENDENCY -> applyModification(createAddDependencyAction(args));
            case RECOMPILE -> handleRecompile();
            case MARK_FALSE_DEPENDENCY -> handleMarkFalseDependency(args);
            case STOP -> ActionExecutionResult.empty();
            case COMPOSITE -> ActionExecutionResult.empty();
        };
    }

    private ActionExecutionResult applyModification(ProjectModificationAction action) {
        if (action == null) {
            return ActionExecutionResult.empty();
        }
        Path targetPath = null;
        if (action instanceof ApplyPatchAction applyPatchAction) {
            targetPath = applyPatchAction.getFilePath();
        }
        if (action instanceof AddImportAction addImportAction) {
            targetPath = addImportAction.getFilePath();
        }
        if (targetPath != null && !isTestFile(targetPath)) {
            return new ActionExecutionResult(Map.of("forbiddenActions", List.of(action.describe())), List.of());
        }
        String before = targetPath == null ? null : sourceFileEditor.readFile(targetPath);
        action.apply();
        if (targetPath != null) {
            String after = sourceFileEditor.readFile(targetPath);
            if (Objects.equals(before, after)) {
                return ActionExecutionResult.error("Action had no effect: " + action.describe());
            }
        }
        return new ActionExecutionResult(Map.of(), List.of(action.describe()));
    }

    private ActionExecutionResult handleShowFile(Map<String, Object> args) {
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

    private ActionExecutionResult handleShowImports(Map<String, Object> args) {
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

    private ActionExecutionResult handleSearchSymbol(Map<String, Object> args) {
        String symbol = requireString(args, "symbol");
        if (symbol == null || symbol.isBlank()) {
            return ActionExecutionResult.error("Missing required argument: symbol");
        }
        String simpleName = symbol.contains(".")
                ? symbol.substring(symbol.lastIndexOf('.') + 1)
                : symbol;
        Map<String, Object> payload = new HashMap<>();
        List<String> candidates = new ArrayList<>();
        String status = "NOT_FOUND";
        String source = "PROJECT_SOURCE";

        Map<String, List<String>> projectIndex = buildProjectSymbolIndex();
        List<String> projectMatches = projectIndex.getOrDefault(simpleName, List.of());
        if (!projectMatches.isEmpty()) {
            candidates.addAll(projectMatches);
            status = projectMatches.size() == 1 ? "FOUND_ONE" : "FOUND_MANY";
        } else {
            Map<String, List<String>> cpIndex = buildClasspathSymbolIndex();
            List<String> classpathMatches = cpIndex.getOrDefault(simpleName, List.of());
            if (!classpathMatches.isEmpty()) {
                candidates.addAll(classpathMatches);
                status = classpathMatches.size() == 1 ? "FOUND_ONE" : "FOUND_MANY";
                source = "CLASSPATH";
            }
        }

        payload.put("searchStatus", status);
        payload.put("symbol", symbol);
        payload.put("candidates", candidates);
        payload.put("source", status.equals("NOT_FOUND") ? "" : source);
        return new ActionExecutionResult(Map.of("symbolSearchResults", List.of(payload)));
    }

    private ActionExecutionResult handleReadClass(Map<String, Object> args) {
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

    private ActionExecutionResult handleReadMethod(Map<String, Object> args) {
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

    private ActionExecutionResult handleListMethods(Map<String, Object> args) {
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

    private String extractMethod(String source, String methodName) {
        if (source == null || source.isBlank() || methodName == null || methodName.isBlank()) {
            return "";
        }
        String[] lines = source.split("\\r?\\n");
        StringBuilder builder = new StringBuilder();
        int startIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(methodName + "(")) {
                startIndex = i;
                break;
            }
        }
        if (startIndex < 0) {
            return "";
        }
        int depth = 0;
        boolean opened = false;
        for (int i = startIndex; i < lines.length; i++) {
            String line = lines[i];
            builder.append(line).append("\n");
            for (int j = 0; j < line.length(); j++) {
                char ch = line.charAt(j);
                if (ch == '{') {
                    depth++;
                    opened = true;
                } else if (ch == '}') {
                    depth--;
                }
            }
            if (opened && depth <= 0) {
                break;
            }
        }
        return builder.toString().trim();
    }

    private ActionExecutionResult handleRecompile() {
        CompileResult result = compilerInvoker.compileWithoutCache(projectRoot, testFile, methodName);
        Map<String, Object> detail = new HashMap<>();
        detail.put("success", result.success());
        if (!result.success()) {
            detail.put("errorInfo", CompilationErrorInfoBuilder.from(result, testFile, testFileFqcn));
        }
        return new ActionExecutionResult(Map.of("compilationResult", detail));
    }

    private ActionExecutionResult handleRunTests() {
        Map<String, Object> detail = new HashMap<>();
        if (executionInvoker == null) {
            detail.put("success", false);
            detail.put("stdout", "");
            detail.put("stderr", "Execution invoker is not configured");
            detail.put("stacktrace", "");
        } else {
            ExecuteResult executeResult = executionInvoker.execute(projectRoot, testFile, methodName);
            detail.put("success", executeResult.success());
            detail.put("stdout", executeResult.stdout());
            detail.put("stderr", executeResult.stderr());
            detail.put("stacktrace", executeResult.failedTests().stream().collect(Collectors.joining("\n")));
        }
        return listPayload("testRuns", detail);
    }

    private ActionExecutionResult handleMarkFalseDependency(Map<String, Object> args) {
        String symbol = requireString(args, "symbol");
        if (symbol == null) {
            return ActionExecutionResult.error("Missing required argument: symbol");
        }
        return new ActionExecutionResult(Map.of("knownMissingSymbols", List.of(symbol)));
    }

    private ProjectModificationAction createAddDependencyAction(Map<String, Object> args) {
        String dependencyNotation = requireString(args, "dependency");
        if (dependencyNotation == null || dependencyNotation.isBlank()) {
            return null;
        }
        return new AddDependencyAction(buildFileEditor, dependencyNotation);
    }

    private ProjectModificationAction createApplyPatchAction(Map<String, Object> args) {
        String pathValue = requireString(args, "path");
        String patch = requireString(args, "patch");
        if (patch == null || pathValue == null) {
            return null;
        }
        Path target = resolve(pathValue);
        if (!isTestFile(target)) {
            return null;
        }
        return new ApplyPatchAction(sourceFileEditor, target, patch);
    }

    private ProjectModificationAction createAddImportAction(Map<String, Object> args) {
        String pathValue = requireString(args, "path");
        String importName = requireString(args, "import");
        if (pathValue == null || importName == null) {
            return null;
        }
        Path target = resolve(pathValue);
        if (!isTestFile(target)) {
            return null;
        }
        return new AddImportAction(sourceFileEditor, target, importName);
    }

    private List<ToolActionStep> resolveSteps(ToolAction action) {
        if (action.getType() == ToolActionType.COMPOSITE && action.getSteps() != null) {
            return action.getSteps();
        }
        if (action.getSingleStep() != null) {
            return List.of(action.getSingleStep());
        }
        if (action.getType() != null) {
            return List.of(new ToolActionStep(action.getType(), action.getSingleStep() == null ? Map.of() : action.getSingleStep().getArguments()));
        }
        return List.of();
    }

    private ActionExecutionResult listPayload(String key, Object entry) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(key, List.of(entry));
        return new ActionExecutionResult(payload);
    }

    private Path resolve(String pathValue) {
        Path path = Path.of(pathValue);
        if (path.isAbsolute()) {
            return path.normalize().toAbsolutePath();
        }
        Path moduleRelative = moduleRoot.resolve(pathValue).normalize().toAbsolutePath();
        if (Files.exists(moduleRelative)) {
            return moduleRelative;
        }
        Path projectRelative = projectRoot.resolve(pathValue).normalize().toAbsolutePath();
        if (Files.exists(projectRelative)) {
            return projectRelative;
        }
        Path siblingRelative = testFile.getParent() == null
                ? moduleRelative
                : testFile.getParent().resolve(pathValue).normalize().toAbsolutePath();
        if (Files.exists(siblingRelative)) {
            return siblingRelative;
        }
        return moduleRelative;
    }

    private Path resolveClassToPath(String className) {
        String relative = className.replace('.', '/') + ".java";
        for (Path root : combinedSourceRoots()) {
            Path candidate = root.resolve(relative).normalize().toAbsolutePath();
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String requireString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : value.toString();
    }

    private boolean isTestFile(Path path) {
        if (path == null) {
            return false;
        }
        Path normalized = path.toAbsolutePath().normalize();
        for (Path testRoot : testRoots) {
            if (normalized.startsWith(testRoot)) {
                return true;
            }
        }
        return hasTestSourceSetSegment(normalized);
    }

    private Map<String, List<String>> buildProjectSymbolIndex() {
        if (projectSymbolIndex != null) {
            return projectSymbolIndex;
        }
        Map<String, List<String>> index = new HashMap<>();
        for (Path root : combinedSourceRoots()) {
            if (!Files.exists(root)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                for (Path file : (Iterable<Path>) paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))::iterator) {
                    String content = sourceFileEditor.readFile(file);
                    String pkg = parsePackage(content);
                    Set<String> types = parseTopLevelTypes(content);
                    for (String type : types) {
                        String fqn = pkg.isBlank() ? type : pkg + "." + type;
                        index.computeIfAbsent(type, k -> new ArrayList<>()).add(fqn);
                    }
                }
            } catch (IOException ignored) {
                // best effort
            }
        }
        projectSymbolIndex = index;
        return index;
    }

    private Map<String, List<String>> buildClasspathSymbolIndex() {
        if (classpathSymbolIndex != null) {
            return classpathSymbolIndex;
        }
        Map<String, List<String>> index = new HashMap<>();
        List<Path> entries = new ArrayList<>();
        Path mainOutput = moduleRoot.resolve("build/classes/java/main");
        if (Files.exists(mainOutput)) {
            entries.add(mainOutput);
        }
        Path rootMainOutput = projectRoot.resolve("build/classes/java/main");
        if (!rootMainOutput.equals(mainOutput) && Files.exists(rootMainOutput)) {
            entries.add(rootMainOutput);
        }
        String cp = System.getProperty("java.class.path", "");
        for (String part : cp.split(java.io.File.pathSeparator)) {
            if (!part.isBlank()) {
                Path path = Path.of(part);
                if (Files.exists(path)) {
                    entries.add(path.toAbsolutePath().normalize());
                }
            }
        }
        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                indexDirectory(entry, index);
            } else if (entry.toString().endsWith(".jar")) {
                indexJar(entry, index);
            }
        }
        classpathSymbolIndex = index;
        return index;
    }

    private void indexDirectory(Path dir, Map<String, List<String>> index) {
        try (var paths = Files.walk(dir)) {
            for (Path file : (Iterable<Path>) paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".class"))::iterator) {
                String rel = dir.relativize(file).toString().replace('\\', '/');
                if (rel.startsWith("META-INF") || rel.endsWith("module-info.class")) {
                    continue;
                }
                String fqn = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
                String simple = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                index.computeIfAbsent(simple, k -> new ArrayList<>()).add(fqn);
            }
        } catch (IOException ignored) {
            // ignore
        }
    }

    private void indexJar(Path jarPath, Map<String, List<String>> index) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            jarFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(JarEntry::getName)
                    .filter(name -> name.endsWith(".class"))
                    .filter(name -> !name.startsWith("META-INF") && !name.endsWith("module-info.class"))
                    .forEach(name -> {
                        String fqn = name.substring(0, name.length() - ".class".length()).replace('/', '.').replace('\\', '.');
                        String simple = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                        index.computeIfAbsent(simple, k -> new ArrayList<>()).add(fqn);
                    });
        } catch (IOException ignored) {
            // ignore
        }
    }

    private Path detectModuleRoot(Path root, Path anchorFile) {
        Path fallback = root.toAbsolutePath().normalize();
        if (anchorFile == null) {
            return fallback;
        }
        Path normalized = anchorFile.toAbsolutePath().normalize();
        for (int i = 0; i < normalized.getNameCount() - 2; i++) {
            if (!"src".equals(normalized.getName(i).toString())) {
                continue;
            }
            String sourceSet = normalized.getName(i + 1).toString().toLowerCase(Locale.ROOT);
            if (!sourceSet.contains("main") && !sourceSet.contains("test")) {
                continue;
            }
            Path prefix = normalized.getRoot() == null ? Path.of("") : normalized.getRoot();
            for (int j = 0; j < i; j++) {
                prefix = prefix.resolve(normalized.getName(j).toString());
            }
            if (prefix.toString().isBlank()) {
                return fallback;
            }
            return prefix.toAbsolutePath().normalize();
        }
        return fallback;
    }

    private List<Path> discoverMainSourceRoots() {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        addIfDirectory(roots, moduleRoot.resolve("src/main/java"));
        addIfDirectory(roots, projectRoot.resolve("src/main/java"));
        try (var paths = Files.walk(projectRoot, 6)) {
            for (Path directory : (Iterable<Path>) paths.filter(Files::isDirectory)::iterator) {
                if (isMainJavaRoot(directory)) {
                    roots.add(directory.toAbsolutePath().normalize());
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
        return List.copyOf(roots);
    }

    private List<Path> discoverTestSourceRoots() {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        addIfDirectory(roots, moduleRoot.resolve("src/test/java"));
        addIfDirectory(roots, projectRoot.resolve("src/test/java"));
        try (var paths = Files.walk(projectRoot, 6)) {
            for (Path directory : (Iterable<Path>) paths.filter(Files::isDirectory)::iterator) {
                if (isTestJavaRoot(directory)) {
                    roots.add(directory.toAbsolutePath().normalize());
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
        return List.copyOf(roots);
    }

    private List<Path> combinedSourceRoots() {
        LinkedHashSet<Path> roots = new LinkedHashSet<>(sourceRoots);
        roots.addAll(testRoots);
        return List.copyOf(roots);
    }

    private boolean isMainJavaRoot(Path path) {
        return isJavaRoot(path) && "main".equalsIgnoreCase(path.getName(path.getNameCount() - 2).toString());
    }

    private boolean isTestJavaRoot(Path path) {
        if (!isJavaRoot(path)) {
            return false;
        }
        String sourceSet = path.getName(path.getNameCount() - 2).toString().toLowerCase(Locale.ROOT);
        return sourceSet.contains("test");
    }

    private boolean isJavaRoot(Path path) {
        if (path == null || path.getNameCount() < 3) {
            return false;
        }
        int count = path.getNameCount();
        return "java".equals(path.getName(count - 1).toString())
                && "src".equals(path.getName(count - 3).toString());
    }

    private void addIfDirectory(Set<Path> collector, Path path) {
        if (path != null && Files.isDirectory(path)) {
            collector.add(path.toAbsolutePath().normalize());
        }
    }

    private boolean hasTestSourceSetSegment(Path path) {
        if (path == null) {
            return false;
        }
        for (int i = 0; i < path.getNameCount() - 1; i++) {
            if (!"src".equalsIgnoreCase(path.getName(i).toString())) {
                continue;
            }
            String sourceSet = path.getName(i + 1).toString().toLowerCase(Locale.ROOT);
            if (sourceSet.contains("test")) {
                return true;
            }
        }
        return false;
    }

    private String parsePackage(String content) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("package\\s+([a-zA-Z0-9_.]+)\\s*;").matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private Set<String> parseTopLevelTypes(String content) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b(class|interface|enum|record|@interface)\\s+([A-Za-z0-9_]+)\\b").matcher(content);
        Set<String> types = new java.util.HashSet<>();
        while (matcher.find()) {
            types.add(matcher.group(2));
        }
        return types;
    }
}
