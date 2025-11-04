package com.gigachat.unit.tests.generator.dto;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record TestClassInfo(
        String className,
        Path targetPath,
        List<String> imports,
        List<TestMethodInfo> methods
) {

    public TestClassInfo {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(targetPath, "targetPath");
        className = className.trim();
        targetPath = targetPath.toAbsolutePath().normalize();
        imports = imports == null ? List.of() : List.copyOf(imports);
        methods = methods == null ? List.of() : List.copyOf(methods);
    }

    public boolean hasMethods() {
        return !methods.isEmpty();
    }

    public Path resolveTestFile() {
        return targetPath.resolve(className + ".java");
    }
}
