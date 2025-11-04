package com.gigachat.unit.tests.generator.pipeline;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.scanner.JavaProjectScanner;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class TestPipeline {
    private final JavaProjectScanner scanner;

    public TestPipeline() {
        this(new JavaProjectScanner());
    }

    public TestPipeline(JavaProjectScanner scanner) {
        this.scanner = Objects.requireNonNull(scanner, "scanner");
    }

    public List<TestClassInfo> execute(AgentConfig config) throws IOException {
        List<TestClassInfo> classes = scanner.scan(config);
        System.out.printf("Scan completed: %d classes detected.%n", classes.size());
        return classes;
    }
}
