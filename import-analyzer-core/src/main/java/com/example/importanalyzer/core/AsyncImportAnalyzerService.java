package com.example.importanalyzer.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link ImportAnalyzerService} that performs background scanning and can
 * return actionable import guidance for a single file on demand.
 */
public class AsyncImportAnalyzerService implements ImportAnalyzerService {
    private final ImportAnalyzerConfig config;
    private final ExecutorService executor;
    private final Map<Path, SourceFileResult> analyzedFiles = new ConcurrentHashMap<>();
    private final ClassIndex classIndex = new ClassIndex();
    private final AtomicInteger scannedCount = new AtomicInteger();
    private volatile CompletableFuture<Void> scanFuture;
    private volatile int totalFiles;

    public AsyncImportAnalyzerService(ImportAnalyzerConfig config) {
        this.config = config;
        this.executor = Executors.newFixedThreadPool(config.threads(), this::daemonThread);
    }

    @Override
    public void startScan() {
        if (scanFuture != null) {
            return;
        }
        scanFuture = CompletableFuture.runAsync(this::runScan, executor);
    }

    @Override
    public ScanResult status() {
        boolean running = scanFuture != null && !scanFuture.isDone();
        return new ScanResult(null, ImportAction.UNKNOWN, -1, List.of(), ImportSource.UNKNOWN, running, scannedCount.get(), totalFiles);
    }

    @Override
    public CompletableFuture<ScanResult> scan(Path file) {
        startScan();
        if (scanFuture == null) {
            return CompletableFuture.completedFuture(new ScanResult(file, ImportAction.UNKNOWN, -1, List.of(), ImportSource.UNKNOWN, true, scannedCount.get(), totalFiles));
        }
        if (!scanFuture.isDone()) {
            return CompletableFuture.completedFuture(new ScanResult(file, ImportAction.UNKNOWN, -1, List.of(), ImportSource.UNKNOWN, true, scannedCount.get(), totalFiles));
        }
        return scanFuture.thenApplyAsync(ignored -> buildResult(file), executor);
    }

