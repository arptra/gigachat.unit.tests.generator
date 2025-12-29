package com.gigachat.unit.tests.generator.util;

import com.gigachat.unit.tests.generator.dto.TestClassInfo;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Utility that normalises target class patterns and checks whether a discovered class
 * matches them. Supports exact class names as well as module/package wildcards such as
 * {@code module.*}.
 */
public final class TargetClassMatcher {

    private TargetClassMatcher() {
    }

    public static boolean matches(String rawTarget,
                                  String packageName,
                                  String className,
                                  String testClassName,
                                  Path moduleRoot,
                                  Path testFilePath) {
        if (rawTarget == null || rawTarget.isBlank()) {
            return false;
        }
        String target = normalize(rawTarget);
        if (target.isEmpty()) {
            return false;
        }

        String fullName = packageName == null || packageName.isBlank()
                ? className
                : packageName + '.' + className;

        if (isModuleWildcard(target)) {
            String prefix = stripWildcard(target);
            if (matchesModule(prefix, moduleRoot, testFilePath)) {
                return true;
            }
            if (fullName != null && fullName.startsWith(prefix)) {
                return true;
            }
            return fullName != null && fullName.startsWith(prefix + '.');
        }

        if (fullName != null && (fullName.equals(target) || fullName.equalsIgnoreCase(target))) {
            return true;
        }
        if (className != null && (className.equals(target) || className.equalsIgnoreCase(target))) {
            return true;
        }
        if (testClassName != null && (testClassName.equals(target) || testClassName.equalsIgnoreCase(target))) {
            return true;
        }
        return false;
    }

    public static boolean matches(TestClassInfo info, String target) {
        Objects.requireNonNull(info, "info");
        return matches(target,
                "",
                info.getClassName(),
                info.getTestClassName(),
                deriveModuleRoot(info.resolveTestFile()),
                info.resolveTestFile());
    }

    private static boolean isModuleWildcard(String target) {
        return target.endsWith(".*");
    }

    private static String stripWildcard(String target) {
        return target.substring(0, target.length() - 2);
    }

    private static boolean matchesModule(String prefix, Path moduleRoot, Path testFilePath) {
        if (moduleRoot != null && moduleRoot.getFileName() != null
                && moduleRoot.getFileName().toString().equalsIgnoreCase(prefix)) {
            return true;
        }
        Path derived = deriveModuleRoot(testFilePath);
        if (derived != null && derived.getFileName() != null
                && derived.getFileName().toString().equalsIgnoreCase(prefix)) {
            return true;
        }
        if (testFilePath != null) {
            String prefixPath = prefix.replace('.', '/');
            Path normalised = Paths.get(prefixPath);
            return containsSegment(testFilePath, normalised);
        }
        return false;
    }

    private static boolean containsSegment(Path path, Path segment) {
        if (path == null || segment == null) {
            return false;
        }
        Path normalisedPath = path.toAbsolutePath().normalize();
        Path normalisedSegment = segment.normalize();
        int nameCount = normalisedSegment.getNameCount();
        if (nameCount == 0) {
            return false;
        }
        for (int i = 0; i <= normalisedPath.getNameCount() - nameCount; i++) {
            boolean match = true;
            for (int j = 0; j < nameCount; j++) {
                if (!normalisedPath.getName(i + j).toString()
                        .equalsIgnoreCase(normalisedSegment.getName(j).toString())) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static Path deriveModuleRoot(Path testFilePath) {
        if (testFilePath == null) {
            return null;
        }
        Path current = testFilePath.toAbsolutePath().normalize();
        for (int i = 0; i < current.getNameCount(); i++) {
            if (current.getName(i).toString().equals("src")) {
                if (i == 0) {
                    return current.getRoot();
                }
                Path prefix = current.subpath(0, i);
                return current.getRoot() == null
                        ? prefix
                        : current.getRoot().resolve(prefix);
            }
        }
        return current.getParent();
    }

    private static String normalize(String target) {
        String candidate = target.trim()
                .replace('\\', '.')
                .replace('/', '.');
        if (candidate.endsWith(".java")) {
            candidate = candidate.substring(0, candidate.length() - 5);
        }
        return candidate;
    }
}
