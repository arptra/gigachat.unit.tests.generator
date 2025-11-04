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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class JavaProjectScanner {

    private static final List<String> DEFAULT_IMPORTS = List.of(
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.Assertions"
    );

    private final JavaParser javaParser = new JavaParser();

    public List<TestClassInfo> scan(AgentConfig config) throws IOException {
        Path projectPath = config.projectPath();
        List<Path> moduleRoots = determineModuleRoots(projectPath, config.includeModules());
        List<TestClassInfo> discoveredClasses = new ArrayList<>();
        for (Path moduleRoot : moduleRoots) {
            Path sourceRoot = resolveSourceRoot(moduleRoot);
            if (!Files.exists(sourceRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> {
                            try {
                                parseJavaFile(path, moduleRoot, config, discoveredClasses);
                            } catch (IOException ex) {
                                throw new java.io.UncheckedIOException(ex);
                            }
                        });
            } catch (java.io.UncheckedIOException ex) {
                throw (IOException) ex.getCause();
            }
        }
        return List.copyOf(discoveredClasses);
    }

    private void parseJavaFile(Path javaFile,
                               Path moduleRoot,
                               AgentConfig config,
                               List<TestClassInfo> collector) throws IOException {
        javaParser.parse(javaFile).getResult().ifPresentOrElse(
                compilationUnit -> handleCompilationUnit(compilationUnit, moduleRoot, config, collector),
                () -> System.err.println("Unable to parse file: " + javaFile)
        );
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
                .map(declaration -> createTestClassInfo(moduleRoot, packageName, declaration))
                .forEach(collector::add);
    }

    private TestClassInfo createTestClassInfo(Path moduleRoot,
                                              String packageName,
                                              ClassOrInterfaceDeclaration declaration) {
        Path targetRoot = moduleRoot.resolve(Path.of("src", "test", "java"));
        Path packagePath = packageName.isBlank()
                ? targetRoot
                : targetRoot.resolve(Path.of(packageName.replace('.', '/')));

        List<TestMethodInfo> methods = declaration.getMethods().stream()
                .filter(method -> !method.isPrivate())
                .map(method -> createTestMethodInfo(declaration.getNameAsString(), method))
                .collect(Collectors.toCollection(ArrayList::new));

        Set<String> imports = new LinkedHashSet<>(DEFAULT_IMPORTS);

        return new TestClassInfo(
                declaration.getNameAsString() + "Test",
                packagePath,
                List.copyOf(imports),
                List.copyOf(methods)
        );
    }

    private TestMethodInfo createTestMethodInfo(String className, MethodDeclaration methodDeclaration) {
        String testMethodName = methodDeclaration.getNameAsString() + "Test";
        String signature = "public void " + testMethodName + "()";
        String body = """
                // TODO: implement test scenario for %s#%s
                throw new UnsupportedOperationException("Not implemented yet");
                """.formatted(className, methodDeclaration.getNameAsString());
        return new TestMethodInfo(signature, "void", body);
    }

    private boolean shouldInclude(ClassOrInterfaceDeclaration declaration,
                                  String packageName,
                                  AgentConfig config) {
        if (config.scanWholeProject()) {
            return true;
        }
        List<String> includeClasses = config.includeClasses();
        if (includeClasses == null || includeClasses.isEmpty()) {
            return true;
        }
        String simpleName = declaration.getNameAsString();
        String qualifiedName = packageName.isBlank() ? simpleName : packageName + '.' + simpleName;
        return includeClasses.stream()
                .map(candidate -> candidate.replace('/', '.'))
                .map(candidate -> candidate.endsWith(".java") ? candidate.substring(0, candidate.length() - 5) : candidate)
                .anyMatch(candidate -> candidate.equals(simpleName) || candidate.equalsIgnoreCase(simpleName)
                        || candidate.equals(qualifiedName) || candidate.equalsIgnoreCase(qualifiedName));
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
