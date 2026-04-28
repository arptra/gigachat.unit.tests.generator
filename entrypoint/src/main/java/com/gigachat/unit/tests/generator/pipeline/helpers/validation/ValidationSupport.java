package com.gigachat.unit.tests.generator.pipeline.helpers.validation;

import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.testagent.entrypoint.pipeline.helpers.analyze.DependencyInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ValidationSupport {

    private ValidationSupport() {
    }

    static String simpleName(String type) {
        if (type == null) {
            return "";
        }
        String trimmed = type.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int genericStart = trimmed.indexOf('<');
        if (genericStart >= 0) {
            trimmed = trimmed.substring(0, genericStart);
        }
        int arrayIndex = trimmed.indexOf('[');
        if (arrayIndex >= 0) {
            trimmed = trimmed.substring(0, arrayIndex);
        }
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < trimmed.length()) {
            return trimmed.substring(lastDot + 1);
        }
        return trimmed;
    }

    static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    static String normalise(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    static String lowerCamel(String value) {
        String text = normalise(value);
        if (text.isBlank()) {
            return "";
        }
        if (text.length() == 1) {
            return text.toLowerCase(java.util.Locale.ROOT);
        }
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    static String extractMethodName(String signature) {
        if (signature == null || signature.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").matcher(signature);
        String methodName = "";
        while (matcher.find()) {
            methodName = matcher.group(1);
        }
        return methodName;
    }

    static boolean containsMockito(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        return source.contains("Mockito")
                || source.contains("org.mockito")
                || source.contains("import static org.mockito")
                || source.contains("@Mock");
    }

    static boolean isStandardLibraryType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        for (String standard : Analyze.STANDARD_TYPES) {
            if (matchesStandardLibraryType(type, standard)) {
                return true;
            }
        }
        return false;
    }

    static Map<String, String> collectVariableTypes(CompilationUnit compilationUnit,
                                                    Analyze.AnalysisSummary analysisSummary,
                                                    TestClassInfo classInfo) {
        Map<String, String> types = new LinkedHashMap<>();
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        if (targetContext != null && targetContext.instanceName() != null && !targetContext.instanceName().isBlank()) {
            types.put(targetContext.instanceName(), targetContext.className());
        }
        if (analysisSummary.methodAnalysis() != null) {
            for (DependencyInfo dependency : analysisSummary.methodAnalysis().dependencies()) {
                if (dependency == null) {
                    continue;
                }
                String className = simpleName(dependency.className());
                if (!className.isBlank()) {
                    types.putIfAbsent(className, className);
                }
            }
        }
        analysisSummary.availableMethods().keySet().forEach(className -> types.putIfAbsent(className, className));
        analysisSummary.availableConstructors().keySet().forEach(className -> types.putIfAbsent(className, className));
        compilationUnit.findAll(VariableDeclarator.class).forEach(declarator -> {
            String name = declarator.getNameAsString();
            if (name == null || name.isBlank()) {
                return;
            }
            String type = declarator.getType().asString();
            if ("var".equals(type)) {
                type = inferTypeFromInitializer(declarator.getInitializer());
            }
            types.putIfAbsent(name, type);
        });
        compilationUnit.findAll(MethodDeclaration.class).forEach(method ->
                method.getParameters().forEach(parameter -> types.putIfAbsent(parameter.getNameAsString(), parameter.getType().asString())));
        if (classInfo != null && classInfo.getClassMetadata() != null) {
            classInfo.getClassMetadata().getFields().forEach(field -> types.putIfAbsent(field.getName(), field.getTypeName()));
        }
        return types;
    }

    static String resolveExpressionType(Expression expression,
                                        Map<String, String> variableTypes,
                                        TestClassInfo classInfo) {
        if (expression == null) {
            return "";
        }
        if (expression instanceof ThisExpr) {
            return classInfo == null ? "" : classInfo.getClassName();
        }
        if (expression instanceof NameExpr nameExpr) {
            return variableTypes.getOrDefault(nameExpr.getNameAsString(), "");
        }
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            String fieldName = fieldAccessExpr.getNameAsString();
            String direct = variableTypes.get(fieldName);
            if (direct != null && !direct.isBlank()) {
                return direct;
            }
            return resolveExpressionType(fieldAccessExpr.getScope(), variableTypes, classInfo);
        }
        if (expression instanceof ObjectCreationExpr objectCreationExpr) {
            return objectCreationExpr.getType().asString();
        }
        return "";
    }

    private static String inferTypeFromInitializer(Optional<Expression> initializer) {
        if (initializer.isEmpty()) {
            return "";
        }
        Expression expression = initializer.get();
        if (expression instanceof ObjectCreationExpr creationExpr) {
            return creationExpr.getType().asString();
        }
        return "";
    }

    private static boolean matchesStandardLibraryType(String candidate, String standard) {
        if (candidate == null || standard == null) {
            return false;
        }
        String trimmedCandidate = candidate.trim();
        if (trimmedCandidate.isEmpty()) {
            return false;
        }
        if (trimmedCandidate.contains(standard)) {
            return true;
        }
        String standardSimple = simpleName(standard);
        String candidateSimple = simpleName(trimmedCandidate);
        if (!standardSimple.isEmpty()) {
            if (candidateSimple.equals(standardSimple)) {
                return true;
            }
            if (trimmedCandidate.startsWith(standardSimple + "<")) {
                return true;
            }
            if (trimmedCandidate.endsWith('.' + standardSimple)) {
                return true;
            }
            if (trimmedCandidate.equals(standardSimple)) {
                return true;
            }
        }
        return false;
    }
}
