package com.gigachat.unit.tests.generator.compile.classification.classify;

import com.gigachat.unit.tests.generator.compile.classification.model.CompilationError;
import com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorClass;
import com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorReport;
import com.gigachat.unit.tests.generator.compile.classification.parse.CompilationErrorParser;
import com.gigachat.unit.tests.generator.compile.classification.parse.CompilerOutputNormalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies compilation errors into broad categories for prioritisation.
 */
public class CompilationErrorClassifier {

    private static final Pattern PACKAGE_MISSING = Pattern.compile("package\\s+([^\\s]+)\\s+does\\s+not\\s+exist", Pattern.CASE_INSENSITIVE);
    private static final Pattern CANNOT_FIND_SYMBOL = Pattern.compile("cannot\\s+find\\s+symbol", Pattern.CASE_INSENSITIVE);
    private static final Pattern SYMBOL_LINE = Pattern.compile("symbol:\\s+(?:class|variable|method)?\\s*([\\w$.]+)");
    private static final Pattern METHOD_APPLY = Pattern.compile("method\\s+([\\w$.<>]+)\\s+in\\s+class", Pattern.CASE_INSENSITIVE);
    private static final Pattern METHOD_CANNOT_APPLY = Pattern.compile("cannot\\s+be\\s+applied\\s+to", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_SUITABLE_METHOD = Pattern.compile("no\\s+suitable\\s+method\\s+found", Pattern.CASE_INSENSITIVE);
    private static final Pattern INCOMPATIBLE_TYPES = Pattern.compile("incompatible\\s+types|cannot\\s+be\\s+converted", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCESS_VIOLATION = Pattern.compile("has\\s+private\\s+access|is\\s+not\\s+public", Pattern.CASE_INSENSITIVE);
    private static final Pattern SYNTAX = Pattern.compile(";'\\s+expected|reached\\s+end\\s+of\\s+file", Pattern.CASE_INSENSITIVE);

    private final CompilerOutputNormalizer normalizer;
    private final CompilationErrorParser parser;

    public CompilationErrorClassifier() {
        this(new CompilerOutputNormalizer(), new CompilationErrorParser());
    }

    public CompilationErrorClassifier(CompilerOutputNormalizer normalizer, CompilationErrorParser parser) {
        this.normalizer = normalizer;
        this.parser = parser;
    }

    public CompilationErrorReport classify(String rawCompilerOutput) {
        String normalized = normalizer.normalize(rawCompilerOutput);
        List<CompilationError> parsed = parser.parse(normalized);
        List<CompilationError> classified = new ArrayList<>();
        for (CompilationError error : parsed) {
            classified.add(applyClassification(error));
        }
        return new CompilationErrorReport(classified, rawCompilerOutput);
    }

    private CompilationError applyClassification(CompilationError error) {
        String message = error.getNormalizedMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        CompilationErrorClass category = CompilationErrorClass.OTHER;
        String symbol = null;
        String packageName = null;
        String methodName = null;

        Matcher pkg = PACKAGE_MISSING.matcher(message);
        if (pkg.find()) {
            category = CompilationErrorClass.MISSING_DEPENDENCY_OR_PACKAGE;
            packageName = pkg.group(1);
        }

        Matcher cfs = CANNOT_FIND_SYMBOL.matcher(message);
        if (cfs.find()) {
            category = CompilationErrorClass.MISSING_IMPORT_OR_SYMBOL;
            Matcher symbolLine = SYMBOL_LINE.matcher(message);
            if (symbolLine.find()) {
                symbol = symbolLine.group(1);
            }
        }

        if (METHOD_APPLY.matcher(lower).find() && METHOD_CANNOT_APPLY.matcher(lower).find()) {
            category = CompilationErrorClass.METHOD_SIGNATURE_MISMATCH;
            Matcher methodMatcher = METHOD_APPLY.matcher(message);
            if (methodMatcher.find()) {
                methodName = methodMatcher.group(1);
            }
        }
        if (NO_SUITABLE_METHOD.matcher(lower).find()) {
            category = CompilationErrorClass.METHOD_SIGNATURE_MISMATCH;
        }

        if (INCOMPATIBLE_TYPES.matcher(lower).find()) {
            category = CompilationErrorClass.TYPE_MISMATCH;
        }

        if (ACCESS_VIOLATION.matcher(lower).find()) {
            category = CompilationErrorClass.ACCESS_VIOLATION;
        }

        if (SYNTAX.matcher(lower).find()) {
            category = CompilationErrorClass.SYNTAX_ERROR;
        }

        return error.toBuilder()
                .errorClass(category)
                .symbol(symbol != null ? symbol : error.getSymbol())
                .packageName(packageName != null ? packageName : error.getPackageName())
                .methodName(methodName != null ? methodName : error.getMethodName())
                .build();
    }
}
