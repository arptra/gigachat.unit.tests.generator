package com.gigachat.unit.tests.generator.analysis.deepsemantic;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.PipelineModuleConfig;
import com.gigachat.unit.tests.generator.dto.ClassMetadata;
import com.gigachat.unit.tests.generator.dto.FieldMetadata;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.type.Type;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalyzer;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticAnalysis;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticStaticCall;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticTypeConstructor;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticTypeMethod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Extends the baseline {@link MethodAnalyzer} with deep semantic analysis of the method body.
 */
public class DeepSemanticMethodAnalyzer extends MethodAnalyzer {
    private final MethodSignatureRegistry signatureRegistry;

    public DeepSemanticMethodAnalyzer(PipelineLogger logger, MethodSignatureRegistry signatureRegistry) {
        super(logger);
        this.signatureRegistry = Objects.requireNonNull(signatureRegistry, "signatureRegistry");
    }

    @Override
    public MethodAnalysisResult analyze(TestClassInfo classInfo,
                                        TestMethodInfo methodInfo,
                                        AgentConfig config,
                                        PipelineModuleConfig pipelineConfig) {
        MethodAnalysisResult base = super.analyze(classInfo, methodInfo, config, pipelineConfig);
        SemanticAnalysis semanticAnalysis = buildSemanticAnalysis(classInfo, methodInfo);
        return new MethodAnalysisResult(base.method(),
                base.dependencies(),
                base.invocations(),
                base.staticUsages(),
                base.unresolved(),
                semanticAnalysis);
    }

    private SemanticAnalysis buildSemanticAnalysis(TestClassInfo classInfo, TestMethodInfo methodInfo) {
        if (methodInfo == null) {
            return SemanticAnalysis.empty();
        }
        MethodDeclaration declaration = methodInfo.getDeclaration();
        if (declaration == null || declaration.getBody().isEmpty()) {
            return SemanticAnalysis.empty();
        }
        DeepSemanticTypeResolver resolver = new DeepSemanticTypeResolver(classInfo);
        DeepSemanticAnalysisResult collector = new DeepSemanticAnalysisResult(resolver);
        Map<String, String> parameterTypes = new HashMap<>();
        Map<String, String> localTypes = new HashMap<>();
        Map<String, String> fieldTypes = new HashMap<>();
        Set<String> parameterNames = new LinkedHashSet<>();
        Set<String> localNames = new LinkedHashSet<>();
        Set<String> fieldNames = new LinkedHashSet<>();

        registerParameters(declaration, resolver, collector, parameterTypes, parameterNames);
        registerFields(classInfo, resolver, collector, fieldTypes, fieldNames);
        registerLocalVariables(declaration, resolver, collector, localTypes, localNames);
        registerReturnType(declaration, resolver, collector);
        registerObjectCreations(declaration, resolver, collector);

        Map<String, String> variableTypes = new HashMap<>();
        variableTypes.putAll(fieldTypes);
        variableTypes.putAll(parameterTypes);
        variableTypes.putAll(localTypes);
        if (classInfo != null && classInfo.getClassName() != null) {
            variableTypes.put("this", classInfo.getClassName());
            collector.addDomainType(classInfo.getClassName());
        }

        Set<String> referencedFields = detectReferencedFields(declaration, fieldNames, parameterNames, localNames);
        addReferencedFieldTypes(referencedFields, fieldTypes, collector);
        analyseMethodCalls(declaration,
                classInfo,
                resolver,
                collector,
                variableTypes,
                fieldNames,
                parameterNames,
                localNames);

        enrichWithRegistryMetadata(collector, resolver);
        return collector.toSemanticAnalysis();
    }

    private void registerParameters(MethodDeclaration declaration,
                                    DeepSemanticTypeResolver resolver,
                                    DeepSemanticAnalysisResult collector,
                                    Map<String, String> parameterTypes,
                                    Set<String> parameterNames) {
        for (Parameter parameter : declaration.getParameters()) {
            String name = parameter.getNameAsString();
            String resolvedType = resolver.resolve(parameter.getType());
            if (!name.isBlank() && !resolvedType.isBlank()) {
                parameterTypes.put(name, resolvedType);
                parameterNames.add(name);
                collector.addDomainType(resolvedType);
            }
        }
    }

