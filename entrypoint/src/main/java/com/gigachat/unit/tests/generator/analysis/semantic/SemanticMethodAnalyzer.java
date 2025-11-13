package com.gigachat.unit.tests.generator.analysis.semantic;

import com.gigachat.unit.tests.generator.analysis.api.ConstructorInfo;
import com.gigachat.unit.tests.generator.analysis.api.MethodAnalysisDTO;
import com.gigachat.unit.tests.generator.analysis.api.MethodAnalyzer;
import com.gigachat.unit.tests.generator.analysis.api.MethodInfo;
import com.gigachat.unit.tests.generator.analysis.api.StaticDependency;
import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class SemanticMethodAnalyzer implements MethodAnalyzer {
    private final DomainTypesExtractor domainTypesExtractor;
    private final MethodUsageExtractor methodUsageExtractor;
    private final StaticDependencyResolver staticDependencyResolver;
    private final MethodSignatureRegistry typeRegistry;

    public SemanticMethodAnalyzer(MethodSignatureRegistry typeRegistry) {
        this(typeRegistry, new DomainTypesExtractor(), new MethodUsageExtractor(), new StaticDependencyResolver());
    }

    public SemanticMethodAnalyzer(MethodSignatureRegistry typeRegistry,
                                  DomainTypesExtractor domainTypesExtractor,
                                  MethodUsageExtractor methodUsageExtractor,
                                  StaticDependencyResolver staticDependencyResolver) {
        this.typeRegistry = typeRegistry == null ? new MethodSignatureRegistry() : typeRegistry;
        this.domainTypesExtractor = Objects.requireNonNull(domainTypesExtractor, "domainTypesExtractor");
        this.methodUsageExtractor = Objects.requireNonNull(methodUsageExtractor, "methodUsageExtractor");
        this.staticDependencyResolver = Objects.requireNonNull(staticDependencyResolver, "staticDependencyResolver");
    }

    @Override
    public MethodAnalysisDTO analyze(TestMethodInfo info) {
        if (info == null || info.getDeclaration() == null) {
            return MethodAnalysisDTO.empty();
        }
        MethodDeclaration declaration = info.getDeclaration();
        TypeResolver resolver = TypeResolver.forMethod(declaration);
        SemanticMethodVisitor visitor = new SemanticMethodVisitor(resolver);
        declaration.accept(visitor, null);

        LinkedHashSet<String> domainTypes = new LinkedHashSet<>();
        declaration.getParameters().forEach(parameter ->
                domainTypes.addAll(TypeResolver.explodeTypes(parameter.getType().asString())));
        domainTypes.addAll(TypeResolver.explodeTypes(info.getReturnType()));
        domainTypes.addAll(domainTypesExtractor.extract(declaration,
                visitor.getMethodCalls(),
                visitor.getReturnExpressions(),
                visitor.getSemanticConstructors(),
                resolver,
                info.getReturnType()));

        Map<String, List<MethodSignature>> typeMethods = new LinkedHashMap<>(
                methodUsageExtractor.extract(visitor.getMethodCalls(), resolver));
        Map<String, List<ConstructorSignature>> typeConstructors = groupConstructors(visitor.getSemanticConstructors());

        mergeRegistryMetadata(domainTypes, typeMethods, typeConstructors);

        List<StaticCall> staticCalls = staticDependencyResolver.resolve(visitor.getStaticCalls());
        SemanticAnalysisResult result = new SemanticAnalysisResult(domainTypes, typeMethods, typeConstructors, staticCalls);
        return convert(result);
    }

    private Map<String, List<ConstructorSignature>> groupConstructors(List<ConstructorSignature> constructors) {
        Map<String, List<ConstructorSignature>> grouped = new LinkedHashMap<>();
        if (constructors == null) {
            return grouped;
        }
        for (ConstructorSignature signature : constructors) {
            if (signature == null || signature.getTypeName().isEmpty()) {
                continue;
            }
            grouped.computeIfAbsent(signature.getTypeName(), ignored -> new ArrayList<>()).add(signature);
        }
        grouped.replaceAll((type, list) -> List.copyOf(list));
        return grouped;
    }

    private void mergeRegistryMetadata(Set<String> domainTypes,
                                       Map<String, List<MethodSignature>> typeMethods,
                                       Map<String, List<ConstructorSignature>> typeConstructors) {
        for (String domainType : domainTypes) {
            if (domainType == null || domainType.isBlank()) {
                continue;
            }
            typeRegistry.registerMethodsIfAbsent(domainType);
            typeRegistry.registerConstructorsIfAbsent(domainType);
            List<String> registeredMethods = typeRegistry.getMethods(domainType);
            if (!registeredMethods.isEmpty()) {
                List<MethodSignature> methodSignatures = convertMethodMetadata(domainType, registeredMethods);
                mergeSignatures(typeMethods, domainType, methodSignatures);
            }
            List<ConstructorMetadata> constructors = typeRegistry.getConstructorsForClass(domainType);
            if (!constructors.isEmpty()) {
                List<ConstructorSignature> constructorSignatures = convertConstructors(constructors);
                mergeConstructors(typeConstructors, domainType, constructorSignatures);
            }
        }
    }

    private List<ConstructorSignature> convertConstructors(List<ConstructorMetadata> constructors) {
        List<ConstructorSignature> signatures = new ArrayList<>(constructors.size());
        for (ConstructorMetadata metadata : constructors) {
            if (metadata == null) {
                continue;
            }
            List<String> parameterTypes = new ArrayList<>();
            for (ParameterMetadata parameterMetadata : metadata.parameters()) {
                if (parameterMetadata == null) {
                    continue;
                }
                parameterTypes.add(TypeResolver.simpleName(parameterMetadata.type()));
            }
            signatures.add(new ConstructorSignature(parseConstructorOwner(metadata.signature()),
                    parameterTypes,
                    metadata.signature()));
        }
        return signatures;
    }

    private String parseConstructorOwner(String signature) {
        if (signature == null || signature.isBlank()) {
            return "";
        }
        int parenIndex = signature.indexOf('(');
        if (parenIndex <= 0) {
            return TypeResolver.simpleName(signature);
        }
        String before = signature.substring(0, parenIndex).trim();
        int space = before.lastIndexOf(' ');
        if (space >= 0 && space + 1 < before.length()) {
            return TypeResolver.simpleName(before.substring(space + 1));
        }
        return TypeResolver.simpleName(before);
    }

    private void mergeConstructors(Map<String, List<ConstructorSignature>> accumulator,
                                   String type,
                                   List<ConstructorSignature> signatures) {
        if (signatures.isEmpty()) {
            return;
        }
        accumulator.compute(type, (key, existing) -> {
            List<ConstructorSignature> merged = existing == null
                    ? new ArrayList<>()
                    : new ArrayList<>(existing);
            merged.addAll(signatures);
            return List.copyOf(merged);
        });
    }

    private List<MethodSignature> convertMethodMetadata(String type, List<String> metadata) {
        List<MethodSignature> signatures = new ArrayList<>(metadata.size());
        for (String signature : metadata) {
            MethodSignature parsed = parseMethodSignature(type, signature);
            if (parsed != null) {
                signatures.add(parsed);
            }
        }
        return signatures;
    }

    private void mergeSignatures(Map<String, List<MethodSignature>> accumulator,
                                 String type,
                                 List<MethodSignature> newSignatures) {
        if (newSignatures.isEmpty()) {
            return;
        }
        accumulator.compute(type, (key, existing) -> {
            List<MethodSignature> merged = existing == null
                    ? new ArrayList<>()
                    : new ArrayList<>(existing);
            merged.addAll(newSignatures);
            return List.copyOf(merged);
        });
    }

    private MethodSignature parseMethodSignature(String type, String signature) {
        if (signature == null || signature.isBlank()) {
            return null;
        }
        int start = signature.indexOf('(');
        int end = signature.lastIndexOf(')');
        if (start < 0 || end < start) {
            return null;
        }
        String before = signature.substring(0, start).trim();
        String inside = signature.substring(start + 1, end).trim();
        String methodName = before;
        String returnType = TypeResolver.UNKNOWN_TYPE;
        int lastSpace = before.lastIndexOf(' ');
        if (lastSpace >= 0 && lastSpace + 1 < before.length()) {
            methodName = before.substring(lastSpace + 1);
            returnType = TypeResolver.simpleName(before.substring(0, lastSpace));
        }
        List<String> parameterTypes = parseParameterTypes(inside);
        return new MethodSignature(type, methodName, parameterTypes, returnType);
    }

    private List<String> parseParameterTypes(String inside) {
        if (inside == null || inside.isBlank()) {
            return List.of();
        }
        List<String> params = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < inside.length(); i++) {
            char ch = inside.charAt(i);
            if (ch == '<' || ch == '(' || ch == '[') {
                depth++;
            } else if (ch == '>' || ch == ')' || ch == ']') {
                if (depth > 0) {
                    depth--;
                }
            }
            if (ch == ',' && depth == 0) {
                params.add(extractType(current.toString()));
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        if (current.length() > 0) {
            params.add(extractType(current.toString()));
        }
        return params;
    }

    private String extractType(String parameter) {
        String trimmed = parameter.trim();
        if (trimmed.isEmpty()) {
            return TypeResolver.UNKNOWN_TYPE;
        }
        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace >= 0) {
            trimmed = trimmed.substring(0, lastSpace);
        }
        return TypeResolver.simpleName(trimmed);
    }

    private MethodAnalysisDTO convert(SemanticAnalysisResult result) {
        Map<String, List<ConstructorInfo>> typeConstructors = new LinkedHashMap<>();
        result.getTypeConstructors().forEach((type, constructors) -> {
            List<ConstructorInfo> infos = new ArrayList<>(constructors.size());
            for (ConstructorSignature signature : constructors) {
                infos.add(new ConstructorInfo(signature.getTypeName(),
                        signature.getParameterTypes(),
                        signature.getSignature()));
            }
            typeConstructors.put(type, List.copyOf(infos));
        });

        Map<String, List<MethodInfo>> typeMethods = new LinkedHashMap<>();
        result.getTypeMethods().forEach((type, methods) -> {
            List<MethodInfo> infos = new ArrayList<>(methods.size());
            for (MethodSignature method : methods) {
                infos.add(new MethodInfo(method.getMethodName(), method.getParameterTypes(), method.getReturnType()));
            }
            typeMethods.put(type, List.copyOf(infos));
        });

        List<StaticDependency> staticDependencies = new ArrayList<>();
        for (StaticCall call : result.getStaticCalls()) {
            staticDependencies.add(new StaticDependency(call.getOwner(),
                    call.getMethodName(),
                    call.getStrategy(),
                    call.getReplacement()));
        }

        return new MethodAnalysisDTO(typeConstructors, typeMethods, staticDependencies, result.getDomainTypes());
    }
}
