package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.resources.MergePolicy;
import com.gigachat.unit.tests.generator.resources.MergePolicyCatalog;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

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
    private final MergePolicy mergePolicy;

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
        this.mergePolicy = new MergePolicyCatalog().policy();
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
        if (!(hasAnnotations || hasFields || hasHelpers || mergePolicy.dedupeDuplicateAnnotations())) {
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
            if (mergePolicy.dedupeDuplicateAnnotations()) {
                changed |= dedupeAnnotations(declaration);
                for (MethodDeclaration method : declaration.getMethods()) {
                    changed |= dedupeAnnotations(method);
                }
            }
            return changed ? unit.toString() : source;
        } catch (ParseProblemException exception) {
            logger.warn("Unable to parse existing test class for structure merge: " + exception.getMessage());
            return source;
        }
    }

    public String ensureImports(String source, List<String> newImports) {
        List<String> requestedImports = newImports == null ? List.of() : newImports;
        if (requestedImports.isEmpty() && !mergePolicy.normalizeImports()) {
            return source;
        }
        LinkedHashSet<String> imports = new LinkedHashSet<>();
        List<String> existingImportLines = source.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("import ") && line.endsWith(";"))
                .toList();
        existingImportLines.forEach(imports::add);
        boolean changed = false;
        boolean hadDuplicateImports = existingImportLines.size() != imports.size();
        for (String rawImport : requestedImports) {
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
        if (!changed && !(mergePolicy.normalizeImports() && hadDuplicateImports)) {
            return source;
        }
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\\R", -1)));
        lines.removeIf(line -> line.trim().startsWith("import ") && line.trim().endsWith(";"));
        int packageIndex = findPackageIndex(lines);
        int insertIndex = packageIndex >= 0 ? packageIndex + 1 : 0;
        while (insertIndex < lines.size() && lines.get(insertIndex).isBlank()) {
            lines.remove(insertIndex);
        }
        if (!imports.isEmpty()) {
            if (insertIndex > 0 && !lines.get(insertIndex - 1).isBlank()) {
                lines.add(insertIndex++, "");
            }
            for (String importLine : imports) {
                lines.add(insertIndex++, importLine);
            }
            if (insertIndex < lines.size() && !lines.get(insertIndex).isBlank()) {
                lines.add(insertIndex, "");
            } else if (insertIndex == lines.size()) {
                lines.add("");
            }
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
                if (mergePolicy.dedupeDuplicateAnnotations()) {
                    dedupeAnnotations(method);
                }
                MethodDeclaration existing = declaration.getMethods().stream()
                        .filter(candidate -> candidate.getSignature().equals(method.getSignature()))
                        .findFirst()
                        .orElse(null);
                if (existing != null) {
                    if (shouldReplaceHelperMethod(existing, method)) {
                        existing.replace(method);
                        changed = true;
                    }
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

    private boolean shouldReplaceHelperMethod(MethodDeclaration existing, MethodDeclaration incoming) {
        if (existing == null || incoming == null) {
            return false;
        }
        if (existing.toString().equals(incoming.toString())) {
            return false;
        }
        if (!isLifecycleHelper(existing) || !isLifecycleHelper(incoming)) {
            return false;
        }
        return helperStrengthScore(incoming) > helperStrengthScore(existing);
    }

    private boolean isLifecycleHelper(MethodDeclaration method) {
        if (method == null) {
            return false;
        }
        return method.getAnnotationByName("BeforeEach").isPresent()
                || method.getAnnotationByName("BeforeAll").isPresent()
                || method.getAnnotationByName("AfterEach").isPresent()
                || method.getAnnotationByName("AfterAll").isPresent();
    }

    private int helperStrengthScore(MethodDeclaration method) {
        if (method == null) {
            return 0;
        }
        String source = method.toString();
        int score = countOccurrences(source, "mock(") * 10;
        score += countOccurrences(source, "Mockito.mock(") * 10;
        if (source.contains("new Application(")) {
            score += 3;
        }
        if (source.contains("new ")) {
            score += 1;
        }
        score += method.getBody().map(body -> body.getStatements().size()).orElse(0);
        return score;
    }

    private int countOccurrences(String source, String token) {
        if (source == null || source.isEmpty() || token == null || token.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
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

    public AppendResult appendMethod(String source, GeneratedTestSnippet snippet) {
        Objects.requireNonNull(snippet, "snippet");
        PreparedSnippet prepared = prepareSnippetForAppend(source, snippet);
        if (!prepared.shouldAppend()) {
            logger.warn("Test method " + prepared.snippet().methodName() + " already exists with the same body. Skipping append.");
            return new AppendResult(source, prepared.snippet(), false, prepared.diagnostic());
        }
        int insertionPoint = source.lastIndexOf('}');
        if (insertionPoint < 0) {
            throw new IllegalStateException("Unable to find class closing brace when appending method");
        }
        StringBuilder builder = new StringBuilder(source);
        builder.insert(insertionPoint, System.lineSeparator() + prepared.snippet().methodBody() + System.lineSeparator());
        return new AppendResult(builder.toString(), prepared.snippet(), true, prepared.diagnostic());
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
        MethodDeclaration declaration = parseMethodDeclaration(methodBody);
        if (declaration != null) {
            normaliseMethodDeclaration(declaration);
            return renderMethodDeclaration(declaration);
        }
        String trimmed = methodBody.trim();
        if (mergePolicy.autoAddTestAnnotationWhenMissing() && !trimmed.startsWith("@")) {
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

    private PreparedSnippet prepareSnippetForAppend(String source, GeneratedTestSnippet snippet) {
        MethodDeclaration incomingMethod = parseMethodDeclaration(snippet.methodBody());
        if (incomingMethod == null) {
            String formatted = ensureMethodFormatting(snippet.methodBody());
            GeneratedTestSnippet rewritten = rewriteSnippet(snippet, snippet.methodName(), formatted);
            if (source.contains(rewritten.methodName() + "(")) {
                return new PreparedSnippet(rewritten, false, "METHOD_ALREADY_PRESENT_UNPARSED");
            }
            return new PreparedSnippet(rewritten, true, "APPEND_UNPARSED_METHOD");
        }

        normaliseMethodDeclaration(incomingMethod);
        String originalName = incomingMethod.getNameAsString();
        List<MethodDeclaration> existingMethods = existingMethods(source);
        for (MethodDeclaration existingMethod : existingMethods) {
            if (methodsEquivalent(existingMethod, incomingMethod)) {
                String rendered = renderMethodDeclaration(incomingMethod);
                GeneratedTestSnippet rewritten = rewriteSnippet(snippet, originalName, rendered);
                return new PreparedSnippet(rewritten, false, "IDENTICAL_METHOD_ALREADY_PRESENT");
            }
        }
        boolean collision = existingMethods.stream()
                .anyMatch(existingMethod -> existingMethod.getNameAsString().equals(originalName))
                || source.contains(" " + originalName + "(");
        String finalName = originalName;
        String diagnostic = "APPEND_METHOD";
        if (collision && mergePolicy.renameMethodOnCollision()) {
            finalName = resolveUniqueMethodName(existingMethods, originalName);
            incomingMethod.setName(finalName);
            diagnostic = "RENAMED_METHOD_COLLISION";
        }
        String rendered = renderMethodDeclaration(incomingMethod);
        GeneratedTestSnippet rewritten = rewriteSnippet(snippet, finalName, rendered);
        return new PreparedSnippet(rewritten, true, diagnostic);
    }

    private GeneratedTestSnippet rewriteSnippet(GeneratedTestSnippet snippet, String methodName, String methodBody) {
        return new GeneratedTestSnippet(
                snippet.className(),
                methodName,
                methodBody,
                snippet.imports(),
                snippet.classAnnotations(),
                snippet.fieldDeclarations(),
                snippet.helperMethods(),
                snippet.fullClassSource()
        );
    }

    private List<MethodDeclaration> existingMethods(String source) {
        try {
            CompilationUnit unit = StaticJavaParser.parse(source);
            ClassOrInterfaceDeclaration declaration = unit.getPrimaryType()
                    .flatMap(type -> type.toClassOrInterfaceDeclaration())
                    .orElse(null);
            if (declaration == null) {
                return List.of();
            }
            return declaration.getMethods();
        } catch (ParseProblemException exception) {
            logger.warn("Unable to parse existing class while preparing append: " + exception.getMessage());
            return List.of();
        }
    }

    private String resolveUniqueMethodName(List<MethodDeclaration> existingMethods, String originalName) {
        LinkedHashSet<String> usedNames = new LinkedHashSet<>();
        for (MethodDeclaration method : existingMethods) {
            usedNames.add(method.getNameAsString());
        }
        usedNames.add(originalName);
        for (int index = 2; index <= mergePolicy.maxCollisionAttempts() + 1; index++) {
            String candidate = originalName + mergePolicy.collisionSuffixStem() + index;
            if (!usedNames.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to derive unique generated test method name for collision: " + originalName);
    }

    private MethodDeclaration parseMethodDeclaration(String methodBody) {
        String trimmed = methodBody == null ? "" : methodBody.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("Snippet method body is empty");
        }
        try {
            BodyDeclaration<?> body = StaticJavaParser.parseBodyDeclaration(trimmed);
            return body.isMethodDeclaration() ? body.asMethodDeclaration() : null;
        } catch (ParseProblemException exception) {
            logger.warn("Unable to parse generated test method for structural merge: " + exception.getMessage());
            return null;
        }
    }

    private void normaliseMethodDeclaration(MethodDeclaration declaration) {
        if (mergePolicy.autoAddTestAnnotationWhenMissing() && declaration.getAnnotationByName("Test").isEmpty()) {
            declaration.addAnnotation("Test");
        }
        if (mergePolicy.dedupeDuplicateAnnotations()) {
            dedupeAnnotations(declaration);
        }
    }

    private String renderMethodDeclaration(MethodDeclaration declaration) {
        String indent = "    ";
        String[] lines = declaration.toString().stripTrailing().split("\\R");
        StringBuilder builder = new StringBuilder();
        boolean firstLine = true;
        for (String line : lines) {
            String candidate = line.startsWith(indent) ? line : indent + line.stripLeading();
            if (firstLine) {
                builder.append(candidate);
                firstLine = false;
            } else {
                builder.append(System.lineSeparator()).append(candidate);
            }
        }
        return builder.toString().stripTrailing();
    }

    private boolean methodsEquivalent(MethodDeclaration existing, MethodDeclaration incoming) {
        if (existing == null || incoming == null) {
            return false;
        }
        MethodDeclaration existingCopy = existing.clone();
        MethodDeclaration incomingCopy = incoming.clone();
        normaliseMethodDeclaration(existingCopy);
        normaliseMethodDeclaration(incomingCopy);
        return existingCopy.toString().equals(incomingCopy.toString());
    }

    private boolean dedupeAnnotations(NodeWithAnnotations<?> node) {
        if (node == null) {
            return false;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<AnnotationExpr> duplicates = new ArrayList<>();
        for (AnnotationExpr annotation : node.getAnnotations()) {
            String key = annotation.toString().trim();
            if (!seen.add(key)) {
                duplicates.add(annotation);
            }
        }
        duplicates.forEach(AnnotationExpr::remove);
        return !duplicates.isEmpty();
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

    public record AppendResult(String source,
                               GeneratedTestSnippet mergedSnippet,
                               boolean changed,
                               String diagnostic) {
    }

    private record PreparedSnippet(GeneratedTestSnippet snippet, boolean shouldAppend, String diagnostic) {
    }
}
