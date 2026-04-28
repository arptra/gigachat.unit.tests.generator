package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.pipeline.helpers.JavaImportSanitizer;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applies heuristic auto-corrections to generated snippets before validation
 * so that obvious internal field mutations are rewritten into safe public API
 * interactions.
 */
public class AutoCorrectionStage {

    public GeneratedTestSnippet apply(GeneratedTestSnippet snippet) {
        if (snippet == null) {
            return null;
        }
        String correctedBody = applyRules(snippet.methodBody());
        List<String> correctedImports = JavaImportSanitizer.sanitizeImports(snippet.imports());
        List<String> correctedHelpers = sanitizeHelperMethods(snippet.helperMethods(),
                snippet.fieldDeclarations(),
                snippet.fullClassSource());
        String correctedSource = sanitizeFullClassSource(JavaImportSanitizer.sanitizeSourceImports(applyRules(snippet.fullClassSource())),
                snippet.methodName(),
                snippet.fieldDeclarations());
        if (equalsSafe(snippet.methodBody(), correctedBody)
                && equalsSafeList(snippet.imports(), correctedImports)
                && equalsSafeList(snippet.helperMethods(), correctedHelpers)
                && equalsSafe(snippet.fullClassSource(), correctedSource)) {
            return snippet;
        }
        return new GeneratedTestSnippet(snippet.className(),
                snippet.methodName(),
                correctedBody,
                correctedImports,
                snippet.classAnnotations(),
                snippet.fieldDeclarations(),
                correctedHelpers,
                correctedSource);
    }

    private List<String> sanitizeHelperMethods(List<String> helperMethods,
                                               List<String> fieldDeclarations,
                                               String fullClassSource) {
        if (helperMethods == null || helperMethods.isEmpty()) {
            return helperMethods;
        }
        Set<String> mockFields = collectMockFieldNames(fieldDeclarations, fullClassSource);
        if (mockFields.isEmpty()) {
            return helperMethods;
        }
        boolean mockLifecycleConfigured = hasMockitoLifecycle(fullClassSource);
        return helperMethods.stream()
                .map(helper -> sanitizeHelperMethod(helper, mockFields, mockLifecycleConfigured))
                .collect(Collectors.toList());
    }

    private String sanitizeFullClassSource(String source,
                                           String targetMethodName,
                                           List<String> fieldDeclarations) {
        if (source == null || source.isBlank() || targetMethodName == null || targetMethodName.isBlank()) {
            return source;
        }
        try {
            CompilationUnit unit = StaticJavaParser.parse(source);
            ClassOrInterfaceDeclaration declaration = unit.findFirst(ClassOrInterfaceDeclaration.class).orElse(null);
            if (declaration == null) {
                return source;
            }
            Set<String> mockFields = collectMockFieldNames(fieldDeclarations, source);
            boolean mockLifecycleConfigured = hasMockitoLifecycle(source);
            if (!mockFields.isEmpty() && mockLifecycleConfigured) {
                declaration.findAll(MethodDeclaration.class).stream()
                        .filter(this::isLifecycleHelper)
                        .forEach(method -> sanitizeMockFieldAssignments(method, mockFields));
            }
            declaration.findAll(MethodDeclaration.class).stream()
                    .filter(this::isTestMethod)
                    .filter(method -> !targetMethodName.equals(method.getNameAsString()))
                    .forEach(MethodDeclaration::remove);
            return unit.toString();
        } catch (Exception ignored) {
            return source;
        }
    }

    private boolean isTestMethod(MethodDeclaration declaration) {
        return declaration.getAnnotations().stream()
                .anyMatch(annotation -> "Test".equals(annotation.getNameAsString()));
    }

    private boolean isLifecycleHelper(MethodDeclaration declaration) {
        return declaration.getAnnotations().stream()
                .anyMatch(annotation -> {
                    String name = annotation.getNameAsString();
                    return "BeforeEach".equals(name)
                            || "BeforeAll".equals(name)
                            || "AfterEach".equals(name)
                            || "AfterAll".equals(name);
                });
    }

