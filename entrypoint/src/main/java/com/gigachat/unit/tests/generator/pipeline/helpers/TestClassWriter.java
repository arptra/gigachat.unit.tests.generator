package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles creation and modification of generated test classes.
 */
public class TestClassWriter {
    private static final String TEST_ANNOTATION_IMPORT = "import org.junit.jupiter.api.Test;";

    private final PipelineLogger logger;
    private final Map<Path, Object> locks = new ConcurrentHashMap<>();

    private static final List<String> REQUIRED_TEST_DEPENDENCIES = List.of(
            "implementation 'org.mockito:mockito-core:5.12.0'",
            "testImplementation 'org.junit.jupiter:junit-jupiter-api:5.10.2'",
            "testImplementation 'org.junit.jupiter:junit-jupiter-params:5.10.2'",
            "testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.10.2'",
            "testImplementation 'org.mockito:mockito-core:5.12.0'",
            "testImplementation 'org.mockito:mockito-junit-jupiter:5.12.0'",
            "testImplementation platform('org.junit:junit-bom:5.10.2')",
            "testImplementation 'org.junit.jupiter:junit-jupiter'",
            "testRuntimeOnly 'org.junit.platform:junit-platform-launcher'",
            "testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'");

    public TestClassWriter(PipelineLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void ensureTestClassExists(TestClassInfo info) {
        Path targetFile = info.getTargetPath();
        synchronized (lockFor(targetFile)) {
            if (Files.exists(targetFile)) {
                return;
            }
            try {
                Files.createDirectories(targetFile.getParent());
                ensureTestDependencies(targetFile);
                List<String> lines = new ArrayList<>();
                String packageName = determinePackage(targetFile);
                if (!packageName.isBlank()) {
                    lines.add("package " + packageName + ';');
                    lines.add("");
                }
                lines.add(TEST_ANNOTATION_IMPORT);
                lines.add("");
                lines.add("public class " + info.getTestClassName() + " {");
                lines.add("");
                lines.add("}");
                Files.write(targetFile,
                        String.join(System.lineSeparator(), lines).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                logger.info("Created test class skeleton at " + targetFile);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to create test class at " + targetFile, exception);
            }
        }
    }

    private void ensureTestDependencies(Path targetFile) {
        Path buildFile = locateBuildScript(targetFile);
        if (buildFile == null) {
            logger.warn("Unable to locate build.gradle for " + targetFile + "; skipping dependency verification.");
            return;
        }
        String content;
        try {
            content = Files.readString(buildFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read build script " + buildFile, exception);
        }
        List<String> missing = determineMissingDependencies(content);
        if (missing.isEmpty()) {
            logger.info("Required test dependencies already present in " + buildFile);
            return;
        }
        try {
            List<String> lines = Files.readAllLines(buildFile);
            int dependenciesStart = findDependenciesBlockStart(lines);
            if (dependenciesStart < 0) {
                logger.warn("No dependencies block found in " + buildFile + "; unable to append required test dependencies.");
                return;
            }
            int insertIndex = findDependenciesBlockEnd(lines, dependenciesStart);
            if (insertIndex < 0) {
                logger.warn("Failed to determine end of dependencies block in " + buildFile + "; skipping dependency insertion.");
                return;
            }
            String indent = determineIndentation(lines, dependenciesStart, insertIndex);
            List<String> additions = new ArrayList<>();
            for (String dependency : missing) {
                additions.add(indent + dependency);
            }
            if (insertIndex > dependenciesStart + 1 && !lines.get(insertIndex - 1).isBlank()) {
                additions.add(0, "");
            }
            lines.addAll(insertIndex, additions);
            String updated = String.join(System.lineSeparator(), lines);
            Files.writeString(buildFile, updated, StandardCharsets.UTF_8);
            logger.info("Added missing test dependencies to " + buildFile + ": " + missing);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to update build script " + buildFile, exception);
        }
    }

    private Path locateBuildScript(Path targetFile) {
        Path current = targetFile.toAbsolutePath().getParent();
        while (current != null) {
            Path buildGradle = current.resolve("build.gradle");
            if (Files.exists(buildGradle)) {
                return buildGradle;
            }
            Path buildGradleKts = current.resolve("build.gradle.kts");
            if (Files.exists(buildGradleKts)) {
                logger.warn("Detected build.gradle.kts near " + targetFile + "; automatic dependency injection currently supports Groovy DSL only.");
                return null;
            }
            current = current.getParent();
        }
        return null;
    }

    private List<String> determineMissingDependencies(String content) {
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        for (String dependency : REQUIRED_TEST_DEPENDENCIES) {
            if (!containsDependency(content, dependency)) {
                missing.add(dependency);
            }
        }
        return new ArrayList<>(missing);
    }

    private boolean containsDependency(String content, String dependency) {
        String trimmed = dependency.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        if (content.contains(trimmed)) {
            return true;
        }
        String alternative = swapQuotes(trimmed);
        return !alternative.equals(trimmed) && content.contains(alternative);
    }

    private String swapQuotes(String value) {
        if (value.indexOf('\'') >= 0) {
            return value.replace('\'', '"');
        }
        if (value.indexOf('"') >= 0) {
            return value.replace('"', '\'');
        }
        return value;
    }

    private int findDependenciesBlockStart(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.startsWith("dependencies")) {
                return index;
            }
        }
        return -1;
    }

    private int findDependenciesBlockEnd(List<String> lines, int startIndex) {
        int balance = 0;
        boolean started = false;
        for (int index = startIndex; index < lines.size(); index++) {
            String line = lines.get(index);
            for (char character : line.toCharArray()) {
                if (character == '{') {
                    balance++;
                    started = true;
                } else if (character == '}') {
                    balance--;
                    if (started && balance == 0) {
                        return index;
                    }
                }
            }
        }
        return -1;
    }

    private String determineIndentation(List<String> lines, int startIndex, int endIndex) {
        for (int index = startIndex + 1; index < endIndex; index++) {
            String line = lines.get(index);
            if (!line.isBlank()) {
                int nonWhitespace = 0;
                while (nonWhitespace < line.length() && Character.isWhitespace(line.charAt(nonWhitespace))) {
                    nonWhitespace++;
                }
                return line.substring(0, nonWhitespace);
            }
        }
        return "    ";
    }

    public String readSource(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read file " + file, exception);
        }
    }