    private void registerFields(TestClassInfo classInfo,
                                DeepSemanticTypeResolver resolver,
                                DeepSemanticAnalysisResult collector,
                                Map<String, String> fieldTypes,
                                Set<String> fieldNames) {
        ClassMetadata metadata = classInfo == null ? null : classInfo.getClassMetadata();
        if (metadata == null) {
            return;
        }
        for (FieldMetadata field : metadata.getFields()) {
            if (field == null) {
                continue;
            }
            String name = field.getName();
            String resolvedType = resolver.resolve(field.getTypeName());
            if (name == null || name.isBlank() || resolvedType.isBlank()) {
                continue;
            }
            fieldNames.add(name);
            fieldTypes.put(name, resolvedType);
        }
    }

    private void registerLocalVariables(MethodDeclaration declaration,
                                        DeepSemanticTypeResolver resolver,
                                        DeepSemanticAnalysisResult collector,
                                        Map<String, String> localTypes,
                                        Set<String> localNames) {
        for (VariableDeclarationExpr declarationExpr : declaration.findAll(VariableDeclarationExpr.class)) {
            Type type = declarationExpr.getElementType();
            declarationExpr.getVariables().forEach(variable -> {
                String name = variable.getNameAsString();
                if (name == null || name.isBlank()) {
                    return;
                }
                Type variableType = variable.getType() == null ? type : variable.getType();
                String resolved = resolver.resolve(variableType);
                if (resolved.isBlank()) {
                    return;
                }
                localNames.add(name);
                localTypes.put(name, resolved);
                collector.addDomainType(resolved);
            });
        }
    }

    private void registerReturnType(MethodDeclaration declaration,
                                    DeepSemanticTypeResolver resolver,
                                    DeepSemanticAnalysisResult collector) {
        Type returnType = declaration.getType();
        String resolved = resolver.resolve(returnType);
        if (!resolved.isBlank() && !resolver.isPrimitive(resolved) && !"void".equalsIgnoreCase(resolved)) {
            collector.addDomainType(resolved);
        }
    }

    private void registerObjectCreations(MethodDeclaration declaration,
                                         DeepSemanticTypeResolver resolver,
                                         DeepSemanticAnalysisResult collector) {
        for (ObjectCreationExpr creationExpr : declaration.findAll(ObjectCreationExpr.class)) {
            String resolved = resolver.resolve(creationExpr.getType());
            if (!resolved.isBlank()) {
                collector.addDomainType(resolved);
            }
        }
    }

    private Set<String> detectReferencedFields(MethodDeclaration declaration,
                                                Set<String> fieldNames,
                                                Set<String> parameterNames,
                                                Set<String> localNames) {
        LinkedHashSet<String> referenced = new LinkedHashSet<>();
        declaration.findAll(NameExpr.class).forEach(nameExpr -> {
            String name = nameExpr.getNameAsString();
            if (fieldNames.contains(name) && !parameterNames.contains(name) && !localNames.contains(name)) {
                referenced.add(name);
            }
        });
        declaration.findAll(FieldAccessExpr.class).forEach(access -> {
            if (access.getScope().isThisExpr() && fieldNames.contains(access.getNameAsString())) {
                referenced.add(access.getNameAsString());
            }
        });
        return referenced;
    }

    private void addReferencedFieldTypes(Set<String> referencedFields,
                                         Map<String, String> fieldTypes,
                                         DeepSemanticAnalysisResult collector) {
        for (String field : referencedFields) {
            String type = fieldTypes.get(field);
            if (type != null && !type.isBlank()) {
                collector.addDomainType(type);
            }
        }
    }

