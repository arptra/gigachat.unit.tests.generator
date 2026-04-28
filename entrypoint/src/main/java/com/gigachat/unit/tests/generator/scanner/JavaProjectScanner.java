package com.gigachat.unit.tests.generator.scanner;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        Path projectPath = config.getProjectPath();
        List<Path> moduleRoots = determineModuleRoots(config);
        Set<Path> diffFiles = resolveDiffJavaFiles(config);
        List<TestClassInfo> discoveredClasses = new ArrayList<>();
        for (Path moduleRoot : moduleRoots) {
            Path sourceRoot = resolveSourceRoot(moduleRoot);
            if (!Files.exists(sourceRoot)) {
                continue;
            }
            ScanScope scope = collectJavaFiles(sourceRoot, config, diffFiles);
            if (scope.targetNarrowed()) {
                preRegisterSignatures(signatureFilesFor(scope, moduleRoot));
            }
            for (Path javaFile : scope.javaFiles()) {
                parseJavaFile(javaFile, moduleRoot, config, discoveredClasses);
            }
        }
        return List.copyOf(discoveredClasses);
    }

    public void scanSequentially(AgentConfig config, Consumer<List<TestClassInfo>> perFileConsumer) throws IOException {
        Objects.requireNonNull(perFileConsumer, "perFileConsumer");
        List<Path> moduleRoots = determineModuleRoots(config);
        Set<Path> diffFiles = resolveDiffJavaFiles(config);
        for (Path moduleRoot : moduleRoots) {
            Path sourceRoot = resolveSourceRoot(moduleRoot);
            if (!Files.exists(sourceRoot)) {
                continue;
            }
            ScanScope scope = collectJavaFiles(sourceRoot, config, diffFiles);
            preRegisterSignatures(signatureFilesFor(scope, moduleRoot));
            for (Path javaFile : scope.javaFiles()) {
                parseAndEmit(javaFile, moduleRoot, config, perFileConsumer);
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
                              Consumer<List<TestClassInfo>> perFileConsumer) {
        List<TestClassInfo> collector = new ArrayList<>();
        parseJavaFile(javaFile, moduleRoot, config, collector);
        if (!collector.isEmpty()) {
            perFileConsumer.accept(List.copyOf(collector));
        }
    }

    private ScanScope collectJavaFiles(Path sourceRoot,
                                       AgentConfig config,
                                       Set<Path> diffFiles) throws IOException {
        TargetFileSelection targetSelection = selectTargetFiles(sourceRoot, config, diffFiles);
        if (targetSelection.exactTargetMode()) {
            return new ScanScope(targetSelection.files(), true);
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            List<Path> javaFiles = files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> shouldProcessFile(path, diffFiles))
                    .sorted()
                    .toList();
            return new ScanScope(javaFiles, false);
        } catch (java.io.UncheckedIOException ex) {
            throw (IOException) ex.getCause();
        }
    }

    private TargetFileSelection selectTargetFiles(Path sourceRoot,
                                                  AgentConfig config,
                                                  Set<Path> diffFiles) throws IOException {
        if (!hasOnlyExactTargetClasses(config)) {
            return new TargetFileSelection(List.of(), false);
        }
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        LinkedHashSet<String> fileNamesToFind = new LinkedHashSet<>();
        boolean requiresFileNameSearch = false;
        for (String target : config.getTargetClasses()) {
            String simpleName = sourceSimpleName(target);
            if (!simpleName.isBlank()) {
                fileNamesToFind.add(simpleName + ".java");
            }
            java.util.Optional<Path> direct = directTargetFile(sourceRoot, target)
                    .filter(path -> shouldProcessFile(path, diffFiles));
            if (direct.isPresent()) {
                files.add(direct.get());
            } else {
                requiresFileNameSearch = true;
            }
            if (!isQualifiedTarget(target)) {
                requiresFileNameSearch = true;
            }
        }
        if (requiresFileNameSearch && !fileNamesToFind.isEmpty()) {
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> fileNamesToFind.contains(path.getFileName().toString()))
                        .filter(path -> shouldProcessFile(path, diffFiles))
                        .forEach(files::add);
            }
        }
        return new TargetFileSelection(sortedCopy(files), true);
    }

    private boolean hasOnlyExactTargetClasses(AgentConfig config) {
        if (config == null || config.getTargetClasses() == null || config.getTargetClasses().isEmpty()) {
            return false;
        }
        for (String target : config.getTargetClasses()) {
            if (target == null || target.isBlank()) {
                return false;
            }
            if (target.contains("*")) {
                return false;
            }
        }
        return true;
    }

    private boolean isQualifiedTarget(String rawTarget) {
        String normalized = normalizeTargetName(rawTarget);
        int lastDot = normalized.lastIndexOf('.');
        return lastDot > 0 && lastDot + 1 < normalized.length();
    }

    private java.util.Optional<Path> directTargetFile(Path sourceRoot, String rawTarget) {
        if (sourceRoot == null || rawTarget == null || rawTarget.isBlank()) {
            return java.util.Optional.empty();
        }
        String trimmed = rawTarget.trim();
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        if (looksLikePath(trimmed)) {
            Path rawPath = Path.of(trimmed);
            if (rawPath.isAbsolute()) {
                candidates.add(rawPath);
            } else {
                candidates.add(sourceRoot.resolve(rawPath));
                candidates.add(sourceRoot.getParent() == null ? rawPath : sourceRoot.getParent().resolve(rawPath));
            }
        }
        String normalized = normalizeTargetName(trimmed);
        int lastDot = normalized.lastIndexOf('.');
        if (lastDot > 0 && lastDot + 1 < normalized.length()) {
            String packagePart = normalized.substring(0, lastDot);
            String simpleName = stripTestSuffix(normalized.substring(lastDot + 1));
            if (!simpleName.isBlank()) {
                candidates.add(sourceRoot.resolve(packagePart.replace('.', '/')).resolve(simpleName + ".java"));
            }
        }
        return candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .findFirst();
    }

    private boolean looksLikePath(String target) {
        return target.endsWith(".java") || target.contains("/") || target.contains("\\");
    }

    private String sourceSimpleName(String rawTarget) {
        String normalized = normalizeTargetName(rawTarget);
        if (normalized.isBlank()) {
            return "";
        }
        int lastDot = normalized.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? normalized.substring(lastDot + 1) : normalized;
        return stripTestSuffix(simpleName);
    }

    private String normalizeTargetName(String rawTarget) {
        if (rawTarget == null) {
            return "";
        }
        String normalized = rawTarget.trim()
                .replace('\\', '.')
                .replace('/', '.');
        if (normalized.endsWith(".java")) {
            normalized = normalized.substring(0, normalized.length() - 5);
        }
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private String stripTestSuffix(String simpleName) {
        if (simpleName == null || simpleName.isBlank()) {
            return "";
        }
        String trimmed = simpleName.trim();
        if (trimmed.endsWith("Test") && trimmed.length() > "Test".length()) {
            return trimmed.substring(0, trimmed.length() - "Test".length());
        }
        return trimmed;
    }

    private List<Path> signatureFilesFor(ScanScope scope, Path moduleRoot) throws IOException {
        if (scope == null || scope.javaFiles().isEmpty()) {
            return List.of();
        }
        if (!scope.targetNarrowed()) {
            return scope.javaFiles();
        }
        LinkedHashSet<Path> signatureFiles = new LinkedHashSet<>(scope.javaFiles());
        Path sourceRoot = resolveSourceRoot(moduleRoot);
        for (Path javaFile : scope.javaFiles()) {
            signatureFiles.addAll(resolveDirectSupportFiles(javaFile, sourceRoot));
        }
        return sortedCopy(signatureFiles);
    }

    private List<Path> resolveDirectSupportFiles(Path javaFile, Path sourceRoot) throws IOException {
        if (javaFile == null || sourceRoot == null || !Files.exists(javaFile)) {
            return List.of();
        }
        SupportReferences references = collectSupportReferences(javaFile);
        LinkedHashSet<Path> supportFiles = new LinkedHashSet<>();
        for (String importedClass : references.importedClasses()) {
            sourceFileForQualifiedName(sourceRoot, importedClass).ifPresent(supportFiles::add);
        }
        for (String wildcardPackage : references.wildcardPackages()) {
            supportFiles.addAll(sourceFilesInPackage(sourceRoot, wildcardPackage));
        }
        String packageName = references.packageName();
        if (!packageName.isBlank()) {
            for (String simpleName : references.simpleTypeNames()) {
                sourceFileForQualifiedName(sourceRoot, packageName + "." + simpleName).ifPresent(supportFiles::add);
            }
        }
        supportFiles.remove(javaFile.toAbsolutePath().normalize());
        return sortedCopy(supportFiles);
    }

    private SupportReferences collectSupportReferences(Path javaFile) throws IOException {
        CompilationUnit compilationUnit = new JavaParser().parse(javaFile)
                .getResult()
                .orElse(null);
        if (compilationUnit == null) {
            return new SupportReferences("", Set.of(), Set.of(), Set.of());
        }
        String packageName = compilationUnit.getPackageDeclaration()
                .map(declaration -> declaration.getName().asString())
                .orElse("");
        LinkedHashSet<String> importedClasses = new LinkedHashSet<>();
        LinkedHashSet<String> wildcardPackages = new LinkedHashSet<>();
        for (ImportDeclaration importDeclaration : compilationUnit.getImports()) {
            if (importDeclaration == null || importDeclaration.isStatic()) {
                continue;
            }
            String importName = importDeclaration.getNameAsString();
            if (importName == null || importName.isBlank() || isStandardPackage(importName)) {
                continue;
            }
            if (importDeclaration.isAsterisk()) {
                wildcardPackages.add(importName);
            } else {
                importedClasses.add(importName);
            }
        }
        LinkedHashSet<String> simpleTypeNames = new LinkedHashSet<>();
        compilationUnit.findAll(ClassOrInterfaceType.class).forEach(type -> {
            String qualifiedName = stripTypeDecorations(type.getNameWithScope());
            addQualifiedTypeName(importedClasses, qualifiedName);
            addSimpleTypeName(simpleTypeNames, qualifiedName);
        });
        compilationUnit.findAll(MethodCallExpr.class).forEach(call ->
                call.getScope()
                        .filter(NameExpr.class::isInstance)
                        .map(NameExpr.class::cast)
                        .map(NameExpr::getNameAsString)
                        .ifPresent(name -> addSimpleTypeName(simpleTypeNames, name)));
        return new SupportReferences(packageName,
                Set.copyOf(importedClasses),
                Set.copyOf(wildcardPackages),
                Set.copyOf(simpleTypeNames));
    }

    private void addSimpleTypeName(Set<String> collector, String rawName) {
        if (collector == null || rawName == null || rawName.isBlank()) {
            return;
        }
        String simpleName = stripTypeDecorations(rawName);
        int lastDot = simpleName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < simpleName.length()) {
            simpleName = simpleName.substring(lastDot + 1);
        }
        if (simpleName.isBlank() || !Character.isUpperCase(simpleName.charAt(0))) {
            return;
        }
        if (isStandardSimpleType(simpleName)) {
            return;
        }
        collector.add(simpleName);
    }

    private void addQualifiedTypeName(Set<String> collector, String rawName) {
        if (collector == null || rawName == null || rawName.isBlank()) {
            return;
        }
        String qualifiedName = stripTypeDecorations(rawName);
        if (qualifiedName.isBlank() || !qualifiedName.contains(".") || isStandardPackage(qualifiedName)) {
            return;
        }
        collector.add(qualifiedName);
    }

    private String stripTypeDecorations(String rawType) {
        if (rawType == null) {
            return "";
        }
        String value = rawType.trim();
        int genericStart = value.indexOf('<');
        if (genericStart >= 0) {
            value = value.substring(0, genericStart);
        }
        int arrayStart = value.indexOf('[');
        if (arrayStart >= 0) {
            value = value.substring(0, arrayStart);
        }
        return value.trim();
    }

    private boolean isStandardPackage(String qualifiedName) {
        return qualifiedName.startsWith("java.")
                || qualifiedName.startsWith("javax.")
                || qualifiedName.startsWith("jakarta.")
                || qualifiedName.startsWith("org.junit.")
                || qualifiedName.startsWith("org.mockito.");
    }

    private boolean isStandardSimpleType(String simpleName) {
        return switch (simpleName) {
            case "String", "Object", "Boolean", "Integer", "Long", "Double", "Float", "Short", "Byte",
                    "Character", "Void", "List", "Set", "Map", "Optional", "Collection", "Collections",
                    "ArrayList", "LinkedList", "HashMap", "LinkedHashMap", "HashSet", "LinkedHashSet",
                    "Stream", "Collectors", "Instant", "LocalDate", "LocalDateTime", "BigDecimal",
                    "BigInteger" -> true;
            default -> false;
        };
    }

    private java.util.Optional<Path> sourceFileForQualifiedName(Path sourceRoot, String qualifiedName) {
        if (sourceRoot == null || qualifiedName == null || qualifiedName.isBlank()) {
            return java.util.Optional.empty();
        }
        Path candidate = sourceRoot.resolve(qualifiedName.trim().replace('.', '/') + ".java")
                .toAbsolutePath()
                .normalize();
        if (Files.isRegularFile(candidate)) {
            return java.util.Optional.of(candidate);
        }
        String candidateName = qualifiedName.trim();
        int dotIndex = candidateName.lastIndexOf('.');
        while (dotIndex > 0) {
            candidateName = candidateName.substring(0, dotIndex);
            candidate = sourceRoot.resolve(candidateName.replace('.', '/') + ".java")
                    .toAbsolutePath()
                    .normalize();
            if (Files.isRegularFile(candidate)) {
                return java.util.Optional.of(candidate);
            }
            dotIndex = candidateName.lastIndexOf('.');
        }
        return java.util.Optional.empty();
    }

    private List<Path> sourceFilesInPackage(Path sourceRoot, String packageName) throws IOException {
        if (sourceRoot == null || packageName == null || packageName.isBlank()) {
            return List.of();
        }
        Path packagePath = sourceRoot.resolve(packageName.trim().replace('.', '/'));
        if (!Files.isDirectory(packagePath)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(packagePath)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted()
                    .toList();
        }
    }

    private List<Path> sortedCopy(Set<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        return paths.stream()
                .filter(Objects::nonNull)
                .map(path -> path.toAbsolutePath().normalize())
                .sorted()
                .toList();
    }

    private void preRegisterSignatures(List<Path> javaFiles) {
        if (javaFiles == null || javaFiles.isEmpty()) {
            return;
        }
        if (javaFiles.size() == 1) {
            preRegisterSignaturesSequentially(javaFiles);
            return;
        }
        int workers = Math.min(javaFiles.size(), Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        workers = Math.min(workers, 8);
        if (workers <= 1) {
            preRegisterSignaturesSequentially(javaFiles);
            return;
        }
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<Void>> futures = new ArrayList<>(javaFiles.size());
        try {
            for (Path javaFile : javaFiles) {
                futures.add(executor.submit(() -> {
                    SignatureBatch batch = parseSignatureBatch(javaFile);
                    synchronized (methodRegistry) {
                        registerSignatureBatch(batch);
                    }
                    return null;
                }));
            }
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while pre-registering Java signatures", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException ioException) {
                throw new java.io.UncheckedIOException(ioException);
            }
            if (cause instanceof java.io.UncheckedIOException unchecked) {
                throw unchecked;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to pre-register Java signatures", cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private void preRegisterSignaturesSequentially(List<Path> javaFiles) {
        for (Path javaFile : javaFiles) {
            try {
                registerSignatureBatch(parseSignatureBatch(javaFile));
            } catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }
    }

    private SignatureBatch parseSignatureBatch(Path javaFile) throws IOException {
        SignatureBatch batch = new SignatureBatch();
        new JavaParser().parse(javaFile).getResult().ifPresent(compilationUnit ->
                compilationUnit.getTypes().stream()
                        .filter(ClassOrInterfaceDeclaration.class::isInstance)
                        .map(ClassOrInterfaceDeclaration.class::cast)
                        .filter(declaration -> !declaration.isInterface())
                        .forEach(declaration -> collectSignatures(declaration, batch)));
        return batch;
    }

    protected void parseJavaFile(Path javaFile,
                                 Path moduleRoot,
                                 AgentConfig config,
                                 List<TestClassInfo> collector) {
        try {
            javaParser.parse(javaFile).getResult().ifPresentOrElse(
                    compilationUnit -> handleCompilationUnit(compilationUnit, moduleRoot, config, collector),
                    () -> System.err.println("Unable to parse file: " + javaFile)
            );
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    protected void handleCompilationUnit(CompilationUnit compilationUnit,
                                         Path moduleRoot,
                                         AgentConfig config,
                                         List<TestClassInfo> collector) {
        String packageName = compilationUnit.getPackageDeclaration()
                .map(declaration -> declaration.getName().asString())
                .orElse("");
        List<ClassOrInterfaceDeclaration> declarations = compilationUnit.getTypes().stream()
                .filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(ClassOrInterfaceDeclaration.class::cast)
                .filter(declaration -> !declaration.isInterface())
                .toList();
        declarations.forEach(this::registerSignatures);
        declarations.stream()
                .filter(declaration -> shouldInclude(declaration, packageName, moduleRoot, config))
                .map(declaration -> createTestClassInfo(compilationUnit, moduleRoot, packageName, declaration))
                .forEach(collector::add);
    }

    private TestClassInfo createTestClassInfo(CompilationUnit compilationUnit,
                                              Path moduleRoot,
                                              String packageName,
                                              ClassOrInterfaceDeclaration declaration) {
        Path targetRoot = moduleRoot.resolve(Path.of("src", "test", "java"));
        Path packagePath = packageName.isBlank()
                ? targetRoot
                : targetRoot.resolve(Path.of(packageName.replace('.', '/')));
        String testClassName = declaration.getNameAsString() + "Test";
        Path targetFile = packagePath.resolve(testClassName + ".java");

        List<TestMethodInfo> methods = declaration.getMethods().stream()
                .filter(method -> !method.isPrivate())
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
        SignatureBatch batch = new SignatureBatch();
        collectSignatures(declaration, batch);
        registerSignatureBatch(batch);
    }

    private void collectSignatures(ClassOrInterfaceDeclaration declaration, SignatureBatch batch) {
        if (declaration == null || batch == null) {
            return;
        }
        String className = declaration.getNameAsString();
        collectConstructors(className, declaration.getConstructors(), batch);
        collectMethods(className, declaration.getMethods(), batch);
        declaration.getMembers().stream()
                .filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(ClassOrInterfaceDeclaration.class::cast)
                .filter(nested -> !nested.isInterface())
                .forEach(nested -> collectSignatures(nested, batch));
    }

    private void collectConstructors(String className,
                                     List<ConstructorDeclaration> constructors,
                                     SignatureBatch batch) {
        if (constructors == null || constructors.isEmpty()) {
            ConstructorMetadata metadata = new ConstructorMetadata(className + "()", List.of());
            batch.addConstructor(className, metadata);
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
            batch.addConstructor(className, metadata);
        }
    }

    private String buildConstructorSignature(String className, ConstructorDeclaration constructor) {
        return (className + formatParameters(constructor == null ? new NodeList<>() : constructor.getParameters())).trim();
    }

    private void collectMethods(String className, List<MethodDeclaration> methods, SignatureBatch batch) {
        if (methods == null || methods.isEmpty()) {
            return;
        }
        for (MethodDeclaration method : methods) {
            if (method.isPrivate() || !method.isPublic()) {
                continue;
            }
            String signature = method.getType().asString() + " "
                    + method.getNameAsString() + formatParameters(method.getParameters());
            batch.addMethod(className, signature);
        }
    }

    private void registerSignatureBatch(SignatureBatch batch) {
        if (batch == null) {
            return;
        }
        for (ConstructorSignature constructor : batch.constructors()) {
            methodRegistry.registerConstructor(constructor.className(), constructor.metadata());
        }
        for (MethodSignature method : batch.methods()) {
            methodRegistry.registerMethod(method.className(), method.signature());
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

    private record ScanScope(List<Path> javaFiles, boolean targetNarrowed) {
    }

    private record TargetFileSelection(List<Path> files, boolean exactTargetMode) {
    }

    private record SupportReferences(String packageName,
                                     Set<String> importedClasses,
                                     Set<String> wildcardPackages,
                                     Set<String> simpleTypeNames) {
    }

    private record ConstructorSignature(String className, ConstructorMetadata metadata) {
    }

    private record MethodSignature(String className, String signature) {
    }

    private static final class SignatureBatch {
        private final List<ConstructorSignature> constructors = new ArrayList<>();
        private final List<MethodSignature> methods = new ArrayList<>();

        void addConstructor(String className, ConstructorMetadata metadata) {
            constructors.add(new ConstructorSignature(className, metadata));
        }

        void addMethod(String className, String signature) {
            methods.add(new MethodSignature(className, signature));
        }

        List<ConstructorSignature> constructors() {
            return constructors;
        }

        List<MethodSignature> methods() {
            return methods;
        }
    }
}
