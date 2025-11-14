package com.gigachat.unit.tests.generator.analyzer.semantic;

import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.dto.ClassMetadata;
import com.gigachat.unit.tests.generator.dto.FieldMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adapter that exposes {@link MethodSignatureRegistry} data via the semantic {@link SignatureRegistry} contract.
 */
public final class MethodSignatureRegistryAdapter implements SignatureRegistry {
    private final MethodSignatureRegistry registry;
    private final Map<String, ClassMetadata> classMetadata;
    private final Map<String, List<MethodSignature>> methodCache = new HashMap<>();
    private final Map<String, List<ConstructorSignature>> constructorCache = new HashMap<>();

    public MethodSignatureRegistryAdapter(MethodSignatureRegistry registry, List<ClassMetadata> metadata) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.classMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            for (ClassMetadata entry : metadata) {
                if (entry == null) {
                    continue;
                }
                String name = normaliseClassName(entry.getClassName());
                if (!name.isBlank()) {
                    classMetadata.putIfAbsent(name, entry);
                }
            }
        }
    }

    @Override
    public TypeName resolveFieldType(TypeName ownerType, String fieldName) {
        if (ownerType == null || fieldName == null || fieldName.isBlank()) {
            return TypeName.unknown();
        }
        ClassMetadata metadata = classMetadata.get(normaliseClassName(ownerType.simpleName()));
        if (metadata == null) {
            return TypeName.unknown();
        }
        for (FieldMetadata field : metadata.getFields()) {
            if (field == null) {
                continue;
            }
            if (fieldName.equals(field.getName())) {
                return TypeName.of(field.getTypeName());
            }
        }
        return TypeName.unknown();
    }

    @Override
    public MethodSignature resolveMethod(TypeName ownerType, String methodName, List<TypeName> argumentTypes) {
        List<MethodSignature> methods = methodsFor(ownerType);
        for (MethodSignature signature : methods) {
            if (!signature.name().equals(methodName)) {
                continue;
            }
            if (argumentsMatch(signature.parameterTypes(), argumentTypes)) {
                return signature;
            }
        }
        return null;
    }

    @Override
    public ConstructorSignature resolveConstructor(TypeName ownerType, List<TypeName> argumentTypes) {
        List<ConstructorSignature> constructors = constructorsFor(ownerType);
        for (ConstructorSignature signature : constructors) {
            if (argumentsMatch(signature.parameterTypes(), argumentTypes)) {
                return signature;
            }
        }
        return null;
    }

    @Override
    public List<MethodSignature> methodsFor(TypeName ownerType) {
        TypeName owner = ownerType == null ? TypeName.unknown() : ownerType;
        String key = normaliseClassName(owner.simpleName());
        return methodCache.computeIfAbsent(key, ignored -> parseMethods(owner));
    }

    @Override
    public List<ConstructorSignature> constructorsFor(TypeName ownerType) {
        TypeName owner = ownerType == null ? TypeName.unknown() : ownerType;
        String key = normaliseClassName(owner.simpleName());
        return constructorCache.computeIfAbsent(key, ignored -> parseConstructors(owner));
    }

    @Override
    public TypeName inferOwner(String methodName, List<TypeName> argumentTypes) {
        if (methodName == null || methodName.isBlank()) {
            return TypeName.unknown();
        }
        Map<String, List<String>> methods = registry.getMethodsDetailed();
        for (Map.Entry<String, List<String>> entry : methods.entrySet()) {
            String className = entry.getKey();
            for (String rawSignature : entry.getValue()) {
                MethodSignature signature = parseMethodSignature(rawSignature);
                if (signature == null || !methodName.equals(signature.name())) {
                    continue;
                }
                if (argumentsMatch(signature.parameterTypes(), argumentTypes)) {
                    return TypeName.of(className);
                }
            }
        }
        return TypeName.unknown();
    }

    @Override
    public boolean isStaticCall(TypeName ownerType, String methodName) {
        return false;
    }

    private List<MethodSignature> parseMethods(TypeName ownerType) {
        List<String> raw = registry.getMethods(ownerType.rawName());
        if (raw.isEmpty()) {
            raw = registry.getMethods(ownerType.simpleName());
        }
        if (raw.isEmpty()) {
            return List.of();
        }
        List<MethodSignature> signatures = new ArrayList<>(raw.size());
        for (String signature : raw) {
            MethodSignature parsed = parseMethodSignature(signature);
            if (parsed != null) {
                signatures.add(parsed);
            }
        }
        return Collections.unmodifiableList(signatures);
    }

    private List<ConstructorSignature> parseConstructors(TypeName ownerType) {
        List<ConstructorMetadata> metadata = registry.getConstructorsForClass(ownerType.rawName());
        if (metadata.isEmpty()) {
            metadata = registry.getConstructorsForClass(ownerType.simpleName());
        }
        if (metadata.isEmpty()) {
            return List.of();
        }
        List<ConstructorSignature> signatures = new ArrayList<>(metadata.size());
        for (ConstructorMetadata constructor : metadata) {
            signatures.add(new ConstructorSignature(ownerType, extractParameterTypes(constructor.parameters())));
        }
        return Collections.unmodifiableList(signatures);
    }

    private MethodSignature parseMethodSignature(String signature) {
        if (signature == null || signature.isBlank()) {
            return null;
        }
        int start = signature.indexOf('(');
        int end = signature.lastIndexOf(')');
        if (start < 0 || end < start) {
            return null;
        }
        String header = signature.substring(0, start).trim();
        String params = signature.substring(start + 1, end).trim();
        int lastSpace = header.lastIndexOf(' ');
        String methodName;
        String returnType;
        if (lastSpace < 0) {
            methodName = header;
            returnType = "";
        } else {
            methodName = header.substring(lastSpace + 1).trim();
            returnType = header.substring(0, lastSpace).trim();
        }
        List<TypeName> parameterTypes = parseParameterTypes(params);
        return new MethodSignature(methodName, TypeName.of(returnType), parameterTypes, false);
    }

    private List<TypeName> parseParameterTypes(String params) {
        if (params == null || params.isBlank()) {
            return List.of();
        }
        List<String> parts = splitParameters(params);
        if (parts.isEmpty()) {
            return List.of();
        }
        List<TypeName> types = new ArrayList<>(parts.size());
        for (String part : parts) {
            String cleaned = part.trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            int lastSpace = cleaned.lastIndexOf(' ');
            String type = lastSpace < 0 ? cleaned : cleaned.substring(0, lastSpace).trim();
            if (type.endsWith("...")) {
                type = type.substring(0, type.length() - 3) + "[]";
            }
            types.add(TypeName.of(type));
        }
        return Collections.unmodifiableList(types);
    }

    private List<TypeName> extractParameterTypes(List<ParameterMetadata> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return List.of();
        }
        List<TypeName> types = new ArrayList<>(parameters.size());
        for (ParameterMetadata parameter : parameters) {
            if (parameter == null) {
                continue;
            }
            types.add(TypeName.of(parameter.type()));
        }
        return Collections.unmodifiableList(types);
    }

    private List<String> splitParameters(String params) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inQuote = false;
        for (int i = 0; i < params.length(); i++) {
            char ch = params.charAt(i);
            if (ch == '\'' || ch == '"') {
                inQuote = !inQuote;
            }
            if (!inQuote) {
                if (ch == '<' || ch == '(' || ch == '[') {
                    depth++;
                } else if (ch == '>' || ch == ')' || ch == ']') {
                    depth = Math.max(0, depth - 1);
                } else if (ch == ',' && depth == 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(ch);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private boolean argumentsMatch(List<TypeName> declared, List<TypeName> actual) {
        if (declared == null || declared.isEmpty()) {
            return actual == null || actual.isEmpty();
        }
        if (actual == null) {
            return declared.isEmpty();
        }
        if (declared.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < declared.size(); i++) {
            TypeName expected = declared.get(i);
            TypeName candidate = actual.get(i);
            if (candidate == null || candidate.isUnknown()) {
                continue;
            }
            if (expected == null || expected.isUnknown()) {
                continue;
            }
            if (!typeEquals(expected, candidate)) {
                return false;
            }
        }
        return true;
    }

    private boolean typeEquals(TypeName expected, TypeName candidate) {
        if (expected.name().equals(candidate.name())) {
            return true;
        }
        return expected.simpleName().equalsIgnoreCase(candidate.simpleName());
    }

    private String normaliseClassName(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < trimmed.length()) {
            return trimmed.substring(lastDot + 1);
        }
        return trimmed;
    }
}