    private void analyseMethodCalls(MethodDeclaration declaration,
                                    TestClassInfo classInfo,
                                    DeepSemanticTypeResolver resolver,
                                    DeepSemanticAnalysisResult collector,
                                    Map<String, String> variableTypes,
                                    Set<String> fieldNames,
                                    Set<String> parameterNames,
                                    Set<String> localNames) {
        for (MethodCallExpr methodCall : declaration.findAll(MethodCallExpr.class)) {
            Optional<Expression> scope = methodCall.getScope();
            boolean callOnThis = scope.isEmpty() || scope.get().isThisExpr();
            String ownerType = callOnThis && classInfo != null
                    ? classInfo.getClassName()
                    : resolveExpressionType(scope.orElse(null), variableTypes, resolver, classInfo);
            if (!ownerType.isBlank()) {
                collector.addDomainType(ownerType);
                if (callOnThis || isFieldReference(scope.orElse(null), fieldNames, parameterNames, localNames)) {
                    registerArgumentTypes(methodCall.getArguments(), variableTypes, resolver, classInfo, collector);
                }
                continue;
            }
            String staticOwner = resolveStaticOwner(scope.orElse(null), variableTypes, resolver);
            if (!staticOwner.isBlank()) {
                List<String> argumentTypes = resolveArgumentTypes(methodCall.getArguments(), variableTypes, resolver, classInfo);
                collector.addStaticCall(new SemanticStaticCall(staticOwner, methodCall.getNameAsString(), argumentTypes));
                collector.addDomainType(staticOwner);
            }
        }
    }

    private boolean isFieldReference(Expression scope,
                                     Set<String> fieldNames,
                                     Set<String> parameterNames,
                                     Set<String> localNames) {
        if (scope == null) {
            return false;
        }
        if (scope.isNameExpr()) {
            String name = scope.asNameExpr().getNameAsString();
            return fieldNames.contains(name) && !parameterNames.contains(name) && !localNames.contains(name);
        }
        if (scope.isFieldAccessExpr()) {
            FieldAccessExpr fieldAccessExpr = scope.asFieldAccessExpr();
            if (fieldAccessExpr.getScope().isThisExpr()) {
                return fieldNames.contains(fieldAccessExpr.getNameAsString());
            }
        }
        return false;
    }

    private void registerArgumentTypes(List<Expression> arguments,
                                       Map<String, String> variableTypes,
                                       DeepSemanticTypeResolver resolver,
                                       TestClassInfo classInfo,
                                       DeepSemanticAnalysisResult collector) {
        List<String> argTypes = resolveArgumentTypes(arguments, variableTypes, resolver, classInfo);
        for (String type : argTypes) {
            collector.addDomainType(type);
        }
    }

    private List<String> resolveArgumentTypes(List<Expression> arguments,
                                              Map<String, String> variableTypes,
                                              DeepSemanticTypeResolver resolver,
                                              TestClassInfo classInfo) {
        List<String> resolved = new ArrayList<>();
        for (Expression argument : arguments) {
            String type = resolveExpressionType(argument, variableTypes, resolver, classInfo);
            if (type.isBlank() && argument.isLiteralExpr()) {
                type = resolver.resolveLiteral(argument.asLiteralExpr());
            }
            if (!type.isBlank()) {
                resolved.add(type);
            }
        }
        return resolved;
    }

