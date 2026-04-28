package com.gigachat.unit.tests.generator.coverage;

import java.nio.file.Path;

public interface CoverageInvoker {
    CoverageResult measure(Path projectRoot,
                           Path testClassFile,
                           String generatedTestMethodName,
                           String targetClassName,
                           String targetMethodSignature);
}
