package com.gigachat.unit.tests.generator.analyzer.semantic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

class SemanticMetadataBuilder {
    private final LinkedHashSet<TypeName> domainTypes = new LinkedHashSet<>();
    private final Map<TypeName, LinkedHashSet<MethodSignature>> usedMethods = new LinkedHashMap<>();
    private final Map<TypeName, LinkedHashSet<ConstructorSignature>> usedConstructors = new LinkedHashMap<>();
    private final List<StaticInvocation> staticCalls = new ArrayList<>();

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
            for (TypeName argument : type.typeArguments()) {
                registerDomainType(argument);
            }
        } else {
            registerDomainType(type);
        }
    }

    void registerOwner(TypeName owner) {
        if (owner == null) {
            return;
        }
        if (owner.isContainer()) {
            for (TypeName argument : owner.typeArguments()) {
                registerDomainType(argument);
            }
            return;
        }
        if (!shouldTrackOwner(owner)) {
            return;
        }
        registerDomainType(owner);
    }

    void registerMethodUsage(TypeName owner, MethodSignature signature) {
        if (!shouldTrackOwner(owner) || signature == null) {
            return;
        }
        if (signature.returnType().isUnknown()) {
            return;
        }
        LinkedHashSet<MethodSignature> methods = usedMethods.computeIfAbsent(owner,
                ignored -> new LinkedHashSet<>());
        if (isDuplicate(methods, signature)) {
            return;
        }
        methods.add(signature);
    }

    void registerFunctionalInterface(TypeName type) {
        // functional interfaces are excluded from the final metadata
    }

    void registerConstructorUsage(TypeName owner, ConstructorSignature signature) {
        if (!shouldTrackOwner(owner) || signature == null) {
            return;
        }
        usedConstructors.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(signature);
    }

    void registerStaticInvocation(TypeName owner, MethodSignature signature) {
        if (!shouldTrackOwner(owner) || signature == null) {
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
        for (Map.Entry<TypeName, LinkedHashSet<MethodSignature>> entry : usedMethods.entrySet()) {
            if (!cleaned.contains(entry.getKey()) || entry.getValue().isEmpty()) {
                continue;
            }
            methods.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        Map<TypeName, List<ConstructorSignature>> constructors = new LinkedHashMap<>();
        for (Map.Entry<TypeName, LinkedHashSet<ConstructorSignature>> entry : usedConstructors.entrySet()) {
            if (!cleaned.contains(entry.getKey()) || entry.getValue().isEmpty()) {
                continue;
            }
            constructors.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new SemanticMethodAnalysis(cleaned, methods, constructors, List.copyOf(staticCalls));
    }

    private boolean shouldTrackOwner(TypeName owner) {
        if (owner == null || owner.isUnknown()) {
            return false;
        }
        if ("internal_state".equalsIgnoreCase(owner.name())) {
            return false;
        }
        if (owner.isJavaType() || owner.isContainer() || owner.isFunctionalInterface()) {
            return false;
        }
        return true;
    }

    private boolean isDuplicate(LinkedHashSet<MethodSignature> existing, MethodSignature candidate) {
        return existing.stream().anyMatch(signature ->
                signature.name().equals(candidate.name())
                        && signature.parameterTypes().equals(candidate.parameterTypes()));
    }
}
