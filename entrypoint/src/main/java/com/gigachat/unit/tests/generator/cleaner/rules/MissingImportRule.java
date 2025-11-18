package com.gigachat.unit.tests.generator.cleaner.rules;

import com.gigachat.unit.tests.generator.cleaner.CleanerRule;
import com.gigachat.unit.tests.generator.cleaner.ProjectClassIndex;
import com.gigachat.unit.tests.generator.cleaner.TestFileContext;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Removes imports that reference classes that no longer exist inside the project sources.
 */
public final class MissingImportRule implements CleanerRule {
    private final ProjectClassIndex classIndex;
    private final ClassLoader projectClassLoader;

    public MissingImportRule(ProjectClassIndex classIndex) {
        this(classIndex, buildProjectClassLoader(classIndex));
    }

    MissingImportRule(ProjectClassIndex classIndex, ClassLoader projectClassLoader) {
        this.classIndex = classIndex;
        this.projectClassLoader = projectClassLoader;
    }

    @Override
    public boolean apply(TestFileContext context) throws IOException {
        CompilationUnit unit = context.getCompilationUnit().orElse(null);
        if (unit == null) {
            return false;
        }
        List<ImportDeclaration> imports = unit.getImports();
        if (imports == null || imports.isEmpty()) {
            return false;
        }
        Set<ImportDeclaration> toRemove = new HashSet<>();
        for (ImportDeclaration declaration : imports) {
            if (declaration.isAsterisk()) {
                continue;
            }
            String candidate = declaration.getName().asString();
            if (declaration.isStatic()) {
                int lastDot = candidate.lastIndexOf('.');
                if (lastDot > 0) {
                    candidate = candidate.substring(0, lastDot);
                }
            }
            if (exists(candidate)) {
                continue;
            }
            toRemove.add(declaration);
        }
        if (toRemove.isEmpty()) {
            return false;
        }
        toRemove.forEach(ImportDeclaration::remove);
        context.markAstDirty();
        return true;
    }

    private boolean exists(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (classIndex.contains(candidate)) {
            return true;
        }
        ClassLoader loader = projectClassLoader;
        try {
            Class.forName(candidate, false, loader);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (LinkageError ignored) {
            return true;
        }
    }

    private static ClassLoader buildProjectClassLoader(ProjectClassIndex index) {
        Path root = index.getProjectRoot();
        if (root == null) {
            return new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader());
        }

        List<Path> jarDirs = List.of(
                root.resolve("libs"),
                root.resolve("lib"),
                root.resolve("build/libs")
        );
        List<Path> classDirs = List.of(
                root.resolve("build/classes/java/main"),
                root.resolve("build/classes/java/test"),
                root.resolve("build/resources/main"),
                root.resolve("build/resources/test")
        );

        List<URL> urls = new ArrayList<>();
        addGradleCacheJars(root, urls);
        for (Path dir : jarDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var stream = Files.list(dir)) {
                stream.filter(path -> path.toString().endsWith(".jar"))
                        .forEach(path -> addUrl(urls, path));
            } catch (IOException ignored) {
                // ignore and continue with other directories
            }
        }

        for (Path dir : classDirs) {
            if (Files.isDirectory(dir)) {
                addUrl(urls, dir);
            }
        }

        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private static void addGradleCacheJars(Path projectRoot, List<URL> urls) {
        Path userHome = Path.of(System.getProperty("user.home"));
        String gradleHomeProperty = System.getProperty("gradle.user.home");
        Path gradleUserHome = (gradleHomeProperty == null || gradleHomeProperty.isBlank())
                ? userHome.resolve(".gradle")
                : Path.of(gradleHomeProperty);

        List<Path> candidates = List.of(
                projectRoot.resolve(".gradle"),
                gradleUserHome.resolve("caches")
        );

        for (Path candidate : candidates) {
            if (!Files.isDirectory(candidate)) {
                continue;
            }
            collectJarUrls(candidate, urls, path -> path.toString().endsWith(".jar"));
        }
    }

    private static void collectJarUrls(Path root, List<URL> urls, Predicate<Path> matcher) {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(matcher)
                    .forEach(path -> addUrl(urls, path));
        } catch (IOException ignored) {
            // If we cannot walk the cache directory we simply skip it.
        }
    }

    private static void addUrl(List<URL> urls, Path jar) {
        try {
            urls.add(jar.toUri().toURL());
        } catch (MalformedURLException ignored) {
            // ignore invalid jar URLs
        }
    }
}
