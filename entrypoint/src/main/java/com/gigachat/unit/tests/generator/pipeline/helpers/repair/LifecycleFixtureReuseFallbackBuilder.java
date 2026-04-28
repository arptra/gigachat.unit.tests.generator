package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds a bounded fallback for E112 lifecycle conflicts by reusing the existing generated fixture
 * and dropping only the duplicate lifecycle helper from the incoming snippet when that helper is
 * fixture-only and the test method already works against fields present in the existing class.
 */
public final class LifecycleFixtureReuseFallbackBuilder {

    private static final Set<String> ALLOWED_FIXTURE_CALLS = Set.of(
            "mock",
            "spy",
            "openmocks"
    );

    private final PipelineLogger logger;

    public LifecycleFixtureReuseFallbackBuilder(PipelineLogger logger) {
        this.logger = logger;
    }

    public GeneratedTestSnippet build(TestClassInfo classInfo,
                                      GeneratedTestSnippet snippet,
                                      String validationMessage) {
        if (classInfo == null || snippet == null || validationMessage == null || !validationMessage.contains("E112")) {
            return null;
        }
        Path targetPath = classInfo.getTargetPath();
        if (targetPath == null || !Files.isRegularFile(targetPath)) {
            logger.info("Skipping deterministic lifecycle-fixture reuse fallback because no existing generated test class is available");
            return null;
        }
        if (snippet.methodBody() == null || snippet.methodBody().isBlank()) {
            logger.info("Skipping deterministic lifecycle-fixture reuse fallback because the snippet has no method body");
            return null;
        }
        List<String> preservedHelpers = filterPreservedHelpers(snippet);
        if (preservedHelpers == null) {
            logger.info("Skipping deterministic lifecycle-fixture reuse fallback because the generated lifecycle helper is not fixture-only");
            return null;
        }
        Set<String> existingFields = readExistingFieldNames(targetPath);
        if (existingFields.isEmpty()) {
            logger.info("Skipping deterministic lifecycle-fixture reuse fallback because the existing generated test class exposes no reusable fields");
            return null;
        }
        Set<String> referencedIncomingFields = findReferencedIncomingFieldNames(snippet, preservedHelpers);
        if (referencedIncomingFields.isEmpty()) {
            logger.info("Skipping deterministic lifecycle-fixture reuse fallback because the generated test method does not reference incoming fixture fields");
            return null;
        }
        if (!existingFields.containsAll(referencedIncomingFields)) {
            LinkedHashSet<String> missingFields = new LinkedHashSet<>(referencedIncomingFields);
            missingFields.removeAll(existingFields);
            logger.info("Skipping deterministic lifecycle-fixture reuse fallback because existing test class is missing fields "
                    + missingFields);
            return null;
        }
        logger.info("Built deterministic lifecycle-fixture reuse fallback for " + snippet.methodName()
                + " using existing fields " + referencedIncomingFields);
        return new GeneratedTestSnippet(
                snippet.className(),
                snippet.methodName(),
                snippet.methodBody(),
                snippet.imports(),
                List.of(),
                List.of(),
                preservedHelpers,
                ""
        );
    }

    private List<String> filterPreservedHelpers(GeneratedTestSnippet snippet) {
        List<String> helperMethods = snippet.helperMethods();
        if (helperMethods == null || helperMethods.isEmpty()) {
            return null;
        }
        boolean foundLifecycleHelper = false;
        List<String> preservedHelpers = new java.util.ArrayList<>();
        for (String helperSource : helperMethods) {
            if (helperSource == null || helperSource.isBlank()) {
                continue;
            }
            MethodDeclaration method = parseMethod(helperSource);
            if (method == null) {
                return null;
            }
            if (!isLifecycleHelper(method)) {
                preservedHelpers.add(helperSource);
                continue;
            }
            foundLifecycleHelper = true;
            if (!isFixtureOnlyHelper(method)) {
                return null;
            }
        }
        return foundLifecycleHelper ? List.copyOf(preservedHelpers) : null;
    }