    public void writeSource(Path file, String source) {
        synchronized (lockFor(file)) {
            try {
                Files.writeString(file,
                        source,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to write file " + file, exception);
            }
        }
    }

    public boolean isEffectivelyEmptyTestClass(String source) {
        if (source == null || source.isBlank()) {
            return true;
        }
        try {
            CompilationUnit unit = StaticJavaParser.parse(source);
            ClassOrInterfaceDeclaration declaration = unit.getPrimaryType()
                    .flatMap(type -> type.toClassOrInterfaceDeclaration())
                    .orElse(null);
            if (declaration == null) {
                return false;
            }
            return declaration.getMembers().stream().noneMatch(member ->
                    member.isFieldDeclaration()
                            || member.isConstructorDeclaration()
                            || member.isMethodDeclaration()
                            || member.isClassOrInterfaceDeclaration()
                            || member.isEnumDeclaration()
                            || member.isInitializerDeclaration());
        } catch (ParseProblemException exception) {
            logger.warn("Unable to parse test class to determine if it is empty: " + exception.getMessage());
            return false;
        }
    }

    public String applyClassStructure(String source, GeneratedTestSnippet snippet) {
        boolean hasAnnotations = snippet.classAnnotations() != null && !snippet.classAnnotations().isEmpty();
        boolean hasFields = snippet.fieldDeclarations() != null && !snippet.fieldDeclarations().isEmpty();
        boolean hasHelpers = snippet.helperMethods() != null && !snippet.helperMethods().isEmpty();
        if (!(hasAnnotations || hasFields || hasHelpers)) {
            return source;
        }
        try {
            CompilationUnit unit = StaticJavaParser.parse(source);
            ClassOrInterfaceDeclaration declaration = locateClass(unit, snippet.className());
            if (declaration == null) {
                return source;
            }
            boolean changed = false;
            if (hasAnnotations) {
                changed |= ensureClassAnnotations(declaration, snippet.classAnnotations());
            }
            if (hasFields) {
                changed |= ensureFields(declaration, snippet.fieldDeclarations());
            }
            if (hasHelpers) {
                changed |= ensureHelperMethods(declaration, snippet.helperMethods());
            }
            return changed ? unit.toString() : source;
        } catch (ParseProblemException exception) {
            logger.warn("Unable to parse existing test class for structure merge: " + exception.getMessage());
            return source;
        }
    }

    public String ensureImports(String source, List<String> newImports) {
        if (newImports == null || newImports.isEmpty()) {
            return source;
        }
        LinkedHashSet<String> imports = new LinkedHashSet<>();
        source.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("import ") && line.endsWith(";"))
                .forEach(imports::add);
        boolean changed = false;
        for (String rawImport : newImports) {
            if (rawImport == null) {
                continue;
            }
            String normalised = normaliseImport(rawImport);
            if (normalised.isEmpty()) {
                continue;
            }
            if (imports.add(normalised)) {
                changed = true;
            }
        }
        if (!imports.contains(TEST_ANNOTATION_IMPORT)) {
            imports.add(TEST_ANNOTATION_IMPORT);
            changed = true;
        }
        if (!changed) {
            return source;
        }
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\\R", -1)));
        int packageIndex = findPackageIndex(lines);
        int lastImportIndex = findLastImportIndex(lines);
        int insertIndex;
        if (lastImportIndex >= 0) {
            insertIndex = lastImportIndex + 1;
        } else if (packageIndex >= 0) {
            insertIndex = packageIndex + 1;
            if (insertIndex < lines.size() && !lines.get(insertIndex).isBlank()) {
                lines.add(insertIndex, "");
            }
        } else {
            insertIndex = 0;
        }
        if (lastImportIndex < 0) {
            // ensure a blank line between imports and class body
            if (insertIndex < lines.size() && !lines.get(insertIndex).isBlank()) {
                lines.add(insertIndex, "");
            }
        }
        for (String importLine : imports) {
            if (lastImportIndex >= 0) {
                if (!containsImport(lines, importLine)) {
                    lines.add(insertIndex, importLine);
                    insertIndex++;
                }
            } else {
                lines.add(insertIndex, importLine);
                insertIndex++;
            }
        }
        if (insertIndex < lines.size() && !lines.get(insertIndex).isBlank()) {
            lines.add(insertIndex, "");
        }
        return joinLines(lines);
    }

