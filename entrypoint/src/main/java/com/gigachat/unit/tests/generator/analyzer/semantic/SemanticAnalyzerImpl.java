package com.gigachat.unit.tests.generator.analyzer.semantic;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link SemanticAnalyzer} that walks the AST and collects semantic metadata.
 */
public class SemanticAnalyzerImpl implements SemanticAnalyzer {
    @Override
    public SemanticMethodAnalysis analyze(MethodDeclaration declaration, SignatureRegistry registry) {
        if (declaration == null || registry == null || declaration.getBody().isEmpty()) {
            return new SemanticMethodAnalysis(Set.of(), Map.of(), Map.of(), List.of());
        }
        ClassOrInterfaceDeclaration owningClass = declaration.findAncestor(ClassOrInterfaceDeclaration.class)
                .orElse(null);
        TypeName declaringType = resolveDeclaringType(owningClass);
        InternalFieldContext fieldContext = collectInternalFields(owningClass);
        SemanticMetadataBuilder metadataBuilder = new SemanticMetadataBuilder();
        TypeResolver typeResolver = new TypeResolver(declaringType);
        for (Parameter parameter : declaration.getParameters()) {
            TypeName type = typeResolver.resolveType(parameter.getType());
            typeResolver.registerLocal(parameter.getNameAsString(), type);
            metadataBuilder.registerContainerType(type);
        }
        MethodUsageCollector collector = new MethodUsageCollector(
                registry,
                typeResolver,
                metadataBuilder,
                fieldContext.names());
        collector.collect(declaration);
        return metadataBuilder.build();
    }

    private TypeName resolveDeclaringType(ClassOrInterfaceDeclaration declaration) {
        if (declaration == null) {
            return TypeName.unknown();
        }
        return declaration.getFullyQualifiedName()
                .map(TypeName::of)
                .orElse(TypeName.unknown());
    }

    private InternalFieldContext collectInternalFields(ClassOrInterfaceDeclaration declaration) {
        if (declaration == null) {
            return new InternalFieldContext(Set.of());
        }
        Set<String> names = declaration.getFields().stream()
                .flatMap(field -> field.getVariables().stream())
                .map(VariableDeclarator::getNameAsString)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        return new InternalFieldContext(names);
    }

    private record InternalFieldContext(Set<String> names) {
        private InternalFieldContext {
            names = names == null ? Set.of() : Set.copyOf(names);
        }
    }
}
