package com.gigachat.unit.tests.generator.scanner;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JavaProjectScanner {
    private final JavaParser javaParser;

    public JavaProjectScanner() {
        this(new JavaParser());
    }

    public JavaProjectScanner(JavaParser javaParser) {
        this.javaParser = Objects.requireNonNull(javaParser, "javaParser");
    }

    public List<TestClassInfo> scan(AgentConfig config) throws IOException {
        Path projectPath = config.getProjectPath();
        List<Path> moduleRoots = determineModuleRoots(projectPath, config.getIncludeModules());
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

    private void parseJavaFile(Path javaFile,
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

    private void handleCompilationUnit(CompilationUnit compilationUnit,
                                       Path moduleRoot,
                                       AgentConfig config,
                                       List<TestClassInfo> collector) {
        String packageName = compilationUnit.getPackageDeclaration()
                .map(declaration -> declaration.getName().asString())
                .orElse("");
        compilationUnit.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(declaration -> !declaration.isInterface())
                .filter(declaration -> shouldInclude(declaration, packageName, config))
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

        return new TestClassInfo(
                declaration.getNameAsString(),
                testClassName,
                targetFile,
                List.copyOf(imports),
                List.copyOf(methods)
        );
    }

    private TestMethodInfo createTestMethodInfo(MethodDeclaration methodDeclaration) {
        String signature = methodDeclaration.getDeclarationAsString(true, true, true);
        String returnType = methodDeclaration.getType().asString();
        String body = methodDeclaration.getBody()
                .map(Object::toString)
                .orElse("");
        return new TestMethodInfo(signature, returnType, body, methodDeclaration.clone());
    }

    private boolean shouldInclude(ClassOrInterfaceDeclaration declaration,
                                  String packageName,
                                  AgentConfig config) {
        List<String> targetClasses = config.getTargetClasses();
        if (targetClasses != null && !targetClasses.isEmpty()) {
            return matchesCandidates(targetClasses, packageName, declaration.getNameAsString());
        }
        if (config.isScanWholeProject()) {
            return true;
        }
        List<String> includeClasses = config.getIncludeClasses();
        if (includeClasses == null || includeClasses.isEmpty()) {
            return true;
        }
        return matchesCandidates(includeClasses, packageName, declaration.getNameAsString());
    }

    private boolean matchesCandidates(List<String> candidates, String packageName, String simpleName) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        String qualifiedName = packageName.isBlank() ? simpleName : packageName + '.' + simpleName;
        String testName = simpleName.endsWith("Test") ? simpleName : simpleName + "Test";
        String qualifiedTestName = packageName.isBlank() ? testName : packageName + '.' + testName;
        for (String rawCandidate : candidates) {
            String candidate = normaliseCandidate(rawCandidate);
            if (candidate.isEmpty()) {
                continue;
            }
            String candidateSimple = candidate.contains(".")
                    ? candidate.substring(candidate.lastIndexOf('.') + 1)
                    : candidate;
            if (equalsName(candidate, qualifiedName) || equalsName(candidateSimple, simpleName)) {
                return true;
            }
            if (equalsName(candidate, qualifiedTestName) || equalsName(candidateSimple, testName)) {
                return true;
            }
        }
        return false;
    }

    private String normaliseCandidate(String candidate) {
        if (candidate == null) {
            return "";
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String normalised = trimmed.replace('/', '.');
        if (normalised.endsWith(".java")) {
            normalised = normalised.substring(0, normalised.length() - 5);
        }
        return normalised;
    }

    private boolean equalsName(String left, String right) {
        return left.equals(right) || left.equalsIgnoreCase(right);
    }

    private List<Path> determineModuleRoots(Path projectPath, List<String> includeModules) {
        if (includeModules == null || includeModules.isEmpty()) {
            return List.of(projectPath);
        }
        List<Path> modules = new ArrayList<>();
        for (String module : includeModules) {
            Path modulePath = projectPath.resolve(module);
            if (Files.exists(modulePath)) {
                modules.add(modulePath);
            }
        }
        return modules.isEmpty() ? List.of(projectPath) : List.copyOf(modules);
    }

    private Path resolveSourceRoot(Path moduleRoot) {
        Path mainSource = moduleRoot.resolve(Path.of("src", "main", "java"));
        if (Files.exists(mainSource)) {
            return mainSource;
        }
        return moduleRoot;
    }
}
