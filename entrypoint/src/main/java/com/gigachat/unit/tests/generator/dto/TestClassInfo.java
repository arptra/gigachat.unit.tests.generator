package com.gigachat.unit.tests.generator.dto;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class TestClassInfo {
    private final String className;
    private final String testClassName;
    private final Path targetPath;
    private final List<String> imports;
    private final List<TestMethodInfo> methods;
    private final ClassMetadata classMetadata;

    public TestClassInfo(String className,
                         String testClassName,
                         Path targetPath,
                         List<String> imports,
                         List<TestMethodInfo> methods) {
        this(className, testClassName, targetPath, imports, methods, null);
    }

    public TestClassInfo(String className,
                         String testClassName,
                         Path targetPath,
                         List<String> imports,
                         List<TestMethodInfo> methods,
                         ClassMetadata classMetadata) {
        this.className = Objects.requireNonNull(className, "className").trim();
        this.testClassName = Objects.requireNonNull(testClassName, "testClassName").trim();
        this.targetPath = Objects.requireNonNull(targetPath, "targetPath").toAbsolutePath().normalize();
        this.imports = normaliseImports(imports);
        this.methods = normaliseMethods(methods);
        this.classMetadata = classMetadata == null ? new ClassMetadata(this.className, List.of()) : classMetadata;
    }

    public String getClassName() {
        return className;
    }

    public String getTestClassName() {
        return testClassName;
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

    public ClassMetadata getClassMetadata() {
        return classMetadata;
    }

    public boolean hasMethods() {
        return !methods.isEmpty();
    }

    public Path resolveTestFile() {
        return targetPath;
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
