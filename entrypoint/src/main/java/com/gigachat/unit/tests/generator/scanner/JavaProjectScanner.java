package com.gigachat.unit.tests.generator.scanner;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentMode;
import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.dto.ClassMetadata;
import com.gigachat.unit.tests.generator.dto.FieldMetadata;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.util.TargetClassMatcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JavaProjectScanner {
    private final JavaParser javaParser;
    private final MethodSignatureRegistry methodRegistry;

    public JavaProjectScanner() {
        this(new JavaParser(), new MethodSignatureRegistry());
    }

    public JavaProjectScanner(MethodSignatureRegistry registry) {
        this(new JavaParser(), registry);
    }

    public JavaProjectScanner(JavaParser javaParser, MethodSignatureRegistry registry) {
        this.javaParser = Objects.requireNonNull(javaParser, "javaParser");
        this.methodRegistry = Objects.requireNonNull(registry, "methodRegistry");
    }

    public List<TestClassInfo> scan(AgentConfig config) throws IOException {
        List<Path> moduleRoots = determineModuleRoots(config);
        TargetResolution targetResolution = resolveExplicitTargets(config, moduleRoots);
        if (targetResolution.hasResolvedFiles()) {
            return scanResolvedTargets(config, targetResolution);
        }
        Set<Path> diffFiles = resolveDiffJavaFiles(config);
        List<TestClassInfo> discoveredClasses = new ArrayList<>();
        for (Path moduleRoot : moduleRoots) {
            Path sourceRoot = resolveSourceRoot(moduleRoot);
            if (!Files.exists(sourceRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> shouldProcessFile(path, diffFiles))
                        .forEach(path -> parseJavaFile(path, moduleRoot, config, discoveredClasses, null));
            } catch (java.io.UncheckedIOException ex) {
                throw (IOException) ex.getCause();
            }
        }
        return List.copyOf(discoveredClasses);
    }

    public void scanSequentially(AgentConfig config, Consumer<List<TestClassInfo>> perFileConsumer) throws IOException {
        Objects.requireNonNull(perFileConsumer, "perFileConsumer");
        List<Path> moduleRoots = determineModuleRoots(config);
        TargetResolution targetResolution = resolveExplicitTargets(config, moduleRoots);
        if (targetResolution.hasResolvedFiles()) {
            for (ResolvedTarget target : targetResolution.resolvedTargets()) {
                parseAndEmit(target.file(), target.moduleRoot(), config, perFileConsumer, target);
            }
            return;
        }
        Set<Path> diffFiles = resolveDiffJavaFiles(config);
        for (Path moduleRoot : moduleRoots) {
            Path sourceRoot = resolveSourceRoot(moduleRoot);
            if (!Files.exists(sourceRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> shouldProcessFile(path, diffFiles))
                        .forEach(path -> parseAndEmit(path, moduleRoot, config, perFileConsumer, null));
            } catch (java.io.UncheckedIOException ex) {
                throw (IOException) ex.getCause();
            }
        }
    }

    private boolean shouldProcessFile(Path javaFile, Set<Path> diffFiles) {
        if (diffFiles == null || diffFiles.isEmpty()) {
            return true;
        }
        return diffFiles.contains(javaFile.toAbsolutePath().normalize());
    }

    private Set<Path> resolveDiffJavaFiles(AgentConfig config) throws IOException {
        if (config == null || config.getMode() != AgentMode.DIFF_GEN_UNIT_TEST) {
            return Set.of();
        }
        String sourceBranch = config.getSourceBranch();
        String targetBranch = config.getTargetBranch();
        if (sourceBranch == null || targetBranch == null) {
            return Set.of();
        }
        ProcessBuilder processBuilder = new ProcessBuilder("git", "diff", "--name-only", targetBranch + "..." + sourceBranch, "--", "*.java");
        processBuilder.directory(config.getProjectPath().toFile());
        Process process = processBuilder.start();
        Set<Path> changed = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                changed.add(config.getProjectPath().resolve(trimmed).toAbsolutePath().normalize());
            }
        }
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Failed to resolve git diff between branches: " + sourceBranch + " and " + targetBranch);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while resolving git diff", ex);
        }
        return Set.copyOf(changed);
    }

    private void parseAndEmit(Path javaFile,
                              Path moduleRoot,
                              AgentConfig config,
                              Consumer<List<TestClassInfo>> perFileConsumer,
                              ResolvedTarget target) {
        List<TestClassInfo> collector = new ArrayList<>();
        parseJavaFile(javaFile, moduleRoot, config, collector, target);
        if (!collector.isEmpty()) {
            perFileConsumer.accept(List.copyOf(collector));
        }
    }

    protected void parseJavaFile(Path javaFile,
                                 Path moduleRoot,
                                 AgentConfig config,
                                 List<TestClassInfo> collector,
                                 ResolvedTarget target) {
        try {
            javaParser.parse(javaFile).getResult().ifPresentOrElse(
                    compilationUnit -> handleCompilationUnit(compilationUnit, moduleRoot, config, collector, target),
                    () -> System.err.println("Unable to parse file: " + javaFile)
            );
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    protected void handleCompilationUnit(CompilationUnit compilationUnit,
                                         Path moduleRoot,
                                         AgentConfig config,
                                         List<TestClassInfo> collector,
                                         ResolvedTarget target) {
        String packageName = compilationUnit.getPackageDeclaration()
                .map(declaration -> declaration.getName().asString())
                .orElse("");
        List<ClassOrInterfaceDeclaration> declarations = compilationUnit.getTypes().stream()
                .filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(ClassOrInterfaceDeclaration.class::cast)
                .filter(declaration -> !declaration.isInterface())
                .toList();
        declarations.forEach(this::registerSignatures);
        if (target != null) {
            List<TestClassInfo> resolved = declarations.stream()
                    .filter(declaration -> declaration.getNameAsString().equals(target.className()))
                    .map(declaration -> createTestClassInfo(compilationUnit,
                            moduleRoot,
                            packageName,
                            declaration,
                            target.methodNames()))
                    .toList();
            if (resolved.isEmpty()) {
                throw new IllegalArgumentException("Класс не найден в файле для пути: " + target.rawTarget());
            }
            if (!target.methodNames().isEmpty() && resolved.stream().allMatch(info -> info.getMethods().isEmpty())) {
                throw new IllegalArgumentException("Метод не найден по пути: " + target.rawTarget());
            }
            collector.addAll(resolved);
            return;
        }
        declarations.stream()
                .filter(declaration -> shouldInclude(declaration, packageName, moduleRoot, config))
                .map(declaration -> createTestClassInfo(compilationUnit, moduleRoot, packageName, declaration, Set.of()))
                .forEach(collector::add);
    }

    private TestClassInfo createTestClassInfo(CompilationUnit compilationUnit,
                                              Path moduleRoot,
                                              String packageName,
                                              ClassOrInterfaceDeclaration declaration,
                                              Set<String> methodFilter) {
        Path targetRoot = moduleRoot.resolve(Path.of("src", "test", "java"));
        Path packagePath = packageName.isBlank()
                ? targetRoot
                : targetRoot.resolve(Path.of(packageName.replace('.', '/')));
        String testClassName = declaration.getNameAsString() + "Test";
        Path targetFile = packagePath.resolve(testClassName + ".java");

        List<TestMethodInfo> methods = declaration.getMethods().stream()
                .filter(method -> !method.isPrivate())
                .filter(method -> methodFilter == null || methodFilter.isEmpty() || methodFilter.contains(method.getNameAsString()))
                .map(this::createTestMethodInfo)
                .collect(Collectors.toCollection(ArrayList::new));

        List<String> imports = new ArrayList<>(compilationUnit.getImports().stream()
                .map(importDeclaration -> importDeclaration.toString().trim())
                .collect(Collectors.toCollection(LinkedHashSet::new)));

        ClassMetadata metadata = extractClassMetadata(declaration);

        return new TestClassInfo(
                declaration.getNameAsString(),
                testClassName,
                targetFile,
                List.copyOf(imports),
                List.copyOf(methods),
                metadata
        );
    }

    public MethodSignatureRegistry getMethodRegistry() {
        return methodRegistry;
    }

    private TestMethodInfo createTestMethodInfo(MethodDeclaration methodDeclaration) {
        String signature = methodDeclaration.getDeclarationAsString(true, true, true);
        String returnType = methodDeclaration.getType().asString();
        String body = methodDeclaration.getBody()
                .map(Object::toString)
                .orElse("");
        return new TestMethodInfo(signature, returnType, body, methodDeclaration.clone());
    }

    private ClassMetadata extractClassMetadata(ClassOrInterfaceDeclaration declaration) {
        if (declaration == null) {
            return new ClassMetadata("", List.of());
        }
        List<FieldMetadata> fields = new ArrayList<>();
        declaration.getFields().forEach(field -> {
            String typeName = field.getElementType().asString();
            boolean isPrivate = field.isPrivate();
            field.getVariables().forEach(variable ->
                    fields.add(new FieldMetadata(variable.getNameAsString(), typeName, isPrivate)));
        });
        return new ClassMetadata(declaration.getNameAsString(), fields);
    }

    private void registerSignatures(ClassOrInterfaceDeclaration declaration) {
        String className = declaration.getNameAsString();
        registerConstructors(className, declaration.getConstructors());
        registerMethods(className, declaration.getMethods());
    }

    private void registerConstructors(String className, List<ConstructorDeclaration> constructors) {
        if (constructors == null || constructors.isEmpty()) {
            ConstructorMetadata metadata = new ConstructorMetadata(className + "()", List.of());
            methodRegistry.registerConstructor(className, metadata);
            return;
        }
        for (ConstructorDeclaration constructor : constructors) {
            if (constructor == null || !constructor.isPublic()) {
                continue;
            }
            String signature = buildConstructorSignature(className, constructor);
            List<ParameterMetadata> parameters = new ArrayList<>();
            NodeList<Parameter> constructorParameters = constructor.getParameters();
            for (Parameter parameter : constructorParameters) {
                String name = parameter.getNameAsString();
                String type = parameter.getType().asString();
                List<String> modifiers = parameter.getModifiers().stream()
                        .map(modifier -> modifier.getKeyword().asString())
                        .collect(Collectors.toCollection(ArrayList::new));
                parameters.add(new ParameterMetadata(name, type, modifiers));
            }
            ConstructorMetadata metadata = new ConstructorMetadata(signature, parameters);
            methodRegistry.registerConstructor(className, metadata);
        }
    }

    private String buildConstructorSignature(String className, ConstructorDeclaration constructor) {
        return (className + formatParameters(constructor == null ? new NodeList<>() : constructor.getParameters())).trim();
    }

    private void registerMethods(String className, List<MethodDeclaration> methods) {
        if (methods == null || methods.isEmpty()) {
            return;
        }
        for (MethodDeclaration method : methods) {
            if (method.isPrivate() || !method.isPublic() || method.isStatic()) {
                continue;
            }
            String signature = method.getType().asString() + " "
                    + method.getNameAsString() + formatParameters(method.getParameters());
            methodRegistry.registerMethod(className, signature);
        }
    }

    private String formatParameters(NodeList<Parameter> parameters) {
        StringBuilder builder = new StringBuilder();
        builder.append('(');
        if (parameters != null && !parameters.isEmpty()) {
            for (int i = 0; i < parameters.size(); i++) {
                Parameter parameter = parameters.get(i);
                builder.append(parameter.getType().asString()).append(' ').append(parameter.getNameAsString());
                if (i + 1 < parameters.size()) {
                    builder.append(", ");
                }
            }
        }
        builder.append(')');
        return builder.toString();
    }

    private boolean shouldInclude(ClassOrInterfaceDeclaration declaration,
                                  String packageName,
                                  Path moduleRoot,
                                  AgentConfig config) {
        if (config == null) {
            return true;
        }
        String className = declaration.getNameAsString();
        String testClassName = className + "Test";
        String fullName = packageName.isBlank()
                ? className
                : packageName + '.' + className;
        List<String> targetClasses = config.getTargetClasses();
        if (targetClasses != null && !targetClasses.isEmpty()) {
            Path targetPath = resolveTestTargetPath(moduleRoot, packageName, testClassName);
            for (String target : targetClasses) {
                if (TargetClassMatcher.matches(target,
                        packageName,
                        className,
                        testClassName,
                        moduleRoot,
                        targetPath)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private Path resolveTestTargetPath(Path moduleRoot, String packageName, String testClassName) {
        Path targetRoot = moduleRoot.resolve(Path.of("src", "test", "java"));
        Path packagePath = packageName == null || packageName.isBlank()
                ? targetRoot
                : targetRoot.resolve(Path.of(packageName.replace('.', '/')));
        return packagePath.resolve(testClassName + ".java");
    }

    private List<Path> determineModuleRoots(AgentConfig config) throws IOException {
        Path projectPath = config.getProjectPath();
        List<String> includeModules = config.getIncludeModules();
        List<Path> modules = new ArrayList<>();
        if (includeModules != null && !includeModules.isEmpty()) {
            for (String module : includeModules) {
                Path modulePath = projectPath.resolve(module);
                if (Files.exists(modulePath)) {
                    modules.add(modulePath);
                }
            }
        } else {
            modules.add(projectPath);
        }

        List<String> targetClasses = config.getTargetClasses();
        if (targetClasses != null) {
            for (String target : targetClasses) {
                if (target == null || target.isBlank()) {
                    continue;
                }
                String trimmed = target.trim();
                boolean moduleWildcard = trimmed.endsWith(".*");
                String base = moduleWildcard ? trimmed.substring(0, trimmed.length() - 2) : trimmed;
                String normalised = base.replace('\\', '/').replace('.', '/');
                if (normalised.endsWith("/*")) {
                    normalised = normalised.substring(0, normalised.length() - 2);
                } else if (normalised.endsWith("/")) {
                    normalised = normalised.substring(0, normalised.length() - 1);
                }
                if (normalised.isEmpty()) {
                    continue;
                }
                Path moduleCandidate = projectPath.resolve(normalised);
                if (Files.exists(moduleCandidate) && Files.isDirectory(moduleCandidate) && !modules.contains(moduleCandidate)) {
                    modules.add(moduleCandidate);
                }
            }
        }

        if (modules.isEmpty()) {
            return List.of(projectPath);
        }
        return List.copyOf(modules);
    }

    private Path resolveSourceRoot(Path moduleRoot) {
        Path mainJava = moduleRoot.resolve(Path.of("src", "main", "java"));
        if (Files.exists(mainJava)) {
            return mainJava;
        }
        Path src = moduleRoot.resolve("src");
        if (Files.exists(src)) {
            return src;
        }
        return moduleRoot;
    }

    private List<TestClassInfo> scanResolvedTargets(AgentConfig config, TargetResolution targetResolution) {
        List<TestClassInfo> discoveredClasses = new ArrayList<>();
        for (ResolvedTarget target : targetResolution.resolvedTargets()) {
            parseJavaFile(target.file(), target.moduleRoot(), config, discoveredClasses, target);
        }
        return List.copyOf(discoveredClasses);
    }

    private TargetResolution resolveExplicitTargets(AgentConfig config, List<Path> moduleRoots) {
        List<String> targetClasses = config.getTargetClasses();
        if (targetClasses == null || targetClasses.isEmpty()) {
            return TargetResolution.empty();
        }
        List<String> explicitTargets = targetClasses.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(target -> !target.isEmpty())
                .filter(target -> !target.contains("*"))
                .toList();
        if (explicitTargets.isEmpty()) {
            return TargetResolution.empty();
        }

        List<ResolvedTarget> resolvedTargets = new ArrayList<>();
        List<String> unresolvedTargets = new ArrayList<>();
        for (String rawTarget : explicitTargets) {
            ParsedTarget parsedTarget = parseTarget(rawTarget);
            ResolvedTarget resolved = resolveTargetToFile(config.getProjectPath(), moduleRoots, parsedTarget, rawTarget);
            if (resolved == null) {
                unresolvedTargets.add(rawTarget);
                continue;
            }
            resolvedTargets.add(resolved);
        }

        if (!unresolvedTargets.isEmpty()) {
            throw new IllegalArgumentException("Файл по пути(ям) не найден: " + unresolvedTargets);
        }

        return mergeResolvedTargets(resolvedTargets);
    }

    private TargetResolution mergeResolvedTargets(List<ResolvedTarget> resolvedTargets) {
        if (resolvedTargets.isEmpty()) {
            return TargetResolution.empty();
        }
        record Key(Path file, Path moduleRoot, String className, String rawTarget) {}
        java.util.Map<Key, Set<String>> merged = new java.util.LinkedHashMap<>();
        final String allMethodsMarker = "__ALL_METHODS__";
        for (ResolvedTarget target : resolvedTargets) {
            Key key = new Key(target.file(), target.moduleRoot(), target.className(), target.rawTarget());
            Set<String> methods = merged.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
            if (methods.contains(allMethodsMarker)) {
                continue;
            }
            if (target.methodNames().isEmpty()) {
                methods.clear();
                methods.add(allMethodsMarker);
                continue;
            }
            methods.addAll(target.methodNames());
        }
        List<ResolvedTarget> unique = merged.entrySet().stream()
                .map(entry -> {
                    Set<String> methods = entry.getValue().contains(allMethodsMarker)
                            ? Set.of()
                            : Set.copyOf(entry.getValue());
                    return new ResolvedTarget(entry.getKey().file(),
                            entry.getKey().moduleRoot(),
                            entry.getKey().className(),
                            methods,
                            entry.getKey().rawTarget());
                })
                .toList();
        return new TargetResolution(unique);
    }

    private ParsedTarget parseTarget(String rawTarget) {
        String normalized = rawTarget.trim().replace('/', '.').replace('\\', '.');
        if (normalized.endsWith(".java")) {
            normalized = normalized.substring(0, normalized.length() - 5);
        }
        return new ParsedTarget(normalized);
    }

    private ResolvedTarget resolveTargetToFile(Path projectPath,
                                               List<Path> moduleRoots,
                                               ParsedTarget target,
                                               String rawTarget) {
        Map<Path, Path> sourceRoots = collectCandidateSourceRoots(projectPath, moduleRoots);
        ResolutionCandidate classOnly = resolveByClassPath(sourceRoots, target.rawPath());
        if (classOnly != null) {
            return new ResolvedTarget(classOnly.file(), classOnly.moduleRoot(), simpleClassName(target.rawPath()), Set.of(), rawTarget);
        }
        int separator = target.rawPath().lastIndexOf('.');
        if (separator > 0 && separator + 1 < target.rawPath().length()) {
            String classPath = target.rawPath().substring(0, separator);
            String methodName = target.rawPath().substring(separator + 1);
            ResolutionCandidate withMethod = resolveByClassPath(sourceRoots, classPath);
            if (withMethod != null) {
                return new ResolvedTarget(withMethod.file(),
                        withMethod.moduleRoot(),
                        simpleClassName(classPath),
                        Set.of(methodName),
                        rawTarget);
            }
        }
        return null;
    }

    private Map<Path, Path> collectCandidateSourceRoots(Path projectPath, List<Path> moduleRoots) {
        Map<Path, Path> roots = new java.util.LinkedHashMap<>();
        for (Path moduleRoot : moduleRoots) {
            Path normalizedRoot = moduleRoot.toAbsolutePath().normalize();
            roots.putIfAbsent(normalizedRoot, resolveSourceRoot(normalizedRoot));
        }
        Path normalizedProjectPath = projectPath.toAbsolutePath().normalize();
        if (Files.exists(normalizedProjectPath) && Files.isDirectory(normalizedProjectPath)) {
            try (Stream<Path> children = Files.list(normalizedProjectPath)) {
                children.filter(Files::isDirectory)
                        .forEach(child -> {
                            Path sourceRoot = resolveSourceRoot(child);
                            if (Files.exists(sourceRoot)) {
                                roots.putIfAbsent(child.toAbsolutePath().normalize(), sourceRoot.toAbsolutePath().normalize());
                            }
                        });
            } catch (IOException ignored) {
            }
        }
        return roots;
    }

    private ResolutionCandidate resolveByClassPath(Map<Path, Path> sourceRoots, String classPath) {
        String relative = classPath.replace('.', '/') + ".java";
        for (Map.Entry<Path, Path> entry : sourceRoots.entrySet()) {
            Path classFile = entry.getValue().resolve(relative).toAbsolutePath().normalize();
            if (Files.exists(classFile) && Files.isRegularFile(classFile)) {
                return new ResolutionCandidate(classFile, entry.getKey());
            }
        }
        return null;
    }

    private String simpleClassName(String classPath) {
        int separator = classPath.lastIndexOf('.');
        if (separator < 0 || separator == classPath.length() - 1) {
            return classPath;
        }
        return classPath.substring(separator + 1);
    }

    private record ParsedTarget(String rawPath) {
    }

    private record ResolutionCandidate(Path file, Path moduleRoot) {
    }

    private record ResolvedTarget(Path file, Path moduleRoot, String className, Set<String> methodNames, String rawTarget) {
    }

    private record TargetResolution(List<ResolvedTarget> resolvedTargets) {
        static TargetResolution empty() {
            return new TargetResolution(List.of());
        }

        boolean hasResolvedFiles() {
            return resolvedTargets != null && !resolvedTargets.isEmpty();
        }
    }
}