    private Set<String> collectMockFieldNames(List<String> fieldDeclarations, String fullClassSource) {
        LinkedHashSet<String> mockFields = new LinkedHashSet<>();
        if (fieldDeclarations != null) {
            for (String fieldDeclaration : fieldDeclarations) {
                collectMockFieldNamesFromDeclaration(fieldDeclaration, mockFields);
            }
        }
        if (fullClassSource != null && !fullClassSource.isBlank()) {
            try {
                CompilationUnit unit = StaticJavaParser.parse(fullClassSource);
                unit.findAll(FieldDeclaration.class).stream()
                        .filter(this::isMockitoManagedField)
                        .forEach(field -> field.getVariables()
                                .forEach(variable -> mockFields.add(variable.getNameAsString())));
            } catch (Exception ignored) {
                // best-effort normalization only
            }
        }
        return mockFields;
    }

    private void collectMockFieldNamesFromDeclaration(String rawField, Set<String> mockFields) {
        if (rawField == null || rawField.isBlank()) {
            return;
        }
        try {
            FieldDeclaration field = StaticJavaParser.parseBodyDeclaration(rawField.trim()).asFieldDeclaration();
            if (!isMockitoManagedField(field)) {
                return;
            }
            field.getVariables().forEach(variable -> mockFields.add(variable.getNameAsString()));
        } catch (Exception ignored) {
            // best-effort normalization only
        }
    }

    private boolean isMockitoManagedField(FieldDeclaration field) {
        return field.getAnnotations().stream()
                .map(annotation -> annotation.getNameAsString())
                .anyMatch(name -> "Mock".equals(name) || "Spy".equals(name));
    }

    private String sanitizeHelperMethod(String helperMethod,
                                        Set<String> mockFields,
                                        boolean mockLifecycleConfigured) {
        if (helperMethod == null || helperMethod.isBlank() || mockFields.isEmpty() || !mockLifecycleConfigured) {
            return helperMethod;
        }
        try {
            MethodDeclaration method = StaticJavaParser.parseBodyDeclaration(helperMethod.trim()).asMethodDeclaration();
            if (!isLifecycleHelper(method)) {
                return helperMethod;
            }
            sanitizeMockFieldAssignments(method, mockFields);
            return method.toString();
        } catch (Exception ignored) {
            return helperMethod;
        }
    }

    private boolean hasMockitoLifecycle(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        return source.contains("MockitoAnnotations.openMocks(this)")
                || source.contains("MockitoExtension");
    }

    private void sanitizeMockFieldAssignments(MethodDeclaration method, Set<String> mockFields) {
        method.findAll(ExpressionStmt.class).stream()
                .filter(statement -> isRealObjectAssignmentToMockField(statement, mockFields))
                .toList()
                .forEach(ExpressionStmt::remove);
    }

    private boolean isRealObjectAssignmentToMockField(ExpressionStmt statement, Set<String> mockFields) {
        if (statement == null || mockFields == null || mockFields.isEmpty()) {
            return false;
        }
        if (!statement.getExpression().isAssignExpr()) {
            return false;
        }
        AssignExpr assignExpr = statement.getExpression().asAssignExpr();
        String target = assignedFieldName(assignExpr.getTarget());
        if (target == null || !mockFields.contains(target)) {
            return false;
        }
        return assignExpr.getValue() instanceof ObjectCreationExpr;
    }

    private String assignedFieldName(Expression expression) {
        if (expression == null) {
            return null;
        }
        if (expression instanceof NameExpr nameExpr) {
            return nameExpr.getNameAsString();
        }
        if (expression instanceof FieldAccessExpr fieldAccessExpr
                && fieldAccessExpr.getScope() instanceof com.github.javaparser.ast.expr.ThisExpr) {
            return fieldAccessExpr.getNameAsString();
        }
        return null;
    }

    private String applyRules(String code) {
        if (code == null || code.isEmpty()) {
            return code;
        }
        String updated = code;
        if (updated.contains("repository.users")) {
            updated = updated.replace("repository.users = users;",
                    "for (User u : users) { repository.save(u); }");
        }
        return updated;
    }

    private boolean equalsSafe(String original, String updated) {
        if (original == null) {
            return updated == null;
        }
        return original.equals(updated);
    }

    private boolean equalsSafeList(List<String> original, List<String> updated) {
        if (original == null) {
            return updated == null;
        }
        return original.equals(updated);
    }
}
