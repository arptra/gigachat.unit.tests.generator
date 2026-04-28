package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze.AnalysisSummary;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze.TestTargetContext;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Derives constructor argument requirements for the class under test from source code so prompt and
 * validation logic can share the same bounded view of required constructor wiring.
 */
public final class TargetConstructorPolicyResolver {

    public Map<String, List<RequiredConstructorArgument>> resolveRequiredNonNullArguments(Path projectRoot,
                                                                                          AnalysisSummary summary) {
        if (projectRoot == null || summary == null || summary.testTargetContext() == null) {
            return Map.of();
        }
        Optional<Path> sourceFile = resolveTargetSourceFile(projectRoot, summary);
        if (sourceFile.isEmpty() || !Files.isRegularFile(sourceFile.get())) {
            return Map.of();
        }
        try {
            CompilationUnit unit = StaticJavaParser.parse(sourceFile.get());
            String targetClass = simpleName(summary.testTargetContext().className());
            ClassOrInterfaceDeclaration declaration = locateTargetClass(unit, targetClass);
            if (declaration == null) {
                return Map.of();
            }
            LinkedHashMap<String, List<RequiredConstructorArgument>> requirements = new LinkedHashMap<>();
            for (ConstructorDeclaration constructor : declaration.getConstructors()) {
                List<RequiredConstructorArgument> requiredArguments = extractRequiredArguments(constructor);
                if (!requiredArguments.isEmpty()) {
                    requirements.put(signatureOf(constructor), List.copyOf(requiredArguments));
                }
            }
            return Map.copyOf(requirements);
        } catch (IOException exception) {
            return Map.of();
        }
    }

    private Optional<Path> resolveTargetSourceFile(Path projectRoot, AnalysisSummary summary) {
        String fqcn = extractOriginalClassFqcn(summary);
        if (!fqcn.isBlank()) {
            Path direct = projectRoot.resolve("src/main/java").resolve(fqcn.replace('.', '/') + ".java");
            if (Files.isRegularFile(direct)) {
                return Optional.of(direct);
            }
        }
        String targetClass = simpleName(summary.testTargetContext().className());
        if (targetClass.isBlank()) {
            return Optional.empty();
        }
        Path mainSourceRoot = projectRoot.resolve("src/main/java");
        if (!Files.isDirectory(mainSourceRoot)) {
            return Optional.empty();
        }
        try (var stream = Files.walk(mainSourceRoot)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(targetClass + ".java"))
                    .findFirst();
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private String extractOriginalClassFqcn(AnalysisSummary summary) {
        String jsonContext = summary == null ? "" : summary.jsonContext();
        if (jsonContext == null || jsonContext.isBlank()) {
            return "";
        }
        try {
            JSONObject json = new JSONObject(jsonContext);
            String fqcn = json.optString("originalClassFqcn", "").trim();
            if (!fqcn.isBlank()) {
                return fqcn;
            }
        } catch (Exception ignored) {
            // best effort only
        }
        TestTargetContext targetContext = summary.testTargetContext();
        return targetContext == null ? "" : defaultString(targetContext.className());
    }

    private ClassOrInterfaceDeclaration locateTargetClass(CompilationUnit unit, String targetClass) {
        if (unit == null || targetClass == null || targetClass.isBlank()) {
            return null;
        }
        return unit.getClassByName(targetClass)
                .orElseGet(() -> unit.findFirst(ClassOrInterfaceDeclaration.class,
                        declaration -> targetClass.equals(declaration.getNameAsString()))
                        .orElse(null));
    }

    private List<RequiredConstructorArgument> extractRequiredArguments(ConstructorDeclaration constructor) {
        if (constructor == null || constructor.getParameters().isEmpty()) {
            return List.of();
        }
        Set<String> requiredParameterNames = new LinkedHashSet<>();
        for (MethodCallExpr methodCall : constructor.findAll(MethodCallExpr.class)) {
            if (!isRequireNonNullCall(methodCall) || methodCall.getArguments().isEmpty()) {
                continue;
            }
            Expression firstArgument = methodCall.getArgument(0);
            if (firstArgument instanceof NameExpr nameExpr) {
                requiredParameterNames.add(nameExpr.getNameAsString());
            }
        }
        if (requiredParameterNames.isEmpty()) {
            return List.of();
        }
        List<RequiredConstructorArgument> requiredArguments = new ArrayList<>();
        for (int index = 0; index < constructor.getParameters().size(); index++) {
            Parameter parameter = constructor.getParameter(index);
            if (!requiredParameterNames.contains(parameter.getNameAsString())) {
                continue;
            }
            requiredArguments.add(new RequiredConstructorArgument(
                    index + 1,
                    parameter.getNameAsString(),
                    parameter.getType().asString()));
        }
        return List.copyOf(requiredArguments);
    }

    private boolean isRequireNonNullCall(MethodCallExpr methodCall) {
        if (methodCall == null || !"requireNonNull".equals(methodCall.getNameAsString())) {
            return false;
        }
        if (methodCall.getScope().isEmpty()) {
            return true;
        }
        return methodCall.getScope()
                .filter(NameExpr.class::isInstance)
                .map(NameExpr.class::cast)
                .map(NameExpr::getNameAsString)
                .filter("Objects"::equals)
                .isPresent();
    }

    private String signatureOf(ConstructorDeclaration constructor) {
        return constructor.getNameAsString() + '(' + constructor.getParameters().stream()
                .map(parameter -> parameter.getType().asString() + ' ' + parameter.getNameAsString())
                .collect(Collectors.joining(", ")) + ')';
    }

    private String simpleName(String value) {
        String trimmed = defaultString(value);
        int lastDot = trimmed.lastIndexOf('.');
        return lastDot >= 0 ? trimmed.substring(lastDot + 1) : trimmed;
    }

    private String defaultString(String value) {
        return value == null ? "" : value.trim();
    }

    public record RequiredConstructorArgument(int position, String parameterName, String parameterType) {
    }
}