    private void runScan() {
        Set<Path> files = collectJavaFiles(config.sourceRoots());
        files.addAll(collectJavaFiles(config.testSourceRoots()));
        totalFiles = files.size();

        ExecutorService workerPool = Executors.newFixedThreadPool(Math.max(1, config.threads()), this::daemonThread);
        try {
            List<Future<SourceFileResult>> tasks = workerPool.invokeAll(
                    files.stream().map(path -> (java.util.concurrent.Callable<SourceFileResult>) () -> SourceFileAnalyzer.analyze(path)).toList());
            for (Future<SourceFileResult> future : tasks) {
                try {
                    SourceFileResult result = future.get();
                    registerDeclarations(classIndex, result, config.sourceRoots(), config.testSourceRoots());
                    analyzedFiles.put(result.file(), result);
                    scannedCount.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to analyze source", e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            workerPool.shutdown();
        }

        if (config.includeDependencies()) {
            new DependencyResolver().findDependencyArtifacts(config.projectRoot()).forEach(path -> {
                if (Files.isDirectory(path)) {
                    scanClassDirectory(classIndex, path);
                } else {
                    scanJar(classIndex, path);
                }
            });
        }
        seedJdk(classIndex);
    }

    private ScanResult buildResult(Path file) {
        SourceFileResult result = analyzedFiles.get(file);
        if (result == null) {
            return new ScanResult(file, ImportAction.UNKNOWN, -1, List.of(), ImportSource.UNKNOWN, false, scannedCount.get(), totalFiles);
        }
        List<ImportIssue> issues = new ImportAnalyzer(config).evaluateForFile(result, classIndex);
        if (issues.isEmpty()) {
            return new ScanResult(file, ImportAction.UNKNOWN, -1, List.of(), ImportSource.UNKNOWN, false, scannedCount.get(), totalFiles);
        }
        ImportIssue primary = issues.get(0);
        ImportAction action = toAction(primary);
        return new ScanResult(file, action, primary.line(), candidatesFor(primary), sourceFor(primary, action), false, scannedCount.get(), totalFiles);
    }

    private ImportAction toAction(ImportIssue issue) {
        if (issue instanceof MissingImportIssue) {
            MissingImportIssue missing = (MissingImportIssue) issue;
            List<ClassIndexEntry> candidates = classIndex.bySimpleName(missing.symbol());
            if (candidates.size() == 1) {
                return ImportAction.ADD;
            } else if (candidates.size() > 1) {
                return ImportAction.SELECT;
            }
        }
        if (issue instanceof AmbiguousImportIssue || issue instanceof WrongPackageIssue) {
            return ImportAction.SELECT;
        }
        if (issue instanceof UnusedImportIssue || issue instanceof UnresolvedImportIssue || issue instanceof WildcardIssue) {
            return ImportAction.DELETE;
        }
        return ImportAction.UNKNOWN;
    }

    private List<String> candidatesFor(ImportIssue issue) {
        if (issue instanceof MissingImportIssue) {
            MissingImportIssue missing = (MissingImportIssue) issue;
            return classIndex.bySimpleName(missing.symbol()).stream()
                    .map(ClassIndexEntry::fullyQualifiedName)
                    .collect(Collectors.toList());
        }
        if (issue instanceof AmbiguousImportIssue) {
            AmbiguousImportIssue ambiguous = (AmbiguousImportIssue) issue;
            return classIndex.bySimpleName(ambiguous.symbol()).stream()
                    .map(ClassIndexEntry::fullyQualifiedName)
                    .collect(Collectors.toList());
        }
        if (issue instanceof WrongPackageIssue) {
            WrongPackageIssue wrong = (WrongPackageIssue) issue;
            String simple = simpleName(wrong.symbol());
            return classIndex.bySimpleName(simple).stream()
                    .map(ClassIndexEntry::fullyQualifiedName)
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private ImportSource sourceFor(ImportIssue issue, ImportAction action) {
        if (action != ImportAction.ADD) {
            return ImportSource.UNKNOWN;
        }
        if (issue instanceof MissingImportIssue) {
            MissingImportIssue missing = (MissingImportIssue) issue;
            List<ClassIndexEntry> candidates = classIndex.bySimpleName(missing.symbol());
            if (candidates.size() == 1) {
                return mapOrigin(candidates.get(0).origin());
            }
        }
        if (issue instanceof WrongPackageIssue) {
            WrongPackageIssue wrong = (WrongPackageIssue) issue;
            String simple = simpleName(wrong.symbol());
            List<ClassIndexEntry> candidates = classIndex.bySimpleName(simple);
            if (candidates.size() == 1) {
                return mapOrigin(candidates.get(0).origin());
            }
        }
        return ImportSource.UNKNOWN;
    }

    private ImportSource mapOrigin(ClassOrigin origin) {
        return origin == ClassOrigin.PROJECT_MAIN ? ImportSource.LOCAL : ImportSource.LIBRARY;
    }

    private Set<Path> collectJavaFiles(List<Path> roots) {
        Set<Path> files = new HashSet<>();
        for (Path root : roots) {
            if (!Files.exists(root)) continue;
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.toString().endsWith(".java")) {
                            files.add(file);
                        }
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return files;
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

    private void scanJar(ClassIndex index, Path jar) {
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar.toFile())) {
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

    private void addJarEntry(ClassIndex index, java.util.jar.JarEntry entry, Path jar) {
        String name = entry.getName().replace('/', '.').replace(".class", "");
        if (name.contains("$")) {
            name = name.substring(0, name.indexOf('$'));
        }
        String simple = simpleName(name);
        index.addEntry(new ClassIndexEntry(name, simple, ClassOrigin.DEPENDENCY_JAR, jar));
    }

    private Thread daemonThread(Runnable runnable) {
        Thread t = new Thread(runnable);
        t.setDaemon(true);
        t.setName("import-analyzer-service-" + t.getId());
        return t;
    }

    private String simpleName(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(idx + 1) : fqn;
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