    private String resolveExpressionType(Expression expression,
                                         Map<String, String> variableTypes,
                                         DeepSemanticTypeResolver resolver,
                                         TestClassInfo classInfo) {
        if (expression == null) {
            return "";
        }
        if (expression.isNameExpr()) {
            String name = expression.asNameExpr().getNameAsString();
            return variableTypes.getOrDefault(name, "");
        }
        if (expression.isThisExpr()) {
            return classInfo == null ? "" : classInfo.getClassName();
        }
        if (expression.isSuperExpr()) {
            return classInfo == null ? "" : classInfo.getClassName();
        }
        if (expression.isFieldAccessExpr()) {
            FieldAccessExpr accessExpr = expression.asFieldAccessExpr();
            String direct = variableTypes.getOrDefault(accessExpr.getNameAsString(), "");
            if (!direct.isBlank()) {
                return direct;
            }
            return resolveExpressionType(accessExpr.getScope(), variableTypes, resolver, classInfo);
        }
        if (expression.isObjectCreationExpr()) {
            return resolver.resolve(expression.asObjectCreationExpr().getType());
        }
        if (expression.isMethodCallExpr()) {
            return "";
        }
        if (expression.isEnclosedExpr()) {
            return resolveExpressionType(expression.asEnclosedExpr().getInner(), variableTypes, resolver, classInfo);
        }
        if (expression.isCastExpr()) {
            CastExpr castExpr = expression.asCastExpr();
            return resolver.resolve(castExpr.getType());
        }
        if (expression.isConditionalExpr()) {
            ConditionalExpr conditionalExpr = expression.asConditionalExpr();
            String thenType = resolveExpressionType(conditionalExpr.getThenExpr(), variableTypes, resolver, classInfo);
            if (!thenType.isBlank()) {
                return thenType;
            }
            return resolveExpressionType(conditionalExpr.getElseExpr(), variableTypes, resolver, classInfo);
        }
        if (expression.isArrayAccessExpr()) {
            ArrayAccessExpr arrayAccessExpr = expression.asArrayAccessExpr();
            String base = resolveExpressionType(arrayAccessExpr.getName(), variableTypes, resolver, classInfo);
            if (base.endsWith("[]")) {
                return base.substring(0, base.length() - 2);
            }
            return base;
        }
        if (expression.isLiteralExpr()) {
            return resolver.resolveLiteral(expression.asLiteralExpr());
        }
        if (expression.isLambdaExpr()) {
            LambdaExpr lambdaExpr = expression.asLambdaExpr();
            if (lambdaExpr.getExpressionBody().isPresent()) {
                return resolveExpressionType(lambdaExpr.getExpressionBody().get(), variableTypes, resolver, classInfo);
            }
        }
        return "";
    }

    private String resolveStaticOwner(Expression scope,
                                      Map<String, String> variableTypes,
                                      DeepSemanticTypeResolver resolver) {
        if (scope == null) {
            return "";
        }
        if (scope.isNameExpr()) {
            String name = scope.asNameExpr().getNameAsString();
            if (variableTypes.containsKey(name)) {
                return "";
            }
            if (Character.isUpperCase(name.charAt(0))) {
                return resolver.resolveClassName(name);
            }
            return "";
        }
        if (scope.isFieldAccessExpr()) {
            FieldAccessExpr accessExpr = scope.asFieldAccessExpr();
            if (accessExpr.getScope().isThisExpr()) {
                return "";
            }
            String owner = resolveStaticOwner(accessExpr.getScope(), variableTypes, resolver);
            if (owner.isBlank()) {
                return "";
            }
            return owner + '.' + accessExpr.getNameAsString();
        }
        return "";
    }

    private void enrichWithRegistryMetadata(DeepSemanticAnalysisResult collector,
                                            DeepSemanticTypeResolver resolver) {
        for (String domainType : collector.domainTypes()) {
            String simple = resolver.simpleName(domainType);
            if (simple.isBlank()) {
                continue;
            }
            DeepSemanticTypeUsage usage = collector.usageFor(domainType);
            List<SemanticTypeMethod> registryMethods = buildMethodMetadata(simple, resolver);
            registryMethods.forEach(usage::addMethod);
            List<SemanticTypeConstructor> constructors = buildConstructorMetadata(domainType, simple, resolver);
            constructors.forEach(usage::addConstructor);
            if (resolver.isCollectionType(domainType)) {
                whitelistCollectionMethods().forEach(usage::addMethod);
            }
            if (resolver.isOptionalType(domainType)) {
                whitelistOptionalMethods().forEach(usage::addMethod);
            }
        }
    }

    private List<SemanticTypeMethod> buildMethodMetadata(String simpleTypeName,
                                                         DeepSemanticTypeResolver resolver) {
        List<String> signatures = signatureRegistry.getMethods(simpleTypeName);
        if (signatures.isEmpty()) {
            return List.of();
        }
        List<SemanticTypeMethod> metadata = new ArrayList<>();
        for (String signature : signatures) {
            SemanticTypeMethod method = parseMethodSignature(signature, resolver);
            if (method != null) {
                metadata.add(method);
            }
        }
        return metadata;
    }

