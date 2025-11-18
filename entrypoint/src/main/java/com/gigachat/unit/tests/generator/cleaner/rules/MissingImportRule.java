package com.gigachat.unit.tests.generator.cleaner.rules;

import com.gigachat.unit.tests.generator.cleaner.CleanerRule;
import com.gigachat.unit.tests.generator.cleaner.ProjectClassIndex;
import com.gigachat.unit.tests.generator.cleaner.TestFileContext;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes imports that reference classes that no longer exist inside the project sources.
 */
public final class MissingImportRule implements CleanerRule {
    private static final Set<String> WHITELISTED_PREFIXES = Set.of(
            "java.",
            "javax.",
            "jakarta.",
            "org.junit.",
            "org.mockito.",
            "org.assertj.",
            "org.hamcrest.",
            "org.springframework.",
            "kotlin.");

    private final ProjectClassIndex classIndex;

    public MissingImportRule(ProjectClassIndex classIndex) {
        this.classIndex = classIndex;
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
        for (String prefix : WHITELISTED_PREFIXES) {
            if (candidate.startsWith(prefix)) {
                return true;
            }
        }
        if (classIndex.contains(candidate)) {
            return true;
        }
        try {
            Class.forName(candidate);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
