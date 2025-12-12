package com.example.importanalyzer.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class ImportAnalyzer {
    private final ImportAnalyzerConfig config;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ConcurrentMap<Path, URLClassLoader> loaderCache = new ConcurrentHashMap<>();

    public ImportAnalyzer(ImportAnalyzerConfig config) {
        this.config = config;
    }

    public List<ImportIssue> analyze() {
        ClassIndex index = new ClassIndex();
        ImportGraph graph = new ImportGraph();
        Map<Path, Long> timestamps = new HashMap<>();

        IndexCache cache = new IndexCache(config.indexCachePath(), mapper);
        if (config.cacheEnabled() && config.reuseIndex() && Files.exists(config.indexCachePath())) {
            IndexCache.SerializedIndex serialized = cache.load();
            if (serialized != null) {
                serialized.entries().forEach(index::addEntry);
                serialized.graph().forEach((file, types) -> types.forEach(t -> graph.recordUsage(Path.of(file), t)));
                serialized.fileTimestamps().forEach((file, ts) -> timestamps.put(Path.of(file), ts));
            }
        }

        Set<Path> files = collectJavaFiles(config.sourceRoots());
        files.addAll(collectJavaFiles(config.testSourceRoots()));

        Set<Path> siblingSourceRoots = new HashSet<>();
        if (config.includeDependencies()) {
            siblingSourceRoots.addAll(new DependencyResolver().findSiblingSourceRoots(config.projectRoot()));
        }

        Set<Path> siblingFiles = collectJavaFiles(new ArrayList<>(siblingSourceRoots));

        var executor = Executors.newFixedThreadPool(config.threads());
        try {
            List<Callable<SourceFileResult>> tasks = files.stream().map(SourceFileResultCallable::new).map(c -> (Callable<SourceFileResult>) c).toList();
            List<Future<SourceFileResult>> futures = executor.invokeAll(tasks);
            for (int i = 0; i < futures.size(); i++) {
                try {
                    SourceFileResult result = futures.get(i).get();
                    timestamps.put(result.file(), Files.getLastModifiedTime(result.file()).toMillis());
                    registerDeclarations(index, result, config.sourceRoots(), config.testSourceRoots());
                    result.usedTypes().forEach(type -> graph.recordUsage(result.file(), type));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to analyze source " + ((SourceFileResultCallable) tasks.get(i)).path(), e);
                }
            }

            if (!siblingFiles.isEmpty()) {
                List<Callable<SourceFileResult>> siblingTasks = siblingFiles.stream().map(SourceFileResultCallable::new).map(c -> (Callable<SourceFileResult>) c).toList();
                List<Future<SourceFileResult>> siblingFutures = executor.invokeAll(siblingTasks);
                for (int i = 0; i < siblingFutures.size(); i++) {
                    try {
                        SourceFileResult result = siblingFutures.get(i).get();
                        registerDeclarations(index, result, List.copyOf(siblingSourceRoots), List.of());
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to analyze dependency source " + ((SourceFileResultCallable) siblingTasks.get(i)).path(), e);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
        }

        if (config.includeDependencies()) {
            scanDependencies(index);
        }

        seedJdk(index);

        List<ImportIssue> issues = new ArrayList<>();
        for (Path file : files) {
            try {
                SourceFileResult result = SourceFileAnalyzer.analyze(file);
                issues.addAll(evaluateForFile(result, index));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        if (config.cacheEnabled()) {
            Map<String, Set<String>> graphSnapshot = new HashMap<>();
            graph.viewFileToTypes().forEach((path, types) -> graphSnapshot.put(path.toString(), new HashSet<>(types)));
            Map<String, Long> tsSnapshot = new HashMap<>();
            timestamps.forEach((path, ts) -> tsSnapshot.put(path.toString(), ts));
            cache.save(new IndexCache.SerializedIndex(new ArrayList<>(index.asFqnMap().values()), graphSnapshot, tsSnapshot));
        }

        return issues;
    }

    private void registerDeclarations(ClassIndex index, SourceFileResult result, List<Path> mainRoots, List<Path> testRoots) {
        ClassOrigin origin = originForFile(result.file(), mainRoots, testRoots);
        for (String simple : result.declaredTypes()) {
            String fqn = result.packageName().isEmpty() ? simple : result.packageName() + "." + simple;
            index.addEntry(new ClassIndexEntry(fqn, simple, origin, result.file()));
        }
    }

    private ClassOrigin originForFile(Path file, List<Path> mainRoots, List<Path> testRoots) {
        for (Path root : testRoots) {
            if (file.startsWith(root)) {
                return ClassOrigin.PROJECT_TEST;
            }
        }
        return ClassOrigin.PROJECT_MAIN;
    }

    private Set<Path> collectJavaFiles(List<Path> roots) {
        Set<Path> files = new HashSet<>();
        for (Path root : roots) {
            if (!Files.exists(root)) continue;
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.toString().endsWith(".java")) {
                            files.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return files;
    }

    private static class SourceFileResultCallable implements Callable<SourceFileResult> {
        private final Path path;

        SourceFileResultCallable(Path path) {
            this.path = path;
        }

        Path path() {
            return path;
        }

        @Override
        public SourceFileResult call() throws Exception {
            try {
                return SourceFileAnalyzer.analyze(path);
            } catch (Exception e) {
                return fallbackResult();
            }
        }

        private SourceFileResult fallbackResult() throws IOException {
            String content = Files.readString(path);
            String pkg = "";
            Matcher pkgMatcher = Pattern.compile("package\\s+([a-zA-Z0-9_.]+)").matcher(content);
            if (pkgMatcher.find()) {
                pkg = pkgMatcher.group(1);
            }
            Set<String> declared = new HashSet<>();
            Matcher declMatcher = Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z0-9_]+)").matcher(content);
            while (declMatcher.find()) {
                declared.add(declMatcher.group(2));
            }
            return new SourceFileResult(path, pkg, Map.of(), Map.of(), Map.of(), Map.of(), declared, Set.of(), Set.of(), Map.of());
        }
    }

    List<ImportIssue> evaluateForFile(SourceFileResult result, ClassIndex index) {
        List<ImportIssue> issues = new ArrayList<>();

        Map<String, Integer> usedCounts = new HashMap<>();
        result.usedTypes().forEach(t -> usedCounts.merge(t, 1, Integer::sum));

        // wildcard issues
        result.wildcardImports().forEach((pkg, line) -> {
            List<ClassIndexEntry> candidates = index.byPackage(pkg);
            boolean used = candidates.stream().anyMatch(entry -> result.usedTypes().contains(entry.simpleName()));
            if (candidates.isEmpty()) {
                issues.add(new WildcardIssue(result.file(), line, pkg + ".*", "Remove wildcard import; package not found"));
            } else if (!used) {
                issues.add(new WildcardIssue(result.file(), line, pkg + ".*", "Remove unused wildcard import"));
            }
        });

        result.staticWildcardImports().forEach((pkg, line) -> {
            List<ClassIndexEntry> candidates = index.byPackage(pkg);
            if (candidates.isEmpty()) {
                issues.add(new WildcardIssue(result.file(), line, pkg + ".*", "Remove wildcard import; package not found"));
            }
        });

        // import checks
        result.imports().forEach((fqn, line) -> {
            ClassIndexEntry entry = index.getByFqn(fqn);
            String simple = simpleName(fqn);
            if (entry == null) {
                String pkg = fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : "";
                boolean packageKnown = !pkg.isEmpty() && !index.byPackage(pkg).isEmpty();
                List<ClassIndexEntry> alternatives = index.bySimpleName(simple);
                if (packageKnown && !alternatives.isEmpty()) {
                    issues.add(new WrongPackageIssue(result.file(), line, fqn, "Replace with: " + formatCandidates(alternatives)));
                } else {
                    String msg = pkg.isEmpty()
                            ? "Remove unresolved import or add the missing dependency"
                            : "Package " + pkg + " not found for import " + fqn;
                    issues.add(new UnresolvedImportIssue(result.file(), line, fqn, msg));
                }
            } else if (!result.usedTypes().contains(simple)) {
                issues.add(new UnusedImportIssue(result.file(), line, simple, "Remove unused import"));
            }
        });

        result.staticImports().forEach((fqn, line) -> {
            String owningType = fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : fqn;
            ClassIndexEntry entry = index.getByFqn(owningType);
            String simple = simpleName(fqn);
            if (entry == null) {
                issues.add(new UnresolvedImportIssue(result.file(), line, fqn, "Remove unresolved import or add the missing dependency"));
            } else if (!result.usedIdentifiers().contains(simple)) {
                issues.add(new UnusedImportIssue(result.file(), line, simple, "Remove unused import"));
            }
        });

        for (String used : result.usedTypes()) {
            if (result.declaredTypes().contains(used)) {
                continue;
            }
            boolean imported = result.imports().keySet().stream().anyMatch(fqn -> simpleName(fqn).equals(used));
            boolean samePackage = index.byPackage(result.packageName()).stream().anyMatch(entry -> entry.simpleName().equals(used));
            if (isJavaLang(used) || samePackage || imported) {
                continue;
            }
            List<ClassIndexEntry> candidates = index.bySimpleName(used);
            Set<String> invokedMembers = result.methodCallsByType().getOrDefault(used, Set.of());
            List<ClassIndexEntry> narrowed = filterByMembers(candidates, invokedMembers);
            if (candidates.isEmpty()) {
                issues.add(new MissingImportIssue(result.file(), 1, used, "Add missing import or dependency for type " + used));
            } else if (narrowed.size() == 1) {
                issues.add(new MissingImportIssue(result.file(), 1, used, "Add import for " + narrowed.get(0).fullyQualifiedName()));
            } else {
                issues.add(new AmbiguousImportIssue(result.file(), 1, used, "Choose one import: " + formatCandidates(narrowed)));
            }
        }
        return issues;
    }

    private boolean isJavaLang(String used) {
        return List.of("String", "Object", "System", "Exception", "RuntimeException", "Iterable").contains(used);
    }

    private String formatCandidates(List<ClassIndexEntry> candidates) {
        List<ClassIndexEntry> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator
                .comparingInt((ClassIndexEntry e) -> switch (e.origin()) {
                    case PROJECT_MAIN -> 0;
                    case PROJECT_TEST -> 1;
                    case DEPENDENCY_JAR -> 2;
                    case JDK -> 3;
                })
                .thenComparing(ClassIndexEntry::fullyQualifiedName));
        int limit = 5;
        String joined = sorted.stream()
                .limit(limit)
                .map(ClassIndexEntry::fullyQualifiedName)
                .collect(Collectors.joining(", "));
        if (sorted.size() > limit) {
            joined = joined + " … +" + (sorted.size() - limit) + " more";
        }
        return joined;
    }

    private void scanDependencies(ClassIndex index) {
        DependencyResolver resolver = new DependencyResolver();
        Set<Path> artifacts = resolver.findDependencyArtifacts(config.projectRoot());
        var executor = Executors.newFixedThreadPool(Math.max(1, config.threads() / 2));
        List<Callable<Void>> tasks = artifacts.stream().map(path -> (Callable<Void>) () -> {
            if (Files.isDirectory(path)) {
                scanClassDirectory(index, path);
            } else {
                scanJar(index, path);
            }
            return null;
        }).toList();
        try {
            executor.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
        }
    }

    private void scanJar(ClassIndex index, Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            jarFile.stream()
                    .filter(e -> e.getName().endsWith(".class") && !e.isDirectory())
                    .forEach(entry -> addJarEntry(index, entry, jar));
        } catch (IOException ignored) {
        }
    }

    private void scanClassDirectory(ClassIndex index, Path directory) {
        try {
            Files.walk(directory)
                    .filter(path -> path.toString().endsWith(".class"))
                    .forEach(path -> {
                        String relative = directory.relativize(path).toString();
                        if (relative.contains("$")) {
                            relative = relative.substring(0, relative.indexOf('$')) + ".class";
                        }
                        if (relative.endsWith(".class")) {
                            String fqn = relative.substring(0, relative.length() - 6)
                                    .replace('/', '.')
                                    .replace('\\', '.');
                            if (!fqn.contains("module-info")) {
                                index.addEntry(new ClassIndexEntry(fqn, simpleName(fqn), ClassOrigin.DEPENDENCY_JAR, directory));
                            }
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private void addJarEntry(ClassIndex index, JarEntry entry, Path jar) {
        String name = entry.getName().replace('/', '.').replace(".class", "");
        if (name.contains("$")) {
            name = name.substring(0, name.indexOf('$'));
        }
        String simple = simpleName(name);
        index.addEntry(new ClassIndexEntry(name, simple, ClassOrigin.DEPENDENCY_JAR, jar));
    }

    private String simpleName(String fqn) {
        int idx = fqn.lastIndexOf('.') ;
        return idx >=0 ? fqn.substring(idx+1) : fqn;
    }

    private List<ClassIndexEntry> filterByMembers(List<ClassIndexEntry> candidates, Set<String> members) {
        if (candidates.isEmpty()) {
            return candidates;
        }

        // de-duplicate by FQN to avoid overwhelming suggestions
        Map<String, ClassIndexEntry> unique = new LinkedHashMap<>();
        for (ClassIndexEntry entry : candidates) {
            unique.putIfAbsent(entry.fullyQualifiedName(), entry);
        }
        List<ClassIndexEntry> deduped = new ArrayList<>(unique.values());

        if (members.isEmpty()) {
            return deduped;
        }

        List<ClassIndexEntry> matching = new ArrayList<>();
        for (ClassIndexEntry entry : deduped) {
            if (supportsMembers(entry, members)) {
                matching.add(entry);
            }
        }

        if (!matching.isEmpty()) {
            return matching;
        }

        // Heuristic ranking when reflective checks could not validate members (e.g. missing deps)
        deduped.sort(Comparator.comparingInt((ClassIndexEntry e) -> heuristicScore(e, members)).reversed());
        int limit = Math.min(5, deduped.size());
        return deduped.subList(0, limit);
    }

    private int heuristicScore(ClassIndexEntry entry, Set<String> members) {
        int score = 0;
        score += switch (entry.origin()) {
            case PROJECT_MAIN -> 40;
            case PROJECT_TEST -> 30;
            case JDK -> 20;
            case DEPENDENCY_JAR -> 10;
        };

        String fqn = entry.fullyQualifiedName();
        if (fqn.startsWith("org.junit.jupiter.api.Assertions")) {
            score += 25;
        }
        if (fqn.startsWith("org.junit.Assert")) {
            score += 20;
        }
        if (fqn.contains("junit")) {
            score += 10;
        }
        if (members.stream().anyMatch(m -> m.startsWith("assert"))) {
            if (fqn.contains("assert")) {
                score += 5;
            }
        }
        return score;
    }

    private boolean supportsMembers(ClassIndexEntry entry, Set<String> members) {
        return switch (entry.origin()) {
            case PROJECT_MAIN, PROJECT_TEST -> supportsMembersFromSource(entry.location(), members);
            case DEPENDENCY_JAR -> supportsMembersFromArtifact(entry, members);
            case JDK -> supportsMembersFromJdk(entry.fullyQualifiedName(), members);
        };
    }

    private boolean supportsMembersFromSource(Path source, Set<String> members) {
        if (source == null || !Files.isRegularFile(source)) {
            return false;
        }
        try {
            var cu = com.github.javaparser.StaticJavaParser.parse(Files.readString(source));
            var declared = new HashSet<String>();
            cu.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).forEach(md -> {
                if (md.isStatic()) {
                    declared.add(md.getNameAsString());
                }
            });
            return members.stream().allMatch(declared::contains);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private boolean supportsMembersFromJdk(String fqn, Set<String> members) {
        try {
            Class<?> clazz = Class.forName(fqn);
            return hasAllStaticMethods(clazz, members);
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private boolean supportsMembersFromArtifact(ClassIndexEntry entry, Set<String> members) {
        Path location = entry.location();
        if (location == null) {
            return false;
        }
        try {
            URLClassLoader loader = loaderCache.computeIfAbsent(location, this::createLoader);
            Class<?> clazz = Class.forName(entry.fullyQualifiedName(), false, loader);
            return hasAllStaticMethods(clazz, members);
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private URLClassLoader createLoader(Path location) {
        try {
            URL url = location.toUri().toURL();
            return new URLClassLoader(new URL[]{url}, getClass().getClassLoader());
        } catch (Exception e) {
            return new URLClassLoader(new URL[]{});
        }
    }

    private boolean hasAllStaticMethods(Class<?> clazz, Set<String> members) {
        try {
            List<Method> methods = Arrays.asList(clazz.getMethods());
            for (String name : members) {
                boolean found = methods.stream().anyMatch(m -> m.getName().equals(name) && Modifier.isStatic(m.getModifiers()));
                if (!found) {
                    return false;
                }
            }
            return true;
        } catch (LinkageError e) {
            return false;
        }
    }

    private void seedJdk(ClassIndex index) {
        List<String> jdk = List.of(
                "java.lang.String",
                "java.lang.Object",
                "java.lang.System",
                "java.lang.Exception",
                "java.util.List",
                "java.util.Map",
                "java.util.Set",
                "java.nio.file.Path"
        );
        jdk.forEach(fqn -> index.addEntry(new ClassIndexEntry(fqn, simpleName(fqn), ClassOrigin.JDK, Path.of("<jdk>"))));
    }
}
