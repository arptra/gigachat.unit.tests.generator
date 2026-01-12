package com.gigachat.unit.tests.generator.scanner;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.dto.ClassMetadata;
import com.gigachat.unit.tests.generator.dto.FieldMetadata;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.util.TargetClassMatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        Path projectPath = config.getProjectPath();
        List<Path> moduleRoots = determineModuleRoots(config);
        List<TestClassInfo> discoveredClasses = new ArrayList<>();
        for (Path moduleRoot : moduleRoots) {
            Path sourceRoot = resolveSourceRoot(moduleRoot);
            if (!Files.exists(sourceRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> parseJavaFile(path, moduleRoot, config, discoveredClasses));
            } catch (java.io.UncheckedIOException ex) {
                throw (IOException) ex.getCause();
            }
        }
        return List.copyOf(discoveredClasses);
    }

    public void scanSequentially(AgentConfig config, Consumer<List<TestClassInfo>> perFileConsumer) throws IOException {
        Objects.requireNonNull(perFileConsumer, "perFileConsumer");
        Path projectPath = config.getProjectPath();
        List<Path> moduleRoots = determineModuleRoots(config);
        for (Path moduleRoot : moduleRoots) {
            Path sourceRoot = resolveSourceRoot(moduleRoot);
            if (!Files.exists(sourceRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> parseAndEmit(path, moduleRoot, config, perFileConsumer));
            } catch (java.io.UncheckedIOException ex) {
                throw (IOException) ex.getCause();
            }
        }
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
}
