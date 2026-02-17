package com.gigachat.unit.tests.generator.reasoning.model;

import java.util.Objects;

public class CompilationErrorInfo {

    private String compilerOutput;
    private String primaryMessage;
    private String testFileFqcn;
    private String testFilePath;
    private Integer line;
    private String stacktrace;

    public CompilationErrorInfo() {
    }

    public CompilationErrorInfo(String compilerOutput,
                                String primaryMessage,
                                String testFileFqcn,
                                String testFilePath,
                                Integer line,
                                String stacktrace) {
        this.compilerOutput = compilerOutput;
        this.primaryMessage = primaryMessage;
        this.testFileFqcn = testFileFqcn;
        this.testFilePath = testFilePath;
        this.line = line;
        this.stacktrace = stacktrace;
    }

    public String getCompilerOutput() {
        return compilerOutput;
    }

    public void setCompilerOutput(String compilerOutput) {
        this.compilerOutput = compilerOutput;
    }

    public String getPrimaryMessage() {
        return primaryMessage;
    }

    public void setPrimaryMessage(String primaryMessage) {
        this.primaryMessage = primaryMessage;
    }

    public String getTestFileFqcn() {
        return testFileFqcn;
    }

    public void setTestFileFqcn(String testFileFqcn) {
        this.testFileFqcn = testFileFqcn;
    }

    public String getTestFilePath() {
        return testFilePath;
    }

    public void setTestFilePath(String testFilePath) {
        this.testFilePath = testFilePath;
    }

    public Integer getLine() {
        return line;
    }

    public void setLine(Integer line) {
        this.line = line;
    }

    public String getStacktrace() {
        return stacktrace;
    }

    public void setStacktrace(String stacktrace) {
        this.stacktrace = stacktrace;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CompilationErrorInfo that = (CompilationErrorInfo) o;
        return Objects.equals(compilerOutput, that.compilerOutput)
                && Objects.equals(primaryMessage, that.primaryMessage)
                && Objects.equals(testFileFqcn, that.testFileFqcn)
                && Objects.equals(testFilePath, that.testFilePath)
                && Objects.equals(line, that.line)
                && Objects.equals(stacktrace, that.stacktrace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(compilerOutput, primaryMessage, testFileFqcn, testFilePath, line, stacktrace);
    }
}