    private MethodDeclaration parseMethod(String helperSource) {
        try {
            return StaticJavaParser.parseBodyDeclaration(helperSource.trim()).asMethodDeclaration();
        } catch (ParseProblemException exception) {
            logger.warn("Unable to parse generated lifecycle helper for deterministic E112 fallback: "
                    + exception.getMessage());
            return null;
        }
    }

    private boolean isLifecycleHelper(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .map(annotation -> annotation.getNameAsString())
                .anyMatch(name -> "BeforeEach".equals(name)
                        || "BeforeAll".equals(name)
                        || "AfterEach".equals(name)
                        || "AfterAll".equals(name));
    }

    private boolean isFixtureOnlyHelper(MethodDeclaration method) {
        return method.findAll(MethodCallExpr.class).stream()
                .map(MethodCallExpr::getNameAsString)
                .map(name -> name == null ? "" : name.toLowerCase(Locale.ROOT))
                .allMatch(ALLOWED_FIXTURE_CALLS::contains);
    }

    private Set<String> readExistingFieldNames(Path targetPath) {
        LinkedHashSet<String> fieldNames = new LinkedHashSet<>();
        try {
            String source = Files.readString(targetPath, StandardCharsets.UTF_8);
            if (source.isBlank()) {
                return fieldNames;
            }
            CompilationUnit unit = StaticJavaParser.parse(source);
            unit.findAll(FieldDeclaration.class).forEach(field ->
                    field.getVariables().forEach(variable -> fieldNames.add(variable.getNameAsString())));
        } catch (IOException | ParseProblemException exception) {
            logger.warn("Unable to read existing generated test class for deterministic E112 fallback: "
                    + targetPath + " -> " + exception.getMessage());
        }
        return fieldNames;
    }

    private Set<String> findReferencedIncomingFieldNames(GeneratedTestSnippet snippet,
                                                         List<String> preservedHelpers) {
        LinkedHashSet<String> incomingFieldNames = new LinkedHashSet<>();
        if (snippet.fieldDeclarations() != null) {
            for (String declarationSource : snippet.fieldDeclarations()) {
                if (declarationSource == null || declarationSource.isBlank()) {
                    continue;
                }
                try {
                    FieldDeclaration field = StaticJavaParser.parseBodyDeclaration(declarationSource.trim()).asFieldDeclaration();
                    field.getVariables().forEach(variable -> incomingFieldNames.add(variable.getNameAsString()));
                } catch (Exception ignored) {
                    // best-effort only
                }
            }
        }
        if (incomingFieldNames.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> referenced = new LinkedHashSet<>();
        collectReferencedIncomingFieldNames(snippet.methodBody(), incomingFieldNames, referenced);
        if (preservedHelpers != null) {
            for (String helperSource : preservedHelpers) {
                collectReferencedIncomingFieldNames(helperSource, incomingFieldNames, referenced);
            }
        }
        return referenced;
    }

    private void collectReferencedIncomingFieldNames(String methodSource,
                                                     Set<String> incomingFieldNames,
                                                     Set<String> referenced) {
        if (methodSource == null || methodSource.isBlank() || incomingFieldNames == null || incomingFieldNames.isEmpty()) {
            return;
        }
        MethodDeclaration method;
        try {
            method = StaticJavaParser.parseBodyDeclaration(methodSource.trim()).asMethodDeclaration();
        } catch (Exception exception) {
            logger.warn("Unable to parse generated test method for deterministic E112 fallback: "
                    + exception.getMessage());
            return;
        }
        LinkedHashSet<String> localNames = new LinkedHashSet<>();
        method.getParameters().forEach(parameter -> localNames.add(parameter.getNameAsString()));
        method.findAll(VariableDeclarator.class).stream()
                .map(VariableDeclarator::getNameAsString)
                .forEach(localNames::add);
        method.findAll(NameExpr.class).stream()
                .map(NameExpr::getNameAsString)
                .filter(incomingFieldNames::contains)
                .filter(name -> !localNames.contains(name))
                .forEach(referenced::add);
    }
}
