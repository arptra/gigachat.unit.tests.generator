package com.gigachat.unit.tests.generator.cleaner.rules.packagelevel;

import com.gigachat.unit.tests.generator.cleaner.ProjectClassIndex;
import com.gigachat.unit.tests.generator.cleaner.TestFileContext;
import com.gigachat.unit.tests.generator.cleaner.parser.CompilationFailureLocation;
import com.gigachat.unit.tests.generator.cleaner.parser.CompilationFailureLogParser;
import com.gigachat.unit.tests.generator.cleaner.rules.packagelevel.api.TestPackageCleanerRule;
import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.compile.GradleCompilerInvoker;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Removes imports that fail real compilation. The rule compiles all discovered test sources with a
 * classpath built from the provided project root (including Gradle caches and local libraries).
 * Any compilation errors that point at import statements cause the corresponding imports to be
 * removed.
 */
public final class MissingImportRule implements TestPackageCleanerRule {
    private final ProjectClassIndex classIndex;
    private final CompilerInvoker compilerInvoker;
    private final CompilationFailureLogParser failureLogParser = new CompilationFailureLogParser();

    public MissingImportRule(ProjectClassIndex classIndex) {
        this(classIndex, new GradleCompilerInvoker(new PipelineLogger(classIndex.getProjectRoot()), true));
    }

    public MissingImportRule(ProjectClassIndex classIndex, CompilerInvoker compilerInvoker) {
        this.classIndex = Objects.requireNonNull(classIndex, "classIndex");
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
    }

    @Override
    public boolean apply(Path projectRoot, List<Path> testFiles) throws IOException {
        Path root = projectRoot != null ? projectRoot : classIndex.getProjectRoot();
        if (root == null) {
            return false;
        }

        Map<Path, TestFileContext> contextsByFile = new HashMap<>();
        Map<Path, List<ImportDeclaration>> importsByFile = new HashMap<>();

        for (Path testFile : testFiles) {
            TestFileContext context = new TestFileContext(testFile, new JavaParser());
            CompilationUnit unit = context.getCompilationUnit().orElse(null);
            if (unit == null) {
                continue;
            }

            List<ImportDeclaration> imports = unit.getImports();
            if (imports == null || imports.isEmpty()) {
                continue;
            }

            Path normalized = testFile.toAbsolutePath().normalize();
            contextsByFile.put(normalized, context);
            importsByFile.put(normalized, new ArrayList<>(imports));
        }

        if (importsByFile.isEmpty()) {
            return false;
        }

        Map<Path, Set<ImportDeclaration>> toRemoveByFile = detectInvalidImports(root, importsByFile);
        if (toRemoveByFile.isEmpty()) {
            return false;
        }

        boolean changed = false;
        for (Map.Entry<Path, Set<ImportDeclaration>> entry : toRemoveByFile.entrySet()) {
            TestFileContext context = contextsByFile.get(entry.getKey());
            if (context == null) {
                continue;
            }
            entry.getValue().forEach(Node::remove);
            context.markAstDirty();
            context.saveIfDirty();
            changed = true;
        }

        return changed;
    }

    private Map<Path, Set<ImportDeclaration>> detectInvalidImports(Path projectRoot, Map<Path, List<ImportDeclaration>> importsByFile) {
        if (projectRoot == null) {
            return Collections.emptyMap();
        }

        CompileResult result = compilerInvoker.compileAllTests(projectRoot, "missing-import-rule");
        if (result.success()) {
            return Collections.emptyMap();
        }

        String logOutput = Stream.of(result.stdout(), result.stderr())
                .filter(output -> output != null && !output.isBlank())
                .collect(Collectors.joining(System.lineSeparator()));
        List<CompilationFailureLocation> failures = failureLogParser.parse(logOutput);
        if (failures.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Path, Set<ImportDeclaration>> toRemoveByFile = new HashMap<>();
        Map<Path, Set<Integer>> importLinesByFile = importsByFile.entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getKey().toAbsolutePath().normalize(), entry -> entry.getValue()
                        .stream()
                        .map(importDecl -> importDecl.getBegin().map(position -> position.line).orElse(-1))
                        .filter(line -> line >= 0)
                        .collect(Collectors.toSet())));

        for (CompilationFailureLocation failure : failures) {
            Path normalizedFile = failure.filePath().toAbsolutePath().normalize();
            Set<Integer> importLines = importLinesByFile.get(normalizedFile);
            if (importLines == null || !importLines.contains(failure.lineNumber())) {
                continue;
            }

            importsByFile.getOrDefault(normalizedFile, List.of()).stream()
                    .filter(importDecl -> importDecl.getBegin()
                            .map(position -> position.line == failure.lineNumber())
                            .orElse(false))
                    .forEach(importDecl -> toRemoveByFile
                            .computeIfAbsent(normalizedFile, ignored -> new HashSet<>())
                            .add(importDecl));
        }

        return toRemoveByFile;
    }
}
