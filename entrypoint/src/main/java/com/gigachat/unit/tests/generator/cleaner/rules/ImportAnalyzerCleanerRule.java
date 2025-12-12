package com.gigachat.unit.tests.generator.cleaner.rules;

import com.example.importanalyzer.core.AsyncImportAnalyzerService;
import com.example.importanalyzer.core.ImportAction;
import com.example.importanalyzer.core.ImportSource;
import com.example.importanalyzer.core.ScanResult;
import com.gigachat.unit.tests.generator.cleaner.CleanerRule;
import com.gigachat.unit.tests.generator.cleaner.TestFileContext;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Integrates the Java Import Analyzer into the cleaner pipeline.
 * <p>
 * The rule waits for the analyzer to finish indexing the project, logging progress once per minute
 * while the scan runs. After completion it applies the suggested import action for the current test
 * file (adding, deleting or selecting imports based on {@link ScanResult}).
 */
public final class ImportAnalyzerCleanerRule implements CleanerRule {
    private static final Duration PROGRESS_LOG_INTERVAL = Duration.ofMinutes(1);

    private final AsyncImportAnalyzerService analyzerService;
    private final PipelineLogger logger;

    public ImportAnalyzerCleanerRule(AsyncImportAnalyzerService analyzerService, PipelineLogger logger) {
        this.analyzerService = Objects.requireNonNull(analyzerService, "analyzerService");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public boolean apply(TestFileContext context) throws IOException {
        CompilationUnit unit = context.getCompilationUnit().orElse(null);
        if (unit == null) {
            return false;
        }

        ScanResult result = awaitResult(context.getFile());
        if (result == null || result.action() == ImportAction.UNKNOWN) {
            return false;
        }

        return switch (result.action()) {
            case ADD -> applyAddition(unit, result, context);
            case DELETE -> applyDeletion(unit, result, context);
            case SELECT -> applySelection(unit, result, context);
            default -> false;
        };
    }

    private boolean applyAddition(CompilationUnit unit, ScanResult result, TestFileContext context) {
        Optional<String> candidate = selectCandidate(result);
        if (candidate.isEmpty()) {
            return false;
        }
        String fqcn = candidate.get();
        boolean alreadyImported = unit.getImports().stream()
                .anyMatch(importDecl -> importDecl.getNameAsString().equals(fqcn));
        if (alreadyImported) {
            return false;
        }
        unit.addImport(fqcn);
        context.markAstDirty();
        return true;
    }

    private boolean applyDeletion(CompilationUnit unit, ScanResult result, TestFileContext context) {
        List<ImportDeclaration> imports = unit.getImports();
        List<ImportDeclaration> targets = imports.stream()
                .filter(importDecl -> matchesLine(result, importDecl) || matchesCandidate(result, importDecl))
                .toList();
        if (targets.isEmpty()) {
            return false;
        }
        targets.forEach(Node::remove);
        context.markAstDirty();
        return true;
    }

    private boolean applySelection(CompilationUnit unit, ScanResult result, TestFileContext context) {
        Optional<String> candidate = selectCandidate(result);
        if (candidate.isEmpty()) {
            return false;
        }
        String fqcn = candidate.get();
        List<ImportDeclaration> imports = unit.getImports();
        Optional<ImportDeclaration> existing = imports.stream()
                .filter(importDecl -> importDecl.getNameAsString().equals(fqcn))
                .findFirst();

        if (existing.isPresent()) {
            return false;
        }

        imports.stream()
                .filter(importDecl -> importDecl.getName().getIdentifier().equals(simpleName(fqcn)))
                .findFirst()
                .ifPresent(Node::remove);

        unit.addImport(fqcn);
        context.markAstDirty();
        return true;
    }

    private ScanResult awaitResult(Path file) {
        Instant lastLog = Instant.MIN;
        ScanResult result = null;
        while (result == null || result.inProgress()) {
            result = analyzerService.scan(file).join();
            if (!result.inProgress()) {
                break;
            }
            Instant now = Instant.now();
            if (Duration.between(lastLog, now).compareTo(PROGRESS_LOG_INTERVAL) >= 0) {
                logger.info("Import analyzer indexing progress: " + result.scannedFiles()
                        + "/" + result.totalFiles());
                lastLog = now;
            }
            try {
                Thread.sleep(PROGRESS_LOG_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return result;
    }

    private Optional<String> selectCandidate(ScanResult result) {
        if (result.candidates() == null || result.candidates().isEmpty()) {
            return Optional.empty();
        }
        if (result.action() == ImportAction.ADD && result.source() == ImportSource.LOCAL) {
            return result.candidates().stream().findFirst();
        }
        return result.candidates().stream()
                .min(Comparator.comparing(String::length));
    }

    private boolean matchesLine(ScanResult result, ImportDeclaration importDecl) {
        return result.line() > 0 && importDecl.getRange()
                .map(range -> range.begin.line == result.line())
                .orElse(false);
    }

    private boolean matchesCandidate(ScanResult result, ImportDeclaration importDecl) {
        if (result.candidates() == null || result.candidates().isEmpty()) {
            return false;
        }
        String name = importDecl.getNameAsString();
        String identifier = importDecl.getName().getIdentifier();
        return result.candidates().stream()
                .anyMatch(candidate -> candidate.equals(name) || candidate.endsWith('.' + identifier));
    }

    private String simpleName(String fqcn) {
        int idx = fqcn.lastIndexOf('.') + 1;
        return idx >= 0 && idx < fqcn.length() ? fqcn.substring(idx) : fqcn;
    }
}
