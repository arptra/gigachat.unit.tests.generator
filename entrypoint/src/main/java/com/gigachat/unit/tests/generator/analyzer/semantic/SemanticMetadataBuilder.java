package com.gigachat.unit.tests.generator.analyzer.semantic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

class SemanticMetadataBuilder {
    private final SignatureRegistry registry;
    private final LinkedHashSet<TypeName> domainTypes = new LinkedHashSet<>();
    private final Map<TypeName, LinkedHashSet<MethodSignature>> usedMethods = new LinkedHashMap<>();
    private final Map<TypeName, LinkedHashSet<ConstructorSignature>> usedConstructors = new LinkedHashMap<>();
    private final List<StaticInvocation> staticCalls = new ArrayList<>();
    private final LinkedHashSet<TypeName> functionalInterfaces = new LinkedHashSet<>();

    SemanticMetadataBuilder(SignatureRegistry registry) {
        this.registry = registry;
    }

    void registerDomainType(TypeName type) {
        if (TypeFilters.isDomainType(type)) {
            domainTypes.add(type);
        }
    }

    void registerContainerType(TypeName type) {
        if (type == null) {
            return;
        }
        if (type.isContainer()) {
            registerDomainType(type);
            for (TypeName argument : type.typeArguments()) {
                registerDomainType(argument);
            }
        } else {
            registerDomainType(type);
        }
    }

    void registerMethodUsage(TypeName owner, MethodSignature signature) {
        if (!TypeFilters.isDomainType(owner) || signature == null) {
            return;
        }
        usedMethods.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(signature);
    }

    void registerFunctionalInterface(TypeName type) {
        if (type != null && !type.isUnknown()) {
            functionalInterfaces.add(type);
        }
    }

    void registerConstructorUsage(TypeName owner, ConstructorSignature signature) {
        if (!TypeFilters.isDomainType(owner) || signature == null) {
            return;
        }
        usedConstructors.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(signature);
    }

    void registerStaticInvocation(TypeName owner, MethodSignature signature) {
        if (owner == null || signature == null) {
            return;
        }
        staticCalls.add(new StaticInvocation(owner, signature));
    }

    SemanticMethodAnalysis build() {
        LinkedHashSet<TypeName> cleaned = new LinkedHashSet<>();
        for (TypeName type : domainTypes) {
            if (TypeFilters.shouldKeep(type)) {
                cleaned.add(type);
            }
        }
        Map<TypeName, List<MethodSignature>> methods = new LinkedHashMap<>();
        for (TypeName type : cleaned) {
            LinkedHashSet<MethodSignature> aggregate = new LinkedHashSet<>();
            aggregate.addAll(usedMethods.getOrDefault(type, new LinkedHashSet<>()));
            registry.methodsFor(type).forEach(aggregate::add);
            methods.put(type, List.copyOf(aggregate));
        }
        for (TypeName functional : functionalInterfaces) {
            methods.putIfAbsent(functional, List.of());
        }
        Map<TypeName, List<ConstructorSignature>> constructors = new LinkedHashMap<>();
        for (TypeName type : cleaned) {
            LinkedHashSet<ConstructorSignature> aggregate = new LinkedHashSet<>();
            aggregate.addAll(usedConstructors.getOrDefault(type, new LinkedHashSet<>()));
            registry.constructorsFor(type).forEach(aggregate::add);
            constructors.put(type, List.copyOf(aggregate));
        }
        return new SemanticMethodAnalysis(cleaned, methods, constructors, List.copyOf(staticCalls));
    }
}
