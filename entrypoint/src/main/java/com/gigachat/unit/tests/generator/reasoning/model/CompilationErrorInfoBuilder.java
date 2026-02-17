package com.gigachat.unit.tests.generator.reasoning.model;

import com.gigachat.unit.tests.generator.compile.CompileResult;

import java.nio.file.Path;

/**
 * Utility to convert compilation results into a {@link CompilationErrorInfo} payload
 * consumable by the reasoning workflow.
 */
public final class CompilationErrorInfoBuilder {

    private CompilationErrorInfoBuilder() {
    }

    /**
     * Creates a {@link CompilationErrorInfo} using only the raw {@link CompileResult} details.
     *
     * @param result compilation result
     * @return error info describing the failure
     */
    public static CompilationErrorInfo from(CompileResult result) {
        return from(result, null, null);
    }

    /**
     * Creates a {@link CompilationErrorInfo} enriched with the test file path and its FQCN.
     *
     * @param result     compilation result
     * @param testFile   path to the generated test file
     * @param testFileFqcn fully qualified class name of the generated test
     * @return error info describing the failure
     */
    public static CompilationErrorInfo from(CompileResult result, Path testFile, String testFileFqcn) {
        String compilerOutput = ((result.stdout() == null ? "" : result.stdout())
                + System.lineSeparator()
                + (result.stderr() == null ? "" : result.stderr())).trim();
        String primaryMessage = result.messages().isEmpty() ? result.stderr() : result.messages().get(0);
        CompilationErrorInfo info = new CompilationErrorInfo();
        info.setCompilerOutput(compilerOutput);
        info.setPrimaryMessage(primaryMessage);
        info.setTestFilePath(testFile == null ? null : testFile.toAbsolutePath().normalize().toString());
        info.setTestFileFqcn(testFileFqcn);
        return info;
    }
}

