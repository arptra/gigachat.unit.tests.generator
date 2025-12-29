package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Detects whether a generated test method already exists to avoid duplicate generation.
 */
public class ExistingTestDetector {

    /**
     * Returns {@code true} when the given test method already exists inside the resolved test class file.
     */
    public boolean isTestMethodPresent(TestClassInfo classInfo, TestMethodInfo methodInfo) {
        Path testFile = classInfo.resolveTestFile();
        if (!Files.exists(testFile)) {
            return false;
        }
        String methodName = extractMethodName(methodInfo.getSignature());
        if (methodName == null || methodName.isBlank()) {
            return false;
        }
        try {
            CompilationUnit compilationUnit = StaticJavaParser.parse(testFile);
            Optional<ClassOrInterfaceDeclaration> testClass = compilationUnit.getClassByName(classInfo.getTestClassName());
            if (testClass.isEmpty()) {
                return false;
            }
            return testClass.get().getMethods().stream()
                    .map(MethodDeclaration::getNameAsString)
                    .anyMatch(existingName -> existingName.equals(methodName));
        } catch (IOException | ParseProblemException ignored) {
            return false;
        }
    }

    private String extractMethodName(String signature) {
        if (signature == null || signature.isBlank()) {
            return null;
        }
        int parenIndex = signature.indexOf('(');
        String candidate = parenIndex >= 0 ? signature.substring(0, parenIndex) : signature;
        candidate = candidate.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        if (candidate.contains(" ")) {
            String[] parts = candidate.split("\\s+");
            candidate = parts[parts.length - 1];
        }
        return candidate.isEmpty() ? null : candidate;
    }
}
