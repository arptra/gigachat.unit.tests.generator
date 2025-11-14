package com.gigachat.unit.tests.generator.analyzer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry that keeps track of discovered method and constructor signatures for classes scanned from the project.
 */
public class MethodSignatureRegistry {
    private final Map<String, Set<String>> classMethods = new LinkedHashMap<>();
    private final Map<String, Set<String>> classConstructors = new LinkedHashMap<>();
    private final Map<String, Map<String, Set<Integer>>> methodArityIndex = new HashMap<>();
    private final Map<String, Set<Integer>> constructorArityIndex = new HashMap<>();
    private final Map<String, List<ConstructorMetadata>> constructorsDetailed = new LinkedHashMap<>();

    public void registerMethod(String className, String signature) {
        String key = normaliseClassName(className);
        if (key.isEmpty() || signature == null || signature.isBlank()) {
            return;
        }
        String trimmedSignature = signature.trim();
        classMethods.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(trimmedSignature);
        String methodName = extractMethodName(trimmedSignature);
        if (!methodName.isEmpty()) {
            int arity = extractArity(trimmedSignature);
            methodArityIndex.computeIfAbsent(key, ignored -> new HashMap<>())
                    .computeIfAbsent(methodName, ignored -> new LinkedHashSet<>())
                    .add(arity);
        }
    }

    public void registerConstructor(String className, String signature) {
        registerConstructor(className, new ConstructorMetadata(signature, List.of()));
    }

