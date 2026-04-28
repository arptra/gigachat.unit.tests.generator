package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds a skeleton prompt describing a single method that requires a generated test.
 */
public class SkeletonPromptBuilder {

    public String build(TestClassInfo classInfo, TestMethodInfo methodInfo) {
        Objects.requireNonNull(classInfo, "classInfo");
        Objects.requireNonNull(methodInfo, "methodInfo");
        List<String> imports = classInfo.getImports();
        String importsJson = imports.stream()
                .map(importEntry -> "\"" + escape(importEntry) + "\"")
                .collect(Collectors.joining(", "));
        return "{\n"
                + "  \"testClass\": \"" + escape(classInfo.getTestClassName()) + "\",\n"
                + "  \"originalClass\": \"" + escape(classInfo.getClassName()) + "\",\n"
                + "  \"packageName\": \"" + escape(resolvePackageName(classInfo, methodInfo)) + "\",\n"
                + "  \"originalClassFqcn\": \"" + escape(resolveOriginalClassFqcn(classInfo, methodInfo)) + "\",\n"
                + "  \"methodSignature\": \"" + escape(methodInfo.getSignature()) + "\",\n"
                + "  \"context\": \"" + escape("Generate a focused unit test for the specified method") + "\",\n"
                + "  \"sourceSnippet\": \"" + escape(resolveSourceSnippet(methodInfo)) + "\",\n"
                + "  \"imports\": [" + importsJson + "]\n"
                + "}";
    }

    private String resolvePackageName(TestClassInfo classInfo, TestMethodInfo methodInfo) {
        if (methodInfo != null && methodInfo.getDeclaration() != null) {
            String fromDeclaration = methodInfo.getDeclaration()
                    .findCompilationUnit()
                    .flatMap(compilationUnit -> compilationUnit.getPackageDeclaration().map(packageDeclaration -> packageDeclaration.getNameAsString()))
                    .orElse("");
            if (!fromDeclaration.isBlank()) {
                return fromDeclaration;
            }
        }
        if (classInfo == null || classInfo.getTargetPath() == null) {
            return "";
        }
        String normalized = classInfo.getTargetPath().toString().replace('\\', '/');
        String marker = "/src/test/java/";
        int markerIndex = normalized.indexOf(marker);
        if (markerIndex < 0) {
            return "";
        }
        String relative = normalized.substring(markerIndex + marker.length());
        int slashIndex = relative.lastIndexOf('/');
        if (slashIndex < 0) {
            return "";
        }
        return relative.substring(0, slashIndex).replace('/', '.');
    }

    private String resolveOriginalClassFqcn(TestClassInfo classInfo, TestMethodInfo methodInfo) {
        if (classInfo == null) {
            return "";
        }
        String packageName = resolvePackageName(classInfo, methodInfo);
        if (packageName.isBlank()) {
            return classInfo.getClassName();
        }
        return packageName + "." + classInfo.getClassName();
    }

    private String resolveSourceSnippet(TestMethodInfo methodInfo) {
        if (methodInfo == null) {
            return "";
        }
        if (methodInfo.getDeclaration() != null) {
            return methodInfo.getDeclaration().toString();
        }
        if (methodInfo.getBody() == null || methodInfo.getBody().isBlank()) {
            return methodInfo.getSignature();
        }
        return methodInfo.getSignature() + " " + methodInfo.getBody();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
