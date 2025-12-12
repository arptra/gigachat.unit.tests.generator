package com.example.importanalyzer.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClassIndex {
    private final Map<String, List<ClassIndexEntry>> bySimpleName = new ConcurrentHashMap<>();
    private final Map<String, ClassIndexEntry> byFqn = new ConcurrentHashMap<>();
    private final Map<String, List<ClassIndexEntry>> byPackage = new ConcurrentHashMap<>();

    public void addEntry(ClassIndexEntry entry) {
        byFqn.put(entry.fullyQualifiedName(), entry);
        bySimpleName.computeIfAbsent(entry.simpleName(), k -> Collections.synchronizedList(new ArrayList<>())).add(entry);
        String pkg = packageName(entry.fullyQualifiedName());
        byPackage.computeIfAbsent(pkg, k -> Collections.synchronizedList(new ArrayList<>())).add(entry);
    }

    public ClassIndexEntry getByFqn(String fqn) {
        return byFqn.get(fqn);
    }

    public List<ClassIndexEntry> bySimpleName(String simpleName) {
        return bySimpleName.getOrDefault(simpleName, List.of());
    }

    public List<ClassIndexEntry> byPackage(String pkg) {
        return byPackage.getOrDefault(pkg, List.of());
    }

    public Set<String> packages() {
        return byPackage.keySet();
    }

    public int size() {
        return byFqn.size();
    }

    private String packageName(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(0, idx) : "";
    }

    public Map<String, ClassIndexEntry> asFqnMap() {
        return Collections.unmodifiableMap(byFqn);
    }

    public Map<String, List<ClassIndexEntry>> asSimpleNameMap() {
        return Collections.unmodifiableMap(bySimpleName);
    }
}
