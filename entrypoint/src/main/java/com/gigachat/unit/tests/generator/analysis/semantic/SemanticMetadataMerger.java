package com.gigachat.unit.tests.generator.analysis.semantic;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves semantic metadata for discovered types using only the registry data.
 */
public class SemanticMetadataMerger {
    private final MethodSignatureRegistry registry;

    public SemanticMetadataMerger(MethodSignatureRegistry registry) {
        this.registry = registry;
    }

    public Map<String, ResolvedType> normalizeDomainTypes(Set<ResolvedType> types) {
        if (types == null || types.isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (ResolvedType type : types) {
            collectTypeNames(type, candidates);
        }
        List<String> sorted = new ArrayList<>(candidates);
        Collections.sort(sorted);
        LinkedHashMap<String, ResolvedType> normalized = new LinkedHashMap<>();
        for (String candidate : sorted) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            ResolvedType resolved = ResolvedType.of(candidate);
            if (shouldSkip(resolved)) {
                continue;
            }
            normalized.put(candidate, resolved);
        }
        return Map.copyOf(normalized);
    }

    public Map<String, List<MethodSignature>> buildTypeMethods(Map<String, ResolvedType> domainTypes) {
        if (domainTypes == null || domainTypes.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, List<MethodSignature>> result = new LinkedHashMap<>();
        domainTypes.forEach((name, resolved) -> {
            List<MethodSignature> signatures = convertMethodMetadata(name, resolved);
            if (!signatures.isEmpty()) {
                result.put(name, signatures);
            }
        });
        return Map.copyOf(result);
    }

    public Map<String, List<ConstructorSignature>> buildTypeConstructors(Map<String, ResolvedType> domainTypes) {
        if (domainTypes == null || domainTypes.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, List<ConstructorSignature>> result = new LinkedHashMap<>();
        domainTypes.forEach((name, resolved) -> {
            List<ConstructorSignature> signatures = convertConstructors(name, resolved);
            if (!signatures.isEmpty()) {
                result.put(name, signatures);
            }
        });
        return Map.copyOf(result);
    }

    private List<MethodSignature> convertMethodMetadata(String domainType, ResolvedType resolvedType) {
        List<String> metadata = fetchRegistryMethods(resolvedType);
        if (metadata.isEmpty()) {
            return List.of();
        }
        List<MethodSignature> signatures = new ArrayList<>(metadata.size());
        for (String signature : metadata) {
            MethodSignature parsed = parseMethodSignature(resolvedType.getName(), signature);
            if (parsed == null) {
                continue;
            }
            signatures.add(new MethodSignature(domainType,
                    parsed.getMethodName(),
                    parsed.getParameterTypes(),
                    parsed.getReturnType()));
        }
        return List.copyOf(deduplicateMethodSignatures(signatures));
    }

    private List<String> fetchRegistryMethods(ResolvedType type) {
        List<String> direct = registry.getMethods(type.describe());
        if (!direct.isEmpty()) {
            return direct;
        }
        return registry.getMethods(type.getName());
    }

    private MethodSignature parseMethodSignature(String ownerType, String signature) {
        if (signature == null || signature.isBlank()) {
            return null;
        }
        int start = signature.indexOf('(');
        int end = signature.lastIndexOf(')');
        if (start < 0 || end < start) {
            return null;
        }
        String before = signature.substring(0, start).trim();
        String inside = signature.substring(start + 1, end);
        String methodName = before;
        String returnType = TypeResolver.UNKNOWN_TYPE;
        int lastSpace = before.lastIndexOf(' ');
        if (lastSpace >= 0 && lastSpace + 1 < before.length()) {
            methodName = before.substring(lastSpace + 1);
            returnType = before.substring(0, lastSpace);
        }
        List<String> params = parseParameterTypes(inside);
        return new MethodSignature(ResolvedType.normalise(ownerType), methodName, params, ResolvedType.normalise(returnType));
    }

    private List<String> parseParameterTypes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> params = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                params.add(extractTypeToken(current.toString()));
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        if (current.length() > 0) {
            params.add(extractTypeToken(current.toString()));
        }
        return params;
    }

    private String extractTypeToken(String token) {
        if (token == null) {
            return "";
        }
        String trimmed = token.trim();
        int lastSpace = lastSpaceOutsideGenerics(trimmed);
        if (lastSpace >= 0 && lastSpace + 1 < trimmed.length()) {
            trimmed = trimmed.substring(0, lastSpace);
        }
        return ResolvedType.of(trimmed).describe();
    }

    private int lastSpaceOutsideGenerics(String value) {
        int depth = 0;
        int lastSpace = -1;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                depth = Math.max(0, depth - 1);
            } else if (ch == ' ' && depth == 0) {
                lastSpace = i;
            }
        }
        return lastSpace;
    }

    private List<ConstructorSignature> convertConstructors(String domainType, ResolvedType resolvedType) {
        List<ConstructorMetadata> constructors = fetchConstructors(resolvedType);
        if (constructors.isEmpty()) {
            return List.of();
        }
        List<ConstructorSignature> signatures = new ArrayList<>(constructors.size());
        for (ConstructorMetadata metadata : constructors) {
            if (metadata == null) {
                continue;
            }
            List<String> parameterTypes = resolveConstructorParameters(metadata);
            signatures.add(new ConstructorSignature(domainType, parameterTypes, metadata.signature()));
        }
        return List.copyOf(deduplicateConstructors(signatures));
    }

    private List<ConstructorMetadata> fetchConstructors(ResolvedType type) {
        List<ConstructorMetadata> direct = registry.getConstructorsForClass(type.describe());
        if (!direct.isEmpty()) {
            return direct;
        }
        return registry.getConstructorsForClass(type.getName());
    }

    private List<String> resolveConstructorParameters(ConstructorMetadata metadata) {
        if (metadata == null) {
            return List.of();
        }
        List<ParameterMetadata> parameterMetadata = metadata.parameters();
        if (parameterMetadata != null && !parameterMetadata.isEmpty()) {
            List<String> parameters = new ArrayList<>(parameterMetadata.size());
            for (ParameterMetadata parameter : parameterMetadata) {
                if (parameter == null) {
                    continue;
                }
                parameters.add(ResolvedType.normalise(parameter.type()));
            }
            if (!parameters.isEmpty()) {
                return parameters;
            }
        }
        String rawSignature = metadata.signature();
        if (rawSignature == null) {
            return List.of();
        }
        int start = rawSignature.indexOf('(');
        int end = rawSignature.lastIndexOf(')');
        if (start < 0 || end <= start) {
            return List.of();
        }
        String inside = rawSignature.substring(start + 1, end);
        return parseParameterTypes(inside);
    }

    private List<MethodSignature> deduplicateMethodSignatures(List<MethodSignature> signatures) {
        if (signatures == null || signatures.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, MethodSignature> deduped = new LinkedHashMap<>();
        for (MethodSignature signature : signatures) {
            if (signature == null) {
                continue;
            }
            String key = signature.getTypeName() + '#' + signature.getMethodName() + '#' + signature.getParameterTypes()
                    + '#' + signature.getReturnType();
            deduped.putIfAbsent(key, signature);
        }
        return new ArrayList<>(deduped.values());
    }

    private List<ConstructorSignature> deduplicateConstructors(List<ConstructorSignature> signatures) {
        if (signatures == null || signatures.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, ConstructorSignature> deduped = new LinkedHashMap<>();
        for (ConstructorSignature signature : signatures) {
            if (signature == null) {
                continue;
            }
            String key = signature.getTypeName() + '#' + signature.getParameterTypes();
            deduped.putIfAbsent(key, signature);
        }
        return new ArrayList<>(deduped.values());
    }

    private void collectTypeNames(ResolvedType type, Set<String> bucket) {
        if (bucket == null || type == null || type.isUnknown()) {
            return;
        }
        String described = type.describe();
        if (!described.isBlank()) {
            bucket.add(described);
        }
        for (String flattened : type.flatten()) {
            if (flattened != null && !flattened.isBlank()) {
                bucket.add(ResolvedType.normalise(flattened));
            }
        }
    }

    private boolean shouldSkip(ResolvedType type) {
        if (type == null || type.isUnknown()) {
            return true;
        }
        String described = type.describe();
        if ("Object".equals(described) || "java.lang.Object".equalsIgnoreCase(described)) {
            return true;
        }
        return false;
    }
}
