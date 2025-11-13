package com.gigachat.unit.tests.generator.analysis.semantic;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges semantic metadata discovered in the AST with the global registry entries.
 */
public class SemanticMetadataMerger {
    private final MethodSignatureRegistry registry;

    public SemanticMetadataMerger(MethodSignatureRegistry registry) {
        this.registry = registry;
    }

    public Map<String, List<MethodSignature>> mergeMethods(Set<String> domainTypes,
                                                           Map<String, List<MethodSignature>> semanticMethods) {
        LinkedHashMap<String, List<MethodSignature>> merged = new LinkedHashMap<>();
        if (semanticMethods != null) {
            semanticMethods.forEach((type, signatures) -> {
                if (type == null || type.isBlank()) {
                    return;
                }
                merged.put(normalise(type), new ArrayList<>(signatures));
            });
        }
        for (String type : domainTypes) {
            if (type == null || type.isBlank()) {
                continue;
            }
            List<String> registryMethods = registry.getMethods(type);
            if (registryMethods.isEmpty()) {
                continue;
            }
            List<MethodSignature> converted = convertMethodMetadata(type, registryMethods);
            merged.computeIfAbsent(normalise(type), ignored -> new ArrayList<>()).addAll(converted);
        }
        deduplicateMethods(merged);
        merged.replaceAll((type, list) -> list == null ? List.of() : List.copyOf(list));
        return Map.copyOf(merged);
    }

    public Map<String, List<ConstructorSignature>> mergeConstructors(Set<String> domainTypes,
                                                                      Map<String, List<ConstructorSignature>> semanticConstructors) {
        LinkedHashMap<String, List<ConstructorSignature>> merged = new LinkedHashMap<>();
        if (semanticConstructors != null) {
            semanticConstructors.forEach((type, signatures) -> {
                if (type == null || type.isBlank()) {
                    return;
                }
                merged.put(normalise(type), new ArrayList<>(signatures));
            });
        }
        for (String type : domainTypes) {
            if (type == null || type.isBlank()) {
                continue;
            }
            List<ConstructorMetadata> constructors = registry.getConstructorsForClass(type);
            if (constructors.isEmpty()) {
                continue;
            }
            List<ConstructorSignature> converted = convertConstructors(constructors);
            merged.computeIfAbsent(normalise(type), ignored -> new ArrayList<>()).addAll(converted);
        }
        deduplicateConstructors(merged);
        merged.replaceAll((type, list) -> list == null ? List.of() : List.copyOf(list));
        return Map.copyOf(merged);
    }

    private List<MethodSignature> convertMethodMetadata(String ownerType, List<String> metadata) {
        List<MethodSignature> signatures = new ArrayList<>(metadata.size());
        for (String signature : metadata) {
            MethodSignature parsed = parseMethodSignature(ownerType, signature);
            if (parsed != null) {
                signatures.add(parsed);
            }
        }
        return signatures;
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
                params.add(ResolvedType.normalise(current.toString()));
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        if (current.length() > 0) {
            params.add(ResolvedType.normalise(current.toString()));
        }
        return params;
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
                parameterTypes.add(ResolvedType.normalise(parameterMetadata.type()));
            }
            signatures.add(new ConstructorSignature(parseConstructorOwner(metadata.signature()), parameterTypes, metadata.signature()));
        }
        return signatures;
    }

    private String parseConstructorOwner(String signature) {
        if (signature == null || signature.isBlank()) {
            return "";
        }
        int parenIndex = signature.indexOf('(');
        if (parenIndex <= 0) {
            return ResolvedType.normalise(signature);
        }
        String before = signature.substring(0, parenIndex).trim();
        int space = before.lastIndexOf(' ');
        if (space >= 0 && space + 1 < before.length()) {
            return ResolvedType.normalise(before.substring(space + 1));
        }
        return ResolvedType.normalise(before);
    }

    private void deduplicateMethods(Map<String, List<MethodSignature>> merged) {
        merged.replaceAll((type, list) -> {
            if (list == null || list.isEmpty()) {
                return List.of();
            }
            LinkedHashMap<String, MethodSignature> deduped = new LinkedHashMap<>();
            for (MethodSignature signature : list) {
                if (signature == null || signature.getTypeName().isBlank()) {
                    continue;
                }
                String key = signature.getMethodName() + '#' + signature.getParameterTypes() + '#' + signature.getReturnType();
                deduped.putIfAbsent(key, signature);
            }
            return new ArrayList<>(deduped.values());
        });
    }

    private void deduplicateConstructors(Map<String, List<ConstructorSignature>> merged) {
        merged.replaceAll((type, list) -> {
            if (list == null || list.isEmpty()) {
                return List.of();
            }
            LinkedHashMap<String, ConstructorSignature> deduped = new LinkedHashMap<>();
            for (ConstructorSignature signature : list) {
                if (signature == null || signature.getTypeName().isBlank()) {
                    continue;
                }
                String key = signature.getTypeName() + '#' + signature.getParameterTypes();
                deduped.putIfAbsent(key, signature);
            }
            return new ArrayList<>(deduped.values());
        });
    }

    private String normalise(String type) {
        return ResolvedType.normalise(type);
    }
}
