package com.gigachat.unit.tests.generator.reasoning.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Resolves project and classpath symbols for bounded reasoning tool actions.
 */
public class SymbolLookupService {
    private static final List<String> COMMON_JDK_PACKAGES = List.of(
            "java.lang",
            "java.util",
            "java.util.concurrent",
            "java.util.function",
            "java.util.regex",
            "java.util.stream",
            "java.time",
            "java.math",
            "java.io",
            "java.net",
            "java.nio",
            "java.nio.charset",
            "java.nio.file",
            "java.sql",
            "java.text",
            "java.security",
            "java.lang.reflect"
    );
    private static final Map<String, List<String>> COMMON_STATIC_IMPORTS = Map.ofEntries(
            Map.entry("assertEquals", List.of("org.junit.jupiter.api.Assertions.assertEquals")),
            Map.entry("assertNotEquals", List.of("org.junit.jupiter.api.Assertions.assertNotEquals")),
            Map.entry("assertTrue", List.of("org.junit.jupiter.api.Assertions.assertTrue")),
            Map.entry("assertFalse", List.of("org.junit.jupiter.api.Assertions.assertFalse")),
            Map.entry("assertNull", List.of("org.junit.jupiter.api.Assertions.assertNull")),
            Map.entry("assertNotNull", List.of("org.junit.jupiter.api.Assertions.assertNotNull")),
            Map.entry("assertSame", List.of("org.junit.jupiter.api.Assertions.assertSame")),
            Map.entry("assertNotSame", List.of("org.junit.jupiter.api.Assertions.assertNotSame")),
            Map.entry("assertThrows", List.of("org.junit.jupiter.api.Assertions.assertThrows")),
            Map.entry("assertDoesNotThrow", List.of("org.junit.jupiter.api.Assertions.assertDoesNotThrow")),
            Map.entry("assertInstanceOf", List.of("org.junit.jupiter.api.Assertions.assertInstanceOf")),
            Map.entry("assertAll", List.of("org.junit.jupiter.api.Assertions.assertAll")),
            Map.entry("assertThat", List.of("org.assertj.core.api.Assertions.assertThat")),
            Map.entry("verify", List.of("org.mockito.Mockito.verify")),
            Map.entry("when", List.of("org.mockito.Mockito.when")),
            Map.entry("mock", List.of("org.mockito.Mockito.mock")),
            Map.entry("spy", List.of("org.mockito.Mockito.spy")),
            Map.entry("times", List.of("org.mockito.Mockito.times")),
            Map.entry("never", List.of("org.mockito.Mockito.never")),
            Map.entry("doNothing", List.of("org.mockito.Mockito.doNothing")),
            Map.entry("doThrow", List.of("org.mockito.Mockito.doThrow")),
            Map.entry("doReturn", List.of("org.mockito.Mockito.doReturn")),
            Map.entry("doAnswer", List.of("org.mockito.Mockito.doAnswer")),
            Map.entry("mockStatic", List.of("org.mockito.Mockito.mockStatic")),
            Map.entry("any", List.of("org.mockito.ArgumentMatchers.any")),
            Map.entry("anyInt", List.of("org.mockito.ArgumentMatchers.anyInt")),
            Map.entry("anyLong", List.of("org.mockito.ArgumentMatchers.anyLong")),
            Map.entry("anyDouble", List.of("org.mockito.ArgumentMatchers.anyDouble")),
            Map.entry("anyBoolean", List.of("org.mockito.ArgumentMatchers.anyBoolean")),
            Map.entry("anyString", List.of("org.mockito.ArgumentMatchers.anyString")),
            Map.entry("eq", List.of("org.mockito.ArgumentMatchers.eq")),
            Map.entry("same", List.of("org.mockito.ArgumentMatchers.same")),
            Map.entry("argThat", List.of("org.mockito.ArgumentMatchers.argThat")),
            Map.entry("refEq", List.of("org.mockito.ArgumentMatchers.refEq")),
            Map.entry("startsWith", List.of("org.mockito.ArgumentMatchers.startsWith")),
            Map.entry("contains", List.of("org.mockito.ArgumentMatchers.contains")),
            Map.entry("isNull", List.of("org.mockito.ArgumentMatchers.isNull")),
            Map.entry("notNull", List.of("org.mockito.ArgumentMatchers.notNull"))
    );

    private final Path projectRoot;
    private final SourceFileEditor sourceFileEditor;
    private Map<String, List<String>> projectSymbolIndex;
    private Map<String, List<String>> classpathSymbolIndex;

    public SymbolLookupService(Path projectRoot, SourceFileEditor sourceFileEditor) {
        this.projectRoot = projectRoot;
        this.sourceFileEditor = sourceFileEditor;
    }

    public boolean symbolExistsInProjectOrClasspath(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        return !lookupSymbolCandidates(symbol).isEmpty()
                || !lookupStaticImportCandidates(symbol).isEmpty();
    }

