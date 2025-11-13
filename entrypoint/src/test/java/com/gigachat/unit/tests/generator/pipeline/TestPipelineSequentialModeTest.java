package com.gigachat.unit.tests.generator.pipeline;

import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.config.AgentMode;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.scanner.JavaProjectScanner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPipelineSequentialModeTest {

    @Test
    void usesProjectScannerWhenSequentialModeDisabled() throws IOException {
        RecordingScanner scanner = new RecordingScanner();
        TestPipeline pipeline = new TestPipeline(scanner, scanner.getMethodRegistry());

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(Path.of("."))
                .build();

        List<TestClassInfo> result = pipeline.execute(config);

        assertTrue(scanner.scanInvoked);
        assertFalse(scanner.sequentialInvoked);
        assertEquals(scanner.scanResult, result);
    }

    @Test
    void usesSequentialScannerWhenModeEnabled() throws IOException {
        RecordingScanner scanner = new RecordingScanner();
        TestPipeline pipeline = new TestPipeline(scanner, scanner.getMethodRegistry());

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(Path.of("."))
                .singleFileMode(true)
                .build();

        List<TestClassInfo> result = pipeline.execute(config);

        assertFalse(scanner.scanInvoked);
        assertTrue(scanner.sequentialInvoked);
        assertTrue(result.isEmpty());
    }

    private static final class RecordingScanner extends JavaProjectScanner {
        boolean scanInvoked;
        boolean sequentialInvoked;
        final List<TestClassInfo> scanResult = List.of();

        RecordingScanner() {
            super(new MethodSignatureRegistry());
        }

        @Override
        public List<TestClassInfo> scan(AgentConfig config) {
            scanInvoked = true;
            return scanResult;
        }

        @Override
        public void scanSequentially(AgentConfig config, Consumer<List<TestClassInfo>> perFileConsumer) {
            sequentialInvoked = true;
        }
    }
}
