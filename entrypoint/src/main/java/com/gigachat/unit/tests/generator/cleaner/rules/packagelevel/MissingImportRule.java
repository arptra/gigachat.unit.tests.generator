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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Removes imports that fail real compilation. The rule compiles the target test file with a
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
        boolean changed = false;
        for (Path testFile : testFiles) {
            changed |= applyToFile(projectRoot, testFile);
        }
        return changed;
    }

    private boolean applyToFile(Path projectRoot, Path testFile) throws IOException {
        TestFileContext context = new TestFileContext(testFile, new JavaParser());
        CompilationUnit unit = context.getCompilationUnit().orElse(null);
        if (unit == null) {
            return false;
        }

        List<ImportDeclaration> imports = unit.getImports();
        if (imports == null || imports.isEmpty()) {
            return false;
        }

        Set<ImportDeclaration> toRemove = detectInvalidImports(projectRoot, context.getFile(), imports);
        if (toRemove.isEmpty()) {
            return false;
        }

        toRemove.forEach(Node::remove);
        context.markAstDirty();
        context.saveIfDirty();
        return true;
    }

    private Set<ImportDeclaration> detectInvalidImports(Path projectRoot, Path file, List<ImportDeclaration> imports) {
        Path root = projectRoot != null ? projectRoot : classIndex.getProjectRoot();
        if (root == null) {
            return Collections.emptySet();
        }

        CompileResult result = compilerInvoker.compile(root, file, "missing-import-rule");
        if (result.success()) {
            return Collections.emptySet();
        }

        String logOutput = Stream.of(result.stdout(), result.stderr())
                .filter(output -> output != null && !output.isBlank())
                .collect(Collectors.joining(System.lineSeparator()));
        List<CompilationFailureLocation> failures = failureLogParser.parse(logOutput);
        if (failures.isEmpty()) {
            return Collections.emptySet();
        }

        Set<Integer> importLines = imports.stream()
                .map(importDecl -> importDecl.getBegin().map(position -> position.line).orElse(-1))
                .filter(line -> line >= 0)
                .collect(Collectors.toSet());

        Path normalizedFile = file.toAbsolutePath().normalize();
        Set<ImportDeclaration> toRemove = new HashSet<>();
        for (CompilationFailureLocation failure : failures) {
            if (!normalizedFile.equals(failure.filePath().toAbsolutePath().normalize())) {
                continue;
            }
            if (!importLines.contains(failure.lineNumber())) {
                continue;
            }
            imports.stream()
                    .filter(importDecl -> importDecl.getBegin()
                            .map(position -> position.line == failure.lineNumber())
                            .orElse(false))
                    .forEach(toRemove::add);
        }

        return toRemove;
    }
}
