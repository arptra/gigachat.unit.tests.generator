package com.gigachat.unit.tests.generator.analyzer.semantic;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Default implementation of {@link SemanticAnalyzer} that walks the AST and collects semantic metadata.
 */
public class SemanticAnalyzerImpl implements SemanticAnalyzer {
    @Override
    public SemanticMethodAnalysis analyze(MethodDeclaration declaration, SignatureRegistry registry) {
        if (declaration == null || registry == null || declaration.getBody().isEmpty()) {
            return new SemanticMethodAnalysis(Set.of(), Map.of(), Map.of(), List.of());
        }
        TypeName declaringType = resolveDeclaringType(declaration);
        SemanticMetadataBuilder metadataBuilder = new SemanticMetadataBuilder(registry);
        TypeResolver typeResolver = new TypeResolver(declaringType);
        for (Parameter parameter : declaration.getParameters()) {
            TypeName type = typeResolver.resolveType(parameter.getType());
            typeResolver.registerLocal(parameter.getNameAsString(), type);
            metadataBuilder.registerContainerType(type);
        }
        MethodUsageCollector collector = new MethodUsageCollector(registry, typeResolver, metadataBuilder);
        collector.collect(declaration);
        return metadataBuilder.build();
    }

    private TypeName resolveDeclaringType(MethodDeclaration declaration) {
        return declaration.findAncestor(ClassOrInterfaceDeclaration.class)
                .flatMap(ClassOrInterfaceDeclaration::getFullyQualifiedName)
                .map(TypeName::of)
                .orElse(TypeName.unknown());
    }
}
