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
    private final StaticDependencyResolver staticDependencyResolver;
    private final MethodSignatureRegistry typeRegistry;
    private final SemanticMetadataMerger metadataMerger;

    public SemanticMethodAnalyzer(MethodSignatureRegistry typeRegistry) {
        this(typeRegistry, new StaticDependencyResolver());
    }

    public SemanticMethodAnalyzer(MethodSignatureRegistry typeRegistry,
                                  StaticDependencyResolver staticDependencyResolver) {
        this.typeRegistry = typeRegistry == null ? new MethodSignatureRegistry() : typeRegistry;
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

        LinkedHashSet<ResolvedType> discoveredTypes = new LinkedHashSet<>();
        context.getFields().values().forEach(type -> addDiscoveredType(discoveredTypes, type));
        context.getParameters().values().forEach(type -> addDiscoveredType(discoveredTypes, type));
        ResolvedType declaredReturn = ResolvedType.of(info.getReturnType());
        addDiscoveredType(discoveredTypes, declaredReturn);

        SemanticMethodVisitor visitor = new SemanticMethodVisitor(resolver, context);
        declaration.getBody().ifPresent(body -> body.accept(visitor, context));

        visitor.getRawDomainTypes().forEach(type -> addDiscoveredType(discoveredTypes, type));
        visitor.getRawMethodUsages().values().forEach(list -> list.forEach(usage -> {
            addDiscoveredType(discoveredTypes, usage.getOwnerType());
            usage.getParameterTypes().forEach(type -> addDiscoveredType(discoveredTypes, type));
            addDiscoveredType(discoveredTypes, usage.getReturnType());
        }));

        Map<String, ResolvedType> normalizedDomainTypes = metadataMerger.normalizeDomainTypes(discoveredTypes);
        Map<String, List<MethodSignature>> typeMethods = metadataMerger.buildTypeMethods(normalizedDomainTypes);
        Map<String, List<ConstructorSignature>> typeConstructors = metadataMerger.buildTypeConstructors(normalizedDomainTypes);
        List<StaticCall> staticCalls = staticDependencyResolver.resolve(visitor.getRawStaticCalls());

        Set<String> domainTypes = new LinkedHashSet<>(normalizedDomainTypes.keySet());
        SemanticAnalysisResult result = new SemanticAnalysisResult(domainTypes, typeMethods, typeConstructors, staticCalls);
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

    private void addDiscoveredType(Set<ResolvedType> discoveredTypes, ResolvedType type) {
        if (discoveredTypes == null || type == null || type.isUnknown()) {
            return;
        }
        discoveredTypes.add(type);
    }
}