    public void registerConstructor(String className, ConstructorMetadata metadata) {
        String key = normaliseClassName(className);
        if (key.isEmpty() || metadata == null || metadata.signature().isBlank()) {
            return;
        }
        ConstructorMetadata normalised = normaliseMetadata(metadata);
        classConstructors.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(normalised.signature());
        constructorsDetailed.computeIfAbsent(key, ignored -> new ArrayList<>());
        List<ConstructorMetadata> existing = constructorsDetailed.get(key);
        boolean replaced = false;
        for (int i = 0; i < existing.size(); i++) {
            ConstructorMetadata current = existing.get(i);
            if (current.signature().equals(normalised.signature())) {
                existing.set(i, normalised);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            existing.add(normalised);
        }
        int arity = extractArity(normalised.signature());
        constructorArityIndex.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(arity);
    }

    public void registerConstructorsIfAbsent(String className) {
        String key = normaliseClassName(className);
        if (key.isEmpty()) {
            return;
        }
        List<ConstructorMetadata> existing = constructorsDetailed.get(key);
        if (existing != null && !existing.isEmpty()) {
            return;
        }
        Set<String> constructors = classConstructors.get(key);
        if (constructors == null || constructors.isEmpty()) {
            return;
        }
        List<ConstructorMetadata> metadata = new ArrayList<>(constructors.size());
        for (String signature : constructors) {
            if (signature == null || signature.isBlank()) {
                continue;
            }
            metadata.add(new ConstructorMetadata(signature.trim(), List.of()));
        }
        if (metadata.isEmpty()) {
            return;
        }
        constructorsDetailed.put(key, List.copyOf(metadata));
    }

    public void registerMethodsIfAbsent(String className) {
        String key = normaliseClassName(className);
        if (key.isEmpty()) {
            return;
        }
        if (classMethods.containsKey(key)) {
            return;
        }
        classMethods.put(key, new LinkedHashSet<>());
        methodArityIndex.computeIfAbsent(key, ignored -> new HashMap<>());
    }

    public void registerPublicMethods(String className) {
        if (className == null || className.isBlank()) {
            return;
        }
        try {
            Class<?> clazz = Class.forName(className);
            Method[] methods = clazz.getMethods();
            for (Method method : methods) {
                if (method == null) {
                    continue;
                }
                if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                registerMethod(clazz.getName(), formatMethodSignature(method));
            }
        } catch (ClassNotFoundException ignored) {
            // Standard type not present in runtime classpath; ignore.
        }
    }

    public boolean methodExists(String className, String methodName, int argCount) {
        if (methodName == null || methodName.isBlank()) {
            return false;
        }
        int arity = Math.max(argCount, 0);
        String key = normaliseClassName(className);
        Map<String, Set<Integer>> methods = methodArityIndex.get(key);
        if (methods == null) {
            return false;
        }
        Set<Integer> arities = methods.get(methodName.trim());
        if (arities == null) {
            return false;
        }
        return arities.contains(arity);
    }

    public boolean constructorExists(String className, int argCount) {
        return hasConstructorWithArgCount(className, argCount);
    }

    public boolean hasConstructorWithArgCount(String className, int argCount) {
        int arity = Math.max(argCount, 0);
        String key = normaliseClassName(className);
        Set<Integer> arities = constructorArityIndex.get(key);
        if (arities == null) {
            return false;
        }
        return arities.contains(arity);
    }

    public boolean hasClass(String className) {
        String key = normaliseClassName(className);
        return classMethods.containsKey(key) || classConstructors.containsKey(key);
    }

    public boolean hasMethods(String className) {
        String key = normaliseClassName(className);
        Set<String> methods = classMethods.get(key);
        return methods != null && !methods.isEmpty();
    }

    public Set<String> methodsFor(String className) {
        String key = normaliseClassName(className);
        Set<String> methods = classMethods.get(key);
        if (methods == null) {
            return Set.of();
        }
        return Collections.unmodifiableSet(methods);
    }

    public List<String> getMethods(String className) {
        Set<String> methods = classMethods.get(normaliseClassName(className));
        if (methods == null || methods.isEmpty()) {
            return List.of();
        }
        return List.copyOf(methods);
    }

    public Map<String, List<String>> getMethodsDetailed() {
        LinkedHashMap<String, List<String>> snapshot = new LinkedHashMap<>();
        classMethods.forEach((key, value) -> snapshot.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(snapshot);
    }

    public Set<String> constructorsFor(String className) {
        String key = normaliseClassName(className);
        Set<String> constructors = classConstructors.get(key);
        if (constructors == null) {
            return Set.of();
        }
        return Collections.unmodifiableSet(constructors);
    }

    public Map<String, List<ConstructorMetadata>> getConstructorsDetailed() {
        LinkedHashMap<String, List<ConstructorMetadata>> snapshot = new LinkedHashMap<>();
        constructorsDetailed.forEach((key, value) -> snapshot.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(snapshot);
    }

    public List<ConstructorMetadata> getConstructorsForClass(String className) {
        String key = normaliseClassName(className);
        List<ConstructorMetadata> constructors = constructorsDetailed.get(key);
        if (constructors == null || constructors.isEmpty()) {
            return List.of();
        }
        return List.copyOf(constructors);
    }

    public boolean refreshConstructors(String className) {
        String key = normaliseClassName(className);
        if (key.isEmpty()) {
            return false;
        }
        registerConstructorsIfAbsent(key);
        List<ConstructorMetadata> constructors = constructorsDetailed.get(key);
        return constructors != null && !constructors.isEmpty();
    }

    private ConstructorMetadata normaliseMetadata(ConstructorMetadata metadata) {
        if (metadata == null) {
            return new ConstructorMetadata("", List.of());
        }
        return new ConstructorMetadata(metadata.signature(), normaliseParameters(metadata.parameters()));
    }

    private List<ParameterMetadata> normaliseParameters(List<ParameterMetadata> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return List.of();
        }
        List<ParameterMetadata> cleaned = new ArrayList<>(parameters.size());
        for (ParameterMetadata parameter : parameters) {
            if (parameter == null) {
                continue;
            }
            cleaned.add(parameter);
        }
        if (cleaned.isEmpty()) {
            return List.of();
        }
        return List.copyOf(cleaned);
    }

    private String extractMethodName(String signature) {
        int parenIndex = signature.indexOf('(');
        if (parenIndex <= 0) {
            return "";
        }
        String before = signature.substring(0, parenIndex).trim();
        int lastSpace = before.lastIndexOf(' ');
        if (lastSpace >= 0 && lastSpace + 1 < before.length()) {
            return before.substring(lastSpace + 1);
        }
        return before;
    }

    private int extractArity(String signature) {
        int start = signature.indexOf('(');
        int end = signature.lastIndexOf(')');
        if (start < 0 || end < start) {
            return 0;
        }
        String inside = signature.substring(start + 1, end).trim();
        if (inside.isEmpty()) {
            return 0;
        }
        int depth = 0;
        boolean inQuote = false;
        int arity = 1;
        for (int i = 0; i < inside.length(); i++) {
            char ch = inside.charAt(i);
            if (ch == '"' || ch == '\'') {
                inQuote = !inQuote;
            }
            if (inQuote) {
                continue;
            }
            if (ch == '<') {
                depth++;
                continue;
            }
            if (ch == '>') {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
                continue;
            }
            if (ch == ')' || ch == ']' || ch == '}') {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (ch == ',' && depth == 0) {
                arity++;
            }
        }
        return Math.max(arity, 0);
    }

    private String normaliseClassName(String className) {
        if (className == null) {
            return "";
        }
        String trimmed = className.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < trimmed.length()) {
            return trimmed.substring(lastDot + 1);
        }
        return trimmed;
    }

    private String formatMethodSignature(Method method) {
        StringBuilder builder = new StringBuilder();
        String returnType = method.getReturnType().getSimpleName();
        if (returnType != null && !returnType.isBlank()) {
            builder.append(returnType.trim()).append(' ');
        }
        builder.append(method.getName()).append('(');
        Parameter[] parameters = method.getParameters();
        List<String> parameterParts = new ArrayList<>(parameters.length);
        for (Parameter parameter : parameters) {
            if (parameter == null) {
                continue;
            }
            String type = parameter.getType().getSimpleName();
            String name = parameter.getName();
            if (type == null) {
                type = "Object";
            }
            if (name == null || name.isBlank()) {
                name = "arg" + parameterParts.size();
            }
            parameterParts.add(type + ' ' + name);
        }
        builder.append(String.join(", ", parameterParts));
        builder.append(')');
        return builder.toString();
    }
}
