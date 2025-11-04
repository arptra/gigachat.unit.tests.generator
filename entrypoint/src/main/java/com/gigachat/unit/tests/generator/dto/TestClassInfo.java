package com.gigachat.unit.tests.generator.dto;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class TestClassInfo {
    private final String className;
    private final Path targetPath;
    private final List<String> imports;
    private final List<TestMethodInfo> methods;

    public TestClassInfo(String className,
                         Path targetPath,
                         List<String> imports,
                         List<TestMethodInfo> methods) {
        this.className = Objects.requireNonNull(className, "className").trim();
        this.targetPath = Objects.requireNonNull(targetPath, "targetPath").toAbsolutePath().normalize();
        this.imports = normaliseImports(imports);
        this.methods = normaliseMethods(methods);
    }

    public String getClassName() {
        return className;
    }

    public Path getTargetPath() {
        return targetPath;
    }

    public List<String> getImports() {
        return imports;
    }

    public List<TestMethodInfo> getMethods() {
        return methods;
    }

    public boolean hasMethods() {
        return !methods.isEmpty();
    }

    public Path resolveTestFile() {
        return targetPath.resolve(className + ".java");
    }

    private List<String> normaliseImports(List<String> rawImports) {
        if (rawImports == null || rawImports.isEmpty()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        for (String entry : rawImports) {
            if (entry != null && !entry.isBlank()) {
                list.add(entry.trim());
            }
        }
        return Collections.unmodifiableList(list);
    }

    private List<TestMethodInfo> normaliseMethods(List<TestMethodInfo> rawMethods) {
        if (rawMethods == null || rawMethods.isEmpty()) {
            return List.of();
        }
        List<TestMethodInfo> list = new ArrayList<>(rawMethods);
        return Collections.unmodifiableList(list);
    }
}
