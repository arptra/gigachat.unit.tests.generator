package com.gigachat.unit.tests.generator.compile;

import java.nio.file.Path;

/**
 * Compiles generated test classes.
 */
public interface CompilerInvoker {

    CompileResult compile(Path projectRoot, Path testClassFile, String methodName);
}
