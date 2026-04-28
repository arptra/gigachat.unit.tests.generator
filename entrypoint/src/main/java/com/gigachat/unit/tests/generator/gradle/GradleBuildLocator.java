package com.gigachat.unit.tests.generator.gradle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Resolves the Gradle build root used for compilation/execution commands.
 *
 * <p>The scanned project may be a nested module inside a larger multi-project build. In that
 * case the pipeline should execute Gradle from the enclosing build root rather than from the
 * nested module directory.
 */
public final class GradleBuildLocator {

    private static final List<String> BUILD_FILES = List.of("build.gradle", "build.gradle.kts");
    private static final List<String> SETTINGS_FILES = List.of("settings.gradle", "settings.gradle.kts");
    private static final List<String> WRAPPER_FILES = List.of("gradlew", "gradlew.bat");

    private GradleBuildLocator() {
    }

    public static Path findInvocationRoot(Path projectRoot) {
        Path normalizedRoot = Objects.requireNonNull(projectRoot, "projectRoot")
                .toAbsolutePath()
                .normalize();

        for (Path current = normalizedRoot; current != null; current = current.getParent()) {
            if (looksLikeBuildRoot(current)) {
                return current;
            }
        }
        return normalizedRoot;
    }

    static boolean looksLikeBuildRoot(Path directory) {
        if (directory == null) {
            return false;
        }
        return containsAny(directory, SETTINGS_FILES) || containsAny(directory, WRAPPER_FILES);
    }

    public static boolean hasGradleBuildDefinition(Path directory) {
        if (directory == null) {
            return false;
        }
        return containsAny(directory, BUILD_FILES) || containsAny(directory, SETTINGS_FILES);
    }

    public static boolean hasGradleWrapper(Path directory) {
        if (directory == null) {
            return false;
        }
        return containsAny(directory, WRAPPER_FILES);
    }

    public static String resolveGradleCommand(Path directory) {
        if (directory == null) {
            return null;
        }
        Path gradlew = directory.resolve("gradlew");
        if (Files.exists(gradlew)) {
            return "./gradlew";
        }
        Path gradlewBat = directory.resolve("gradlew.bat");
        if (Files.exists(gradlewBat)) {
            return ".\\gradlew.bat";
        }
        if (hasGradleBuildDefinition(directory)) {
            return "gradle";
        }
        return null;
    }

    private static boolean containsAny(Path directory, List<String> fileNames) {
        for (String fileName : fileNames) {
            if (Files.exists(directory.resolve(fileName))) {
                return true;
            }
        }
        return false;
    }
}
