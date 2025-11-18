package com.gigachat.unit.tests.generator.cleaner.parser;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record ExecutionFailureParseResult(List<TestFailure> failures, Optional<Path> reportPath) {
    public ExecutionFailureParseResult {
        failures = failures == null ? List.of() : List.copyOf(failures);
        reportPath = reportPath == null ? Optional.empty() : reportPath;
    }
}