    public boolean packageExistsInClasspath(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return false;
        }
        if (COMMON_JDK_PACKAGES.stream().anyMatch(pkg -> pkg.equals(packageName) || pkg.startsWith(packageName + "."))) {
            return true;
        }
        String normalizedPrefix = packageName.endsWith(".")
                ? packageName
                : packageName + ".";
        return buildClasspathSymbolIndex().values().stream()
                .flatMap(List::stream)
                .anyMatch(candidate -> candidate.startsWith(normalizedPrefix));
    }

    public List<String> lookupSymbolCandidates(String symbol) {
        List<String> projectMatches = lookupProjectCandidates(symbol);
        if (!projectMatches.isEmpty()) {
            return projectMatches;
        }
        return lookupClasspathCandidates(symbol);
    }

    public List<String> lookupProjectCandidates(String symbol) {
        String simpleName = toSimpleName(symbol);
        return new ArrayList<>(buildProjectSymbolIndex().getOrDefault(simpleName, List.of()));
    }

    public List<String> lookupClasspathCandidates(String symbol) {
        String simpleName = toSimpleName(symbol);
        List<String> candidates = new ArrayList<>(buildClasspathSymbolIndex().getOrDefault(simpleName, List.of()));
        for (String candidate : lookupCommonJdkCandidates(simpleName)) {
            if (!candidates.contains(candidate)) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    public List<String> lookupStandardLibraryCandidates(String symbol) {
        return new ArrayList<>(lookupCommonJdkCandidates(toSimpleName(symbol)));
    }

    public List<String> lookupStaticImportCandidates(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return List.of();
        }
        return new ArrayList<>(COMMON_STATIC_IMPORTS.getOrDefault(toSimpleName(symbol), List.of()));
    }

    public String toSimpleName(String symbol) {
        return symbol.contains(".")
                ? symbol.substring(symbol.lastIndexOf('.') + 1)
                : symbol;
    }

    private Map<String, List<String>> buildProjectSymbolIndex() {
        if (projectSymbolIndex != null) {
            return projectSymbolIndex;
        }
        Map<String, List<String>> index = new HashMap<>();
        Path mainRoot = projectRoot.resolve("src/main/java").toAbsolutePath().normalize();
        if (Files.exists(mainRoot)) {
            try (var paths = Files.walk(mainRoot)) {
                for (Path file : (Iterable<Path>) paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))::iterator) {
                    String content = sourceFileEditor.readFile(file);
                    String pkg = parsePackage(content);
                    Set<String> types = parseTopLevelTypes(content);
                    for (String type : types) {
                        String fqn = pkg.isBlank() ? type : pkg + "." + type;
                        index.computeIfAbsent(type, ignored -> new ArrayList<>()).add(fqn);
                    }
                }
            } catch (IOException ignored) {
                // best effort
            }
        }
        projectSymbolIndex = index;
        return index;
    }

    private Map<String, List<String>> buildClasspathSymbolIndex() {
        if (classpathSymbolIndex != null) {
            return classpathSymbolIndex;
        }
        Map<String, List<String>> index = new HashMap<>();
        List<Path> entries = new ArrayList<>();
        Path mainOutput = projectRoot.resolve("build/classes/java/main");
        if (Files.exists(mainOutput)) {
            entries.add(mainOutput);
        }
        String cp = System.getProperty("java.class.path", "");
        for (String part : cp.split(java.io.File.pathSeparator)) {
            if (!part.isBlank()) {
                Path path = Path.of(part);
                if (Files.exists(path)) {
                    entries.add(path.toAbsolutePath().normalize());
                }
            }
        }
        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                indexDirectory(entry, index);
            } else if (entry.toString().endsWith(".jar")) {
                indexJar(entry, index);
            }
        }
        classpathSymbolIndex = index;
        return index;
    }

    private void indexDirectory(Path dir, Map<String, List<String>> index) {
        try (var paths = Files.walk(dir)) {
            for (Path file : (Iterable<Path>) paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".class"))::iterator) {
                String rel = dir.relativize(file).toString().replace('\\', '/');
                if (rel.startsWith("META-INF") || rel.endsWith("module-info.class")) {
                    continue;
                }
                String fqn = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
                String simple = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                index.computeIfAbsent(simple, ignored -> new ArrayList<>()).add(fqn);
            }
        } catch (IOException ignored) {
            // ignore
        }
    }

    private void indexJar(Path jarPath, Map<String, List<String>> index) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            jarFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(JarEntry::getName)
                    .filter(name -> name.endsWith(".class"))
                    .filter(name -> !name.startsWith("META-INF") && !name.endsWith("module-info.class"))
                    .forEach(name -> {
                        String fqn = name.substring(0, name.length() - ".class".length()).replace('/', '.').replace('\\', '.');
                        String simple = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                        index.computeIfAbsent(simple, ignored -> new ArrayList<>()).add(fqn);
                    });
        } catch (IOException ignored) {
            // ignore
        }
    }

    private List<String> lookupCommonJdkCandidates(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        for (String pkg : COMMON_JDK_PACKAGES) {
            String candidate = pkg + "." + symbol;
            if (classExists(candidate)) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private boolean classExists(String candidate) {
        try {
            Class.forName(candidate, false, Thread.currentThread().getContextClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            try {
                Class.forName(candidate, false, ClassLoader.getSystemClassLoader());
                return true;
            } catch (ClassNotFoundException ignoredAgain) {
                return false;
            }
        } catch (LinkageError ignored) {
            return true;
        }
    }

    private String parsePackage(String content) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("package\\s+([a-zA-Z0-9_.]+)\\s*;").matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private Set<String> parseTopLevelTypes(String content) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b(class|interface|enum|record|@interface)\\s+([A-Za-z0-9_]+)\\b").matcher(content);
        Set<String> types = new java.util.HashSet<>();
        while (matcher.find()) {
            types.add(matcher.group(2));
        }
        return types;
    }
}
