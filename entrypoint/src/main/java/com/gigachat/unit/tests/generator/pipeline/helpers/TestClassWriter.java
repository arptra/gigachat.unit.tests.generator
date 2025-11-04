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
