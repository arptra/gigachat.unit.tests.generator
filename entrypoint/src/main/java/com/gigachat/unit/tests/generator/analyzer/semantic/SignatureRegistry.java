package com.gigachat.unit.tests.generator.analyzer.semantic;

import java.util.List;

/**
 * Registry with method and constructor metadata for every type in the project.
 */
public interface SignatureRegistry {
    TypeName resolveFieldType(TypeName ownerType, String fieldName);

    MethodSignature resolveMethod(TypeName ownerType, String methodName, List<TypeName> argumentTypes);

    ConstructorSignature resolveConstructor(TypeName ownerType, List<TypeName> argumentTypes);

    List<MethodSignature> methodsFor(TypeName ownerType);

    List<ConstructorSignature> constructorsFor(TypeName ownerType);

    TypeName inferOwner(String methodName, List<TypeName> argumentTypes);

    boolean isStaticCall(TypeName ownerType, String methodName);

    default List<MethodSignature> functionalInterfacesFor(TypeName ownerType) {
        return List.of();
    }
}
