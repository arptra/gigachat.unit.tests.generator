package com.gigachat.unit.tests.generator.execute;

import java.nio.file.Path;

/**
 * Executes generated tests.
 */
public interface ExecutionInvoker {

    ExecuteResult execute(Path projectRoot, Path testClassFile, String methodName);
}
