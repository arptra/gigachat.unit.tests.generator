package com.example.importanalyzer.core;

import java.nio.file.Path;

public record AmbiguousImportIssue(Path file, int line, String symbol, String message) implements ImportIssue {
    @Override
    public IssueType type() {
        return IssueType.AMBIGUOUS_IMPORT;
    }
}
