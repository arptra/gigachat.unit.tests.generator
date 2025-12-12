package com.example.importanalyzer.core;

import java.nio.file.Path;

public sealed interface ImportIssue permits MissingImportIssue, UnresolvedImportIssue, AmbiguousImportIssue, UnusedImportIssue, WrongPackageIssue, WildcardIssue {
    Path file();
    int line();
    String message();
    String symbol();
    IssueType type();
}
