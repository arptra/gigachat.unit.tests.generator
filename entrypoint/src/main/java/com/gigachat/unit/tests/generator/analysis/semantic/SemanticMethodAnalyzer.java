package com.gigachat.unit.tests.generator.analysis.semantic;

import com.gigachat.unit.tests.generator.analysis.api.ConstructorInfo;
import com.gigachat.unit.tests.generator.analysis.api.MethodAnalysisDTO;
import com.gigachat.unit.tests.generator.analysis.api.MethodAnalyzer;
import com.gigachat.unit.tests.generator.analysis.api.MethodInfo;
import com.gigachat.unit.tests.generator.analysis.api.StaticDependency;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class SemanticMethodAnalyzer implements MethodAnalyzer {
    private final DomainTypesExtractor domainTypesExtractor;
    private final MethodUsageExtractor methodUsageExtractor;
    private final StaticDependencyResolver staticDependencyResolver;
    private final MethodSignatureRegistry typeRegistry;
    private final SemanticMetadataMerger metadataMerger;

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
        this.metadataMerger = new SemanticMetadataMerger(this.typeRegistry);
    }

    @Override
    public MethodAnalysisDTO analyze(TestMethodInfo info) {
        if (info == null || info.getDeclaration() == null) {
            return MethodAnalysisDTO.empty();
        }
        MethodDeclaration declaration = info.getDeclaration();
        SemanticTypeContext context = new SemanticTypeContext();
        registerClassFields(declaration, context);
        registerParameters(declaration, context);
        String enclosingType = resolveEnclosingType(declaration);
        TypeResolver resolver = new TypeResolver(enclosingType, context, typeRegistry);

        LinkedHashSet<String> signatureTypes = new LinkedHashSet<>();
        declaration.getParameters().forEach(parameter -> {
            ResolvedType parameterType = ResolvedType.of(parameter.getType().asString());
            signatureTypes.add(parameterType.describe());
            signatureTypes.addAll(parameterType.flatten());
        });
        ResolvedType declaredReturn = ResolvedType.of(info.getReturnType());
        signatureTypes.add(declaredReturn.describe());
        signatureTypes.addAll(declaredReturn.flatten());

        LinkedHashSet<String> domainTypes = new LinkedHashSet<>(signatureTypes);

        SemanticMethodVisitor visitor = new SemanticMethodVisitor(resolver, context);
        declaration.getBody().ifPresent(body -> body.accept(visitor, context));
        domainTypes.addAll(visitor.getDomainTypes());

        Map<String, List<MethodSignature>> semanticMethods = methodUsageExtractor.extract(visitor.getMethodCalls(), resolver);
        Map<String, List<ConstructorSignature>> semanticConstructors = groupConstructors(visitor.getSemanticConstructors());

        domainTypes.addAll(domainTypesExtractor.extract(declaration,
                visitor.getMethodCalls(),
                visitor.getReturnExpressions(),
                visitor.getSemanticConstructors(),
                resolver,
                info.getReturnType()));

        Map<String, List<MethodSignature>> mergedMethods = metadataMerger.mergeMethods(domainTypes, semanticMethods);
        mergedMethods = redistributeRegistryMethods(mergedMethods, semanticMethods.keySet());
        mergedMethods = removeUnknown(mergedMethods);
        Map<String, List<ConstructorSignature>> mergedConstructors = removeUnknownConstructors(
                metadataMerger.mergeConstructors(domainTypes, semanticConstructors));

        Set<String> filteredDomainTypes = filterDomainTypes(domainTypes, mergedMethods, mergedConstructors, signatureTypes);

        List<StaticCall> staticCalls = staticDependencyResolver.resolve(visitor.getStaticCalls());
        SemanticAnalysisResult result = new SemanticAnalysisResult(filteredDomainTypes, mergedMethods, mergedConstructors, staticCalls);
        return convert(result);
    }

    private void registerClassFields(MethodDeclaration declaration, SemanticTypeContext context) {
        declaration.findAncestor(ClassOrInterfaceDeclaration.class)
                .ifPresent(clazz -> clazz.getFields().forEach(field -> registerField(context, field)));
    }

    private void registerField(SemanticTypeContext context, FieldDeclaration field) {
        for (VariableDeclarator variable : field.getVariables()) {
            context.registerField(variable.getNameAsString(), ResolvedType.of(variable.getType().asString()));
        }
    }

    private void registerParameters(MethodDeclaration declaration, SemanticTypeContext context) {
        declaration.getParameters().forEach(parameter ->
                context.registerParameter(parameter.getNameAsString(), ResolvedType.of(parameter.getType().asString())));
    }

    private String resolveEnclosingType(MethodDeclaration declaration) {
        Optional<ClassOrInterfaceDeclaration> clazz = declaration.findAncestor(ClassOrInterfaceDeclaration.class);
        return clazz.map(ClassOrInterfaceDeclaration::getNameAsString).orElse("");
    }

    private Map<String, List<ConstructorSignature>> groupConstructors(List<ConstructorSignature> constructors) {
        Map<String, List<ConstructorSignature>> grouped = new LinkedHashMap<>();
        if (constructors == null) {
            return grouped;
        }
        for (ConstructorSignature signature : constructors) {
            if (signature == null || signature.getTypeName().isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(signature.getTypeName(), ignored -> new ArrayList<>()).add(signature);
        }
        grouped.replaceAll((type, list) -> List.copyOf(list));
        return grouped;
    }

    private Map<String, List<MethodSignature>> removeUnknown(Map<String, List<MethodSignature>> methods) {
        Map<String, List<MethodSignature>> sanitized = new LinkedHashMap<>();
        methods.forEach((type, list) -> {
            if (TypeResolver.UNKNOWN_TYPE.equals(type) || type == null || type.isBlank()) {
                return;
            }
            sanitized.put(type, list);
        });
        return Map.copyOf(sanitized);
    }

    private Map<String, List<ConstructorSignature>> removeUnknownConstructors(Map<String, List<ConstructorSignature>> constructors) {
        Map<String, List<ConstructorSignature>> sanitized = new LinkedHashMap<>();
        constructors.forEach((type, list) -> {
            if (TypeResolver.UNKNOWN_TYPE.equals(type) || type == null || type.isBlank()) {
                return;
            }
            sanitized.put(type, list);
        });
        return Map.copyOf(sanitized);
    }

    private Set<String> filterDomainTypes(Set<String> candidates,
                                          Map<String, List<MethodSignature>> typeMethods,
                                          Map<String, List<ConstructorSignature>> typeConstructors,
                                          Set<String> signatureTypes) {
        LinkedHashSet<String> filtered = new LinkedHashSet<>();
        for (String type : candidates) {
            if (type == null || type.isBlank() || TypeResolver.UNKNOWN_TYPE.equals(type)) {
                continue;
            }
            ResolvedType resolved = ResolvedType.of(type);
            if (resolved.isPrimitive() || isJavaPackage(type)) {
                continue;
            }
        boolean hasConstructors = hasEntries(typeConstructors, type, resolved);
        boolean hasMethods = hasEntries(typeMethods, type, resolved);
        boolean partOfSignature = signatureTypes.contains(type) || signatureTypes.contains(resolved.getName());
        if (hasConstructors || hasMethods || partOfSignature) {
                if (!type.isBlank()) {
                    filtered.add(type);
                }
                filtered.add(resolved.getName());
                resolved.getGenericArguments().stream()
                        .map(ResolvedType::normalise)
                        .filter(argument -> argument != null && !argument.isBlank())
                        .forEach(filtered::add);
        }
        }
        return Set.copyOf(filtered);
    }

    private boolean isJavaPackage(String type) {
        return type != null && type.startsWith("java.");
    }

    private boolean hasEntries(Map<String, ? extends List<?>> map, String rawType, ResolvedType resolved) {
        if (map == null || map.isEmpty()) {
            return false;
        }
        if (hasNonEmpty(map.get(rawType))) {
            return true;
        }
        String base = resolved.getName();
        return hasNonEmpty(map.get(base));
    }

    private boolean hasNonEmpty(List<?> values) {
        return values != null && !values.isEmpty();
    }

    private Map<String, List<MethodSignature>> redistributeRegistryMethods(Map<String, List<MethodSignature>> mergedMethods,
                                                                           Set<String> semanticKeys) {
        if (mergedMethods == null || mergedMethods.isEmpty() || semanticKeys == null || semanticKeys.isEmpty()) {
            return mergedMethods == null ? Map.of() : mergedMethods;
        }
        LinkedHashMap<String, List<MethodSignature>> mutable = new LinkedHashMap<>();
        mergedMethods.forEach((key, value) -> mutable.put(key, value == null ? new ArrayList<>() : new ArrayList<>(value)));

        LinkedHashMap<String, List<String>> baseToVariants = new LinkedHashMap<>();
        for (String semanticKey : semanticKeys) {
            ResolvedType resolved = ResolvedType.of(semanticKey);
            String base = resolved.getName();
            if (base.isBlank()) {
                continue;
            }
            baseToVariants.computeIfAbsent(base, ignored -> new ArrayList<>());
            List<String> variants = baseToVariants.get(base);
            if (!variants.contains(semanticKey)) {
                variants.add(semanticKey);
            }
        }

        for (Map.Entry<String, List<String>> entry : baseToVariants.entrySet()) {
            String base = entry.getKey();
            List<String> variants = entry.getValue();
            boolean hasSpecialisedVariant = variants.stream().anyMatch(variant -> !variant.equals(base));
            if (!hasSpecialisedVariant) {
                continue;
            }
            List<MethodSignature> baseMethods = mutable.get(base);
            if (baseMethods == null || baseMethods.isEmpty()) {
                continue;
            }
            for (String variant : variants) {
                if (variant.equals(base)) {
                    continue;
                }
                mutable.computeIfAbsent(variant, ignored -> new ArrayList<>()).addAll(baseMethods);
            }
            mutable.remove(base);
        }

        mutable.replaceAll((key, value) -> deduplicateSignatures(value));
        mutable.replaceAll((key, value) -> value == null ? List.of() : List.copyOf(value));
        return Map.copyOf(mutable);
    }

    private List<MethodSignature> deduplicateSignatures(List<MethodSignature> signatures) {
        if (signatures == null || signatures.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, MethodSignature> deduped = new LinkedHashMap<>();
        for (MethodSignature signature : signatures) {
            if (signature == null) {
                continue;
            }
            String key = signature.getMethodName() + '#' + signature.getParameterTypes() + '#' + signature.getReturnType();
            deduped.putIfAbsent(key, signature);
        }
        return new ArrayList<>(deduped.values());
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