    private List<SemanticTypeConstructor> buildConstructorMetadata(String domainType,
                                                                   String simpleTypeName,
                                                                   DeepSemanticTypeResolver resolver) {
        signatureRegistry.registerConstructorsIfAbsent(simpleTypeName);
        Map<String, List<ConstructorMetadata>> constructorsDetailed = signatureRegistry.getConstructorsDetailed();
        List<ConstructorMetadata> entries = constructorsDetailed.getOrDefault(simpleTypeName, List.of());
        if (entries.isEmpty()) {
            return List.of();
        }
        List<SemanticTypeConstructor> constructors = new ArrayList<>();
        for (ConstructorMetadata metadata : entries) {
            List<String> parameterTypes = new ArrayList<>();
            for (ParameterMetadata parameterMetadata : metadata.parameters()) {
                if (parameterMetadata == null) {
                    continue;
                }
                String resolved = resolver.resolve(parameterMetadata.type());
                if (!resolved.isBlank()) {
                    parameterTypes.add(resolved);
                }
            }
            constructors.add(new SemanticTypeConstructor(domainType, parameterTypes, metadata.signature()));
        }
        return constructors;
    }

    private SemanticTypeMethod parseMethodSignature(String signature, DeepSemanticTypeResolver resolver) {
        if (signature == null || signature.isBlank()) {
            return null;
        }
        String trimmed = signature.trim();
        int parenIndex = trimmed.indexOf('(');
        int closingIndex = trimmed.lastIndexOf(')');
        if (parenIndex < 0 || closingIndex < parenIndex) {
            return null;
        }
        String head = trimmed.substring(0, parenIndex).trim();
        int lastSpace = head.lastIndexOf(' ');
        if (lastSpace <= 0) {
            return null;
        }
        String returnType = head.substring(0, lastSpace).trim();
        String methodName = head.substring(lastSpace + 1).trim();
        String paramsBlock = trimmed.substring(parenIndex + 1, closingIndex).trim();
        List<String> parameters = parseParameterTypes(paramsBlock, resolver);
        return new SemanticTypeMethod(false, methodName, parameters, resolver.resolve(returnType));
    }

    private List<String> parseParameterTypes(String paramsBlock, DeepSemanticTypeResolver resolver) {
        if (paramsBlock == null || paramsBlock.isBlank()) {
            return List.of();
        }
        List<String> parts = splitParameters(paramsBlock);
        List<String> types = new ArrayList<>(parts.size());
        for (String part : parts) {
            String trimmed = part.trim();
            int lastSpace = trimmed.lastIndexOf(' ');
            String type = lastSpace < 0 ? trimmed : trimmed.substring(0, lastSpace).trim();
            type = type.replace("...", "[]");
            String resolved = resolver.resolve(type);
            if (!resolved.isBlank()) {
                types.add(resolved);
            }
        }
        return types;
    }

    private List<String> splitParameters(String block) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < block.length(); i++) {
            char ch = block.charAt(i);
            if (ch == '<' || ch == '(' || ch == '[') {
                depth++;
            } else if (ch == '>' || ch == ')' || ch == ']') {
                if (depth > 0) {
                    depth--;
                }
            } else if (ch == ',' && depth == 0) {
                parts.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            parts.add(last);
        }
        return parts;
    }

    private List<SemanticTypeMethod> whitelistCollectionMethods() {
        return List.of(
                new SemanticTypeMethod(false, "size", List.of(), "int"),
                new SemanticTypeMethod(false, "isEmpty", List.of(), "boolean"),
                new SemanticTypeMethod(false, "get", List.of("int"), "java.lang.Object"),
                new SemanticTypeMethod(false, "contains", List.of("java.lang.Object"), "boolean")
        );
    }

    private List<SemanticTypeMethod> whitelistOptionalMethods() {
        return List.of(
                new SemanticTypeMethod(false, "isPresent", List.of(), "boolean"),
                new SemanticTypeMethod(false, "get", List.of(), "java.lang.Object")
        );
    }
}
