package com.example.importanalyzer.core;

import java.nio.file.Path;

public record UnresolvedImportIssue(Path file, int line, String symbol, String message) implements ImportIssue {
    @Override
    public IssueType type() {
        return IssueType.UNRESOLVED_IMPORT;
    }
}
