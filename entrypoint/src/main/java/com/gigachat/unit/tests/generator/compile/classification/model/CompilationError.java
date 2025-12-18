package com.gigachat.unit.tests.generator.compile.classification.model;

import java.util.Objects;

/**
 * Represents a single compiler error with extracted metadata for downstream classification.
 */
public class CompilationError {

    private final String filePath;
    private final Integer line;
    private final Integer column;
    private final String rawMessage;
    private final String normalizedMessage;
    private final String symbol;
    private final String packageName;
    private final String methodName;
    private final CompilationErrorClass errorClass;

    private CompilationError(Builder builder) {
        this.filePath = builder.filePath;
        this.line = builder.line;
        this.column = builder.column;
        this.rawMessage = Objects.requireNonNull(builder.rawMessage, "rawMessage");
        this.normalizedMessage = Objects.requireNonNull(builder.normalizedMessage, "normalizedMessage");
        this.symbol = builder.symbol;
        this.packageName = builder.packageName;
        this.methodName = builder.methodName;
        this.errorClass = Objects.requireNonNull(builder.errorClass, "errorClass");
    }

    public String getFilePath() {
        return filePath;
    }

    public Integer getLine() {
        return line;
    }

    public Integer getColumn() {
        return column;
    }

    public String getRawMessage() {
        return rawMessage;
    }

    public String getNormalizedMessage() {
        return normalizedMessage;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getMethodName() {
        return methodName;
    }

    public CompilationErrorClass getErrorClass() {
        return errorClass;
    }

    public Builder toBuilder() {
        return new Builder()
                .filePath(filePath)
                .line(line)
                .column(column)
                .rawMessage(rawMessage)
                .normalizedMessage(normalizedMessage)
                .symbol(symbol)
                .packageName(packageName)
                .methodName(methodName)
                .errorClass(errorClass);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String filePath;
        private Integer line;
        private Integer column;
        private String rawMessage;
        private String normalizedMessage;
        private String symbol;
        private String packageName;
        private String methodName;
        private CompilationErrorClass errorClass = CompilationErrorClass.OTHER;

        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        public Builder line(Integer line) {
            this.line = line;
            return this;
        }

        public Builder column(Integer column) {
            this.column = column;
            return this;
        }

        public Builder rawMessage(String rawMessage) {
            this.rawMessage = rawMessage;
            return this;
        }

        public Builder normalizedMessage(String normalizedMessage) {
            this.normalizedMessage = normalizedMessage;
            return this;
        }

        public Builder symbol(String symbol) {
            this.symbol = symbol;
            return this;
        }

        public Builder packageName(String packageName) {
            this.packageName = packageName;
            return this;
        }

        public Builder methodName(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public Builder errorClass(CompilationErrorClass errorClass) {
            this.errorClass = errorClass;
            return this;
        }

        public CompilationError build() {
            return new CompilationError(this);
        }
    }
}
