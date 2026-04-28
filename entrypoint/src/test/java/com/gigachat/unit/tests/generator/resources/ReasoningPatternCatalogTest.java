package com.gigachat.unit.tests.generator.resources;

import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.reasoning.model.ProjectContextSummary;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningMemory;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningStage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReasoningPatternCatalogTest {

    @Test
    void shouldMatchCompilePatternFromErrorText() {
        ReasoningPatternCatalog catalog = new ReasoningPatternCatalog();

        List<ReasoningPattern> matches = catalog.match(new ReasoningLoopContext(
                new CompilationErrorInfo("cannot find symbol", "cannot find symbol", "fqcn", "path", 1, null),
                new ProjectContextSummary(),
                ActionExecutionResult.empty(),
                null,
                new ReasoningMemory(),
                ReasoningStage.COMPILATION
        ));

        assertFalse(matches.isEmpty());
        assertEquals("C001_MISSING_IMPORT_OR_SYMBOL", matches.get(0).id());
    }

    @Test
    void shouldMatchDeprecatedMockitoHelperCompilePattern() {
        ReasoningPatternCatalog catalog = new ReasoningPatternCatalog();

        List<ReasoningPattern> matches = catalog.match(new ReasoningLoopContext(
                new CompilationErrorInfo(
                        "cannot find symbol",
                        "error: cannot find symbol\nsymbol: method verifyZeroInteractions(com.example.Service)",
                        "fqcn",
                        "path",
                        1,
                        null),
                new ProjectContextSummary(),
                ActionExecutionResult.empty(),
                null,
                new ReasoningMemory(),
                ReasoningStage.COMPILATION
        ));

        assertFalse(matches.isEmpty());
        assertEquals("C001A_DEPRECATED_MOCKITO_HELPER", matches.stream()
                .filter(pattern -> "C001A_DEPRECATED_MOCKITO_HELPER".equals(pattern.id()))
                .findFirst()
                .orElseThrow()
                .id());
    }

    @Test
    void shouldMatchWrongProjectImportCompilePattern() {
        ReasoningPatternCatalog catalog = new ReasoningPatternCatalog();

        List<ReasoningPattern> matches = catalog.match(new ReasoningLoopContext(
                new CompilationErrorInfo(
                        "cannot find symbol",
                        "error: cannot find symbol\nsymbol: class NotificationService\nlocation: package com.example.app.util",
                        "fqcn",
                        "path",
                        1,
                        null),
                new ProjectContextSummary(),
                ActionExecutionResult.empty(),
                null,
                new ReasoningMemory(),
                ReasoningStage.COMPILATION
        ));

        assertFalse(matches.isEmpty());
        assertEquals("C001B_WRONG_PROJECT_IMPORT", matches.stream()
                .filter(pattern -> "C001B_WRONG_PROJECT_IMPORT".equals(pattern.id()))
                .findFirst()
                .orElseThrow()
                .id());
    }

    @Test
    void shouldMatchRuntimePatternFromErrorText() {
        ReasoningPatternCatalog catalog = new ReasoningPatternCatalog();

        List<ReasoningPattern> matches = catalog.match(new ReasoningLoopContext(
                new CompilationErrorInfo("org.mockito.exceptions.misusing.NotAMockException", "NotAMockException", "fqcn", "path", null, null),
                new ProjectContextSummary(),
                ActionExecutionResult.empty(),
                null,
                new ReasoningMemory(),
                ReasoningStage.EXECUTION
        ));

        assertFalse(matches.isEmpty());
        assertEquals("R001_NOT_A_MOCK", matches.get(0).id());
    }

    @Test
    void shouldMatchNullInsteadOfMockRuntimePatternFromErrorText() {
        ReasoningPatternCatalog catalog = new ReasoningPatternCatalog();

        List<ReasoningPattern> matches = catalog.match(new ReasoningLoopContext(
                new CompilationErrorInfo(
                        "org.mockito.exceptions.misusing.NullInsteadOfMockException",
                        "Argument passed to verify() should be a mock but is null",
                        "fqcn",
                        "path",
                        null,
                        null),
                new ProjectContextSummary(),
                ActionExecutionResult.empty(),
                null,
                new ReasoningMemory(),
                ReasoningStage.EXECUTION
        ));

        assertFalse(matches.isEmpty());
        assertEquals("R001A_NULL_INSTEAD_OF_MOCK", matches.get(0).id());
    }

    @Test
    void shouldMatchTemporalNowAssertionRuntimePatternFromErrorText() {
        ReasoningPatternCatalog catalog = new ReasoningPatternCatalog();

        List<ReasoningPattern> matches = catalog.match(new ReasoningLoopContext(
                new CompilationErrorInfo(
                        "java.lang.AssertionError: The last login time did not match the expected value.",
                        "The last login time did not match the expected value.",
                        "fqcn",
                        "path",
                        null,
                        null),
                new ProjectContextSummary(),
                ActionExecutionResult.empty(),
                null,
                new ReasoningMemory(),
                ReasoningStage.EXECUTION
        ));

        assertFalse(matches.isEmpty());
        assertEquals("R006B_TEMPORAL_NOW_ASSERTION_WINDOW", matches.get(0).id());
    }

    @Test
    void shouldMatchReboundThresholdAttemptsRuntimePatternFromErrorText() {
        ReasoningPatternCatalog catalog = new ReasoningPatternCatalog();

        List<ReasoningPattern> matches = catalog.match(new ReasoningLoopContext(
                new CompilationErrorInfo(
                        "org.opentest4j.AssertionFailedError: expected: <true> but was: <false>",
                        "testRollbackHighRebound expected: <true> but was: <false>; verify sendDeactivationNotice on rebound branch",
                        "fqcn",
                        "path",
                        null,
                        null),
                new ProjectContextSummary(),
                ActionExecutionResult.empty(),
                null,
                new ReasoningMemory(),
                ReasoningStage.EXECUTION
        ));

        assertFalse(matches.isEmpty());
        assertEquals("R006C_REBOUND_THRESHOLD_ATTEMPTS_TOO_LOW", matches.get(0).id());
    }

    @Test
    void shouldMatchCoverageRuntimeRegressionPattern() {
        ReasoningPatternCatalog catalog = new ReasoningPatternCatalog();

        List<ReasoningPattern> matches = catalog.match(new ReasoningLoopContext(
                new CompilationErrorInfo(
                        "Execution failed for task ':test'",
                        "There were failing tests. Execution failed for task ':test'",
                        "fqcn",
                        "path",
                        null,
                        null),
                new ProjectContextSummary(),
                ActionExecutionResult.empty(),
                null,
                new ReasoningMemory(),
                ReasoningStage.COVERAGE
        ));

        assertFalse(matches.isEmpty());
        assertEquals("V004_CLASS_LEVEL_RUNTIME_REGRESSION", matches.stream()
                .filter(pattern -> "V004_CLASS_LEVEL_RUNTIME_REGRESSION".equals(pattern.id()))
                .findFirst()
                .orElseThrow()
                .id());
    }
}
