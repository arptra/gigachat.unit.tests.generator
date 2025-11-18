package com.gigachat.unit.tests.generator.cleaner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Indexes Java classes declared inside the current project. The cleaner relies on the
 * index to verify that imports reference classes that still exist in the repository.
 */
public final class ProjectClassIndex {
    private final Set<String> classNames = new LinkedHashSet<>();
    private final Path projectRoot;

    public ProjectClassIndex(Path projectRoot) {
        this.projectRoot = projectRoot;
        try {
            index(projectRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to index project classes under " + projectRoot, exception);
        }
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    public boolean contains(String fullyQualifiedClass) {
        if (fullyQualifiedClass == null || fullyQualifiedClass.isBlank()) {
            return false;
        }
        return classNames.contains(fullyQualifiedClass);
    }

    private void index(Path projectRoot) throws IOException {
        if (projectRoot == null || !Files.exists(projectRoot)) {
            return;
        }
        try (Stream<Path> files = Files.walk(projectRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(path -> toClassName(projectRoot, path))
                    .filter(name -> !name.isBlank())
                    .forEach(classNames::add);
        }
    }

    private String toClassName(Path projectRoot, Path javaFile) {
        String normalised = projectRoot.relativize(javaFile).toString().replace('\\', '/');
        String marker = "src/main/java/";
        int markerIndex = normalised.indexOf(marker);
        if (markerIndex < 0) {
            marker = "src/test/java/";
            markerIndex = normalised.indexOf(marker);
        }
        if (markerIndex < 0) {
            return "";
        }
        String relative = normalised.substring(markerIndex + marker.length());
        if (!relative.endsWith(".java")) {
            return "";
        }
        String withoutExtension = relative.substring(0, relative.length() - 5);
        return withoutExtension.replace('/', '.');
    }
}
