package com.gigachat.unit.tests.generator.pipeline.helpers.validation;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.InvalidLLMResponseException;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class GeneratedSnippetApiUsageValidator {

    private final PipelineLogger logger;
    private final Analyze analyze;
    private final MethodSignatureRegistry signatureRegistry;
    private final ClasspathApiMetadataResolver classpathApiMetadataResolver;

    GeneratedSnippetApiUsageValidator(PipelineLogger logger,
                                      Analyze analyze,
                                      MethodSignatureRegistry signatureRegistry) {
        this(logger, analyze, signatureRegistry, new ClasspathApiMetadataResolver(logger, signatureRegistry));
    }

    GeneratedSnippetApiUsageValidator(PipelineLogger logger,
                                      Analyze analyze,
                                      MethodSignatureRegistry signatureRegistry,
                                      ClasspathApiMetadataResolver classpathApiMetadataResolver) {
        this.logger = logger;
        this.analyze = analyze;
        this.signatureRegistry = signatureRegistry;
        this.classpathApiMetadataResolver = classpathApiMetadataResolver;
    }

    Map<String, String> ensureMethodAndConstructorUsageIsValid(AgentConfig config,
                                                               CompilationUnit compilationUnit,
                                                               Analyze.AnalysisSummary analysisSummary,
                                                               TestClassInfo classInfo,
                                                               TestMethodInfo methodInfo) {
        if (compilationUnit == null) {
            return Map.of();
        }
        Map<String, String> variableTypes = ValidationSupport.collectVariableTypes(compilationUnit, analysisSummary, classInfo);
        LinkedHashSet<String> issues = new LinkedHashSet<>();
        LinkedHashSet<String> missingConstructorMetadata = new LinkedHashSet<>();
        Set<String> signatureTypes = collectMethodSignatureTypeNames(methodInfo);
        compilationUnit.findAll(ObjectCreationExpr.class).forEach(expr -> {
            String rawType = expr.getType().asString();
            String type = ValidationSupport.simpleName(rawType);
            if (type.isEmpty()) {
                return;
            }
            registerClasspathApiIfAvailable(config, classInfo, compilationUnit, rawType);
            if (!signatureRegistry.hasClass(type)) {
                return;
            }
            int argumentCount = expr.getArguments().size();
            signatureRegistry.registerConstructorsIfAbsent(type);
            List<ConstructorMetadata> constructors = signatureRegistry.getConstructorsForClass(type);
            if (constructors.isEmpty() || !signatureRegistry.constructorExists(type, argumentCount)) {
                registerClasspathApiIfAvailable(config, classInfo, compilationUnit, rawType);
                constructors = signatureRegistry.getConstructorsForClass(type);
                if (!constructors.isEmpty() && signatureRegistry.constructorExists(type, argumentCount)) {
                    return;
                }
                if (signatureTypes.contains(type)) {
                    attemptConstructorRefresh(config, classInfo, methodInfo, type);
                    constructors = signatureRegistry.getConstructorsForClass(type);
                    if (!constructors.isEmpty() && signatureRegistry.constructorExists(type, argumentCount)) {
                        return;
                    }
                }
                missingConstructorMetadata.add(type);
                issues.add("E104: Missing constructor metadata for " + formatConstructorInvocation(type, expr));
            }
        });
        compilationUnit.findAll(MethodCallExpr.class).forEach(expr -> {
            Optional<Expression> scope = expr.getScope();
            if (scope.isEmpty()) {
                return;
            }
            String resolvedType = ValidationSupport.resolveExpressionType(scope.get(), variableTypes, classInfo);
            if (ValidationSupport.isStandardLibraryType(resolvedType)) {
                return;
            }
            String simple = ValidationSupport.simpleName(resolvedType);
            if (simple.isEmpty()) {
                return;
            }
            registerClasspathApiIfAvailable(config, classInfo, compilationUnit, resolvedType);
            if (!signatureRegistry.hasClass(simple)) {
                return;
            }
            if (!signatureRegistry.methodExists(simple, expr.getNameAsString(), expr.getArguments().size())) {
                registerClasspathApiIfAvailable(config, classInfo, compilationUnit, resolvedType);
                if (signatureRegistry.methodExists(simple, expr.getNameAsString(), expr.getArguments().size())) {
                    return;
                }
                if (isCurrentStaticTargetInvocation(simple, expr, classInfo, methodInfo)) {
                    return;
                }
                issues.add("E102: Invented method " + formatMethodInvocation(simple, expr));
            }
        });
        if (!missingConstructorMetadata.isEmpty()) {
            logger.warn("Constructor metadata missing for: " + String.join(", ", missingConstructorMetadata));
        }
        if (!issues.isEmpty()) {
            String message = String.join("; ", issues);
            logger.warn("⚠️  " + message);
            throw new InvalidLLMResponseException(message);
        }
        return variableTypes;
    }

    private void registerClasspathApiIfAvailable(AgentConfig config,
                                                 TestClassInfo classInfo,
                                                 CompilationUnit compilationUnit,
                                                 String rawType) {
        if (classpathApiMetadataResolver == null
                || config == null
                || classInfo == null
                || rawType == null
                || rawType.isBlank()) {
            return;
        }
        Path projectRoot = config.getProjectPath();
        Path testClassFile = classInfo.getTargetPath();
        if (projectRoot == null || testClassFile == null) {
            return;
        }
        List<String> candidates = classpathTypeCandidates(compilationUnit, rawType);
        if (candidates.isEmpty()) {
            return;
        }
        classpathApiMetadataResolver.registerType(projectRoot, testClassFile, candidates);
    }

    private List<String> classpathTypeCandidates(CompilationUnit compilationUnit, String rawType) {
        String cleaned = stripTypeDecorations(rawType);
        if (cleaned.isBlank()) {
            return List.of();
        }
        String simple = ValidationSupport.simpleName(cleaned);
        if (simple.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (cleaned.contains(".")) {
            candidates.add(cleaned);
        }
        if (compilationUnit != null) {
            compilationUnit.getImports().forEach(importDeclaration -> {
                if (importDeclaration == null || importDeclaration.isStatic()) {
                    return;
                }
                String importName = importDeclaration.getNameAsString();
                if (importName == null || importName.isBlank()) {
                    return;
                }
                if (importDeclaration.isAsterisk()) {
                    candidates.add(importName + "." + simple);
                    return;
                }
                if (simple.equals(ValidationSupport.simpleName(importName))) {
                    candidates.add(importName);
                }
            });
            compilationUnit.getPackageDeclaration()
                    .map(declaration -> declaration.getName().asString())
                    .filter(packageName -> !packageName.isBlank())
                    .ifPresent(packageName -> candidates.add(packageName + "." + cleaned));
        }
        if (!cleaned.contains(".")) {
            candidates.add(cleaned);
        }
        return List.copyOf(candidates);
    }

    private String stripTypeDecorations(String rawType) {
        if (rawType == null) {
            return "";
        }
        String value = rawType.trim();
        int genericStart = value.indexOf('<');
        if (genericStart >= 0) {
            value = value.substring(0, genericStart);
        }
        int arrayIndex = value.indexOf('[');
        if (arrayIndex >= 0) {
            value = value.substring(0, arrayIndex);
        }
        return value.trim();
    }

    private Set<String> collectMethodSignatureTypeNames(TestMethodInfo methodInfo) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (methodInfo == null) {
            return names;
        }
        MethodDeclaration declaration = methodInfo.getDeclaration();
        if (declaration != null) {
            declaration.getParameters().forEach(parameter -> extractTypeNames(parameter.getType(), names));
            extractTypeNames(declaration.getType(), names);
        } else {
            String returnType = methodInfo.getReturnType();
            if (returnType != null && !returnType.isBlank()) {
                names.add(ValidationSupport.simpleName(returnType));
            }
        }
        return names;
    }

    private boolean isCurrentStaticTargetInvocation(String resolvedSimpleType,
                                                    MethodCallExpr expr,
                                                    TestClassInfo classInfo,
                                                    TestMethodInfo methodInfo) {
        if (expr == null || classInfo == null || methodInfo == null || !isTargetStatic(methodInfo)) {
            return false;
        }
        String targetClass = ValidationSupport.simpleName(classInfo.getClassName());
        if (targetClass.isBlank() || !targetClass.equals(ValidationSupport.simpleName(resolvedSimpleType))) {
            return false;
        }
        String targetMethodName = targetMethodName(methodInfo);
        if (targetMethodName.isBlank()
                || !targetMethodName.equals(expr.getNameAsString())
                || targetArity(methodInfo) != expr.getArguments().size()) {
            return false;
        }
        String scopeText = expr.getScope().map(Expression::toString).orElse("");
        return scopeText.equals(targetClass) || scopeText.endsWith("." + targetClass);
    }

    private boolean isTargetStatic(TestMethodInfo methodInfo) {
        MethodDeclaration declaration = methodInfo.getDeclaration();
        if (declaration != null) {
            return declaration.isStatic();
        }
        String signature = methodInfo.getSignature();
        return signature != null && signature.matches(".*\\bstatic\\b.*");
    }

    private String targetMethodName(TestMethodInfo methodInfo) {
        MethodDeclaration declaration = methodInfo.getDeclaration();
        if (declaration != null) {
            return declaration.getNameAsString();
        }
        return ValidationSupport.extractMethodName(methodInfo.getSignature());
    }

    private int targetArity(TestMethodInfo methodInfo) {
        MethodDeclaration declaration = methodInfo.getDeclaration();
        if (declaration != null) {
            return declaration.getParameters().size();
        }
        String signature = methodInfo.getSignature();
        if (signature == null || signature.isBlank()) {
            return 0;
        }
        int start = signature.indexOf('(');
        int end = signature.lastIndexOf(')');
        if (start < 0 || end <= start) {
            return 0;
        }
        String parameters = signature.substring(start + 1, end).trim();
        if (parameters.isEmpty()) {
            return 0;
        }
        int depth = 0;
        int arity = 1;
        for (int index = 0; index < parameters.length(); index++) {
            char ch = parameters.charAt(index);
            if (ch == '<' || ch == '(' || ch == '[') {
                depth++;
                continue;
            }
            if (ch == '>' || ch == ')' || ch == ']') {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (ch == ',' && depth == 0) {
                arity++;
            }
        }
        return arity;
    }

    private void extractTypeNames(com.github.javaparser.ast.type.Type type, Set<String> collector) {
        if (type == null || collector == null) {
            return;
        }
        if (type.isPrimitiveType()) {
            return;
        }
        if (type.isArrayType()) {
            extractTypeNames(type.asArrayType().getComponentType(), collector);
            return;
        }
        if (type.isUnionType()) {
            type.asUnionType().getElements().forEach(element -> extractTypeNames(element, collector));
            return;
        }
        if (type.isIntersectionType()) {
            type.asIntersectionType().getElements().forEach(element -> extractTypeNames(element, collector));
            return;
        }
        if (type.isWildcardType()) {
            type.asWildcardType().getExtendedType().ifPresent(t -> extractTypeNames(t, collector));
            type.asWildcardType().getSuperType().ifPresent(t -> extractTypeNames(t, collector));
            return;
        }
        if (type.isClassOrInterfaceType()) {
            collector.add(ValidationSupport.simpleName(type.asClassOrInterfaceType().getNameWithScope()));
            type.asClassOrInterfaceType().getTypeArguments()
                    .ifPresent(arguments -> arguments.forEach(argument -> extractTypeNames(argument, collector)));
            return;
        }
        collector.add(ValidationSupport.simpleName(type.asString()));
    }

    private void attemptConstructorRefresh(AgentConfig config,
                                           TestClassInfo classInfo,
                                           TestMethodInfo methodInfo,
                                           String type) {
        if (config == null) {
            return;
        }
        boolean refreshTriggered = signatureRegistry.refreshConstructors(type);
        String baseMessage = "Attempting constructor metadata refresh for type " + type
                + " referenced in method signature before failing validation.";
        if (!refreshTriggered) {
            logger.warn(baseMessage + " Registry has no cached constructors yet.");
        } else {
            logger.warn(baseMessage);
        }
        analyze.analyze(config, classInfo, methodInfo);
    }

    private String formatConstructorInvocation(String type, ObjectCreationExpr expr) {
        return type + '(' + describeArguments(new java.util.ArrayList<>(expr.getArguments())) + ')';
    }

    private String formatMethodInvocation(String type, MethodCallExpr expr) {
        return type + '.' + expr.getNameAsString() + '(' + describeArguments(new java.util.ArrayList<>(expr.getArguments())) + ')';
    }

    private String describeArguments(List<Expression> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        List<String> parts = new java.util.ArrayList<>(arguments.size());
        for (Expression argument : arguments) {
            String text = argument == null ? "" : argument.toString();
            if (text.length() > 40) {
                text = text.substring(0, 37) + "...";
            }
            parts.add(text);
        }
        return String.join(", ", parts);
    }
}