    private ClassOrInterfaceDeclaration locateClass(CompilationUnit unit, String className) {
        return unit.getClassByName(className)
                .orElseGet(() -> unit.getPrimaryType()
                        .flatMap(type -> type.toClassOrInterfaceDeclaration())
                        .orElse(null));
    }

    private boolean ensureClassAnnotations(ClassOrInterfaceDeclaration declaration, List<String> annotations) {
        boolean changed = false;
        for (String raw : annotations) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                AnnotationExpr candidate = StaticJavaParser.parseAnnotation(raw.trim());
                boolean exists = declaration.getAnnotations().stream()
                        .anyMatch(existing -> existing.equals(candidate) || existing.toString().equals(candidate.toString()));
                if (!exists) {
                    declaration.addAnnotation(candidate);
                    changed = true;
                }
            } catch (ParseProblemException exception) {
                logger.warn("Failed to parse class annotation from snippet: " + raw + " -> " + exception.getMessage());
            }
        }
        return changed;
    }

    private boolean ensureFields(ClassOrInterfaceDeclaration declaration, List<String> fields) {
        boolean changed = false;
        for (String raw : fields) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                BodyDeclaration<?> body = StaticJavaParser.parseBodyDeclaration(raw.trim());
                if (!body.isFieldDeclaration()) {
                    continue;
                }
                FieldDeclaration field = body.asFieldDeclaration();
                if (containsField(declaration, field)) {
                    continue;
                }
                declaration.addMember(field);
                changed = true;
            } catch (ParseProblemException exception) {
                logger.warn("Failed to parse field declaration from snippet: " + raw + " -> " + exception.getMessage());
            }
        }
        return changed;
    }

    private boolean ensureHelperMethods(ClassOrInterfaceDeclaration declaration, List<String> methods) {
        boolean changed = false;
        for (String raw : methods) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                BodyDeclaration<?> body = StaticJavaParser.parseBodyDeclaration(raw.trim());
                if (!body.isMethodDeclaration()) {
                    continue;
                }
                MethodDeclaration method = body.asMethodDeclaration();
                boolean exists = declaration.getMethods().stream()
                        .anyMatch(existing -> existing.getSignature().equals(method.getSignature()));
                if (exists) {
                    continue;
                }
                declaration.addMember(method);
                changed = true;
            } catch (ParseProblemException exception) {
                logger.warn("Failed to parse helper method from snippet: " + raw + " -> " + exception.getMessage());
            }
        }
        return changed;
    }

    private boolean containsField(ClassOrInterfaceDeclaration declaration, FieldDeclaration candidate) {
        for (FieldDeclaration existing : declaration.getFields()) {
            if (!existing.getElementType().toString().equals(candidate.getElementType().toString())) {
                continue;
            }
            boolean allVariablesPresent = candidate.getVariables().stream()
                    .allMatch(variable -> existing.getVariables().stream()
                            .anyMatch(existingVar -> existingVar.getNameAsString().equals(variable.getNameAsString())));
            if (allVariablesPresent) {
                return true;
            }
        }
        return false;
    }

    private boolean containsImport(List<String> lines, String importLine) {
        return lines.stream().anyMatch(line -> line.trim().equals(importLine));
    }

    public String appendMethod(String source, GeneratedTestSnippet snippet) {
        Objects.requireNonNull(snippet, "snippet");
        String methodBody = snippet.methodBody() == null ? "" : snippet.methodBody().trim();
        if (methodBody.isEmpty()) {
            throw new IllegalArgumentException("Snippet method body is empty for " + snippet.methodName());
        }
        if (source.contains(snippet.methodName() + "(")) {
            logger.warn("Test method " + snippet.methodName() + " already exists. Skipping append.");
            return source;
        }
        int insertionPoint = source.lastIndexOf('}');
        if (insertionPoint < 0) {
            throw new IllegalStateException("Unable to find class closing brace when appending method");
        }
        String normalisedMethod = ensureMethodFormatting(methodBody);
        StringBuilder builder = new StringBuilder(source);
        builder.insert(insertionPoint, System.lineSeparator() + normalisedMethod + System.lineSeparator());
        return builder.toString();
    }

    public String removeMethod(String source, String methodBody) {
        if (methodBody == null || methodBody.isBlank()) {
            return source;
        }
        String normalisedMethod = ensureMethodFormatting(methodBody.trim());
        String updated = source.replace(System.lineSeparator() + normalisedMethod + System.lineSeparator(), System.lineSeparator());
        if (updated.equals(source)) {
            updated = source.replace(normalisedMethod + System.lineSeparator(), "");
        }
        return updated;
    }

    private String ensureMethodFormatting(String methodBody) {
        String trimmed = methodBody.trim();
        if (!trimmed.startsWith("@")) {
            trimmed = "@Test" + System.lineSeparator() + trimmed;
        }
        String indent = "    ";
        String[] lines = trimmed.split("\\R");
        StringBuilder builder = new StringBuilder();
        boolean firstLine = true;
        for (String line : lines) {
            String candidate = line;
            if (!candidate.startsWith(indent)) {
                candidate = indent + candidate.stripLeading();
            }
            if (firstLine) {
                builder.append(candidate);
                firstLine = false;
            } else {
                builder.append(System.lineSeparator()).append(candidate);
            }
        }
        if (!trimmed.endsWith("}")) {
            builder.append(System.lineSeparator()).append(indent).append("}");
        }
        return builder.toString().stripTrailing();
    }

    private Object lockFor(Path path) {
        return locks.computeIfAbsent(path.toAbsolutePath().normalize(), key -> new Object());
    }

    private int findPackageIndex(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("package ")) {
                return i;
            }
        }
        return -1;
    }

    private int findLastImportIndex(List<String> lines) {
        int index = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith("import ")) {
                index = i;
            }
        }
        return index;
    }

    private String joinLines(List<String> lines) {
        return String.join(System.lineSeparator(), lines);
    }

    private String determinePackage(Path targetFile) {
        String normalised = targetFile.toAbsolutePath().normalize().toString().replace('\\', '/');
        String marker = "/src/test/java/";
        int index = normalised.indexOf(marker);
        if (index < 0) {
            return "";
        }
        String remainder = normalised.substring(index + marker.length());
        int lastSlash = remainder.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "";
        }
        return remainder.substring(0, lastSlash).replace('/', '.');
    }

    private String normaliseImport(String rawImport) {
        String trimmed = rawImport.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (!trimmed.endsWith(";")) {
            trimmed = trimmed + ';';
        }
        if (!trimmed.startsWith("import ")) {
            trimmed = "import " + trimmed;
        }
        return trimmed;
    }
}
