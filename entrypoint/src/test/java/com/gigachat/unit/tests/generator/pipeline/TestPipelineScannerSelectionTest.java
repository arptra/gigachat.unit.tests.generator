package com.gigachat.unit.tests.generator.pipeline;

import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.config.AgentMode;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.scanner.JavaProjectScanner;
import com.gigachat.unit.tests.generator.scanner.SingleFileProjectScanner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPipelineScannerSelectionTest {

    @Test
    void prefersProjectScannerWhenSingleFileIsAbsent() throws IOException {
        RecordingProjectScanner projectScanner = new RecordingProjectScanner();
        RecordingSingleFileScanner singleFileScanner = new RecordingSingleFileScanner();
        TestPipeline pipeline = new TestPipeline(projectScanner, singleFileScanner, new MethodSignatureRegistry());

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(Path.of("."))
                .build();

        List<TestClassInfo> result = pipeline.scanClasses(config);

        assertTrue(projectScanner.invoked);
        assertFalse(singleFileScanner.invoked);
        assertEquals(projectScanner.result, result);
    }

    @Test
    void delegatesToSingleFileScannerWhenPathProvided() throws IOException {
        RecordingProjectScanner projectScanner = new RecordingProjectScanner();
        RecordingSingleFileScanner singleFileScanner = new RecordingSingleFileScanner();
        TestPipeline pipeline = new TestPipeline(projectScanner, singleFileScanner, new MethodSignatureRegistry());

        Path singleFile = Path.of("SingleFile.java").toAbsolutePath();
        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(Path.of("."))
                .singleFile(singleFile)
                .build();

        List<TestClassInfo> result = pipeline.scanClasses(config);

        assertFalse(projectScanner.invoked);
        assertTrue(singleFileScanner.invoked);
        assertEquals(singleFileScanner.result, result);
        assertEquals(singleFile, singleFileScanner.invokedWith);
    }

    private static final class RecordingProjectScanner extends JavaProjectScanner {
        boolean invoked;
        final List<TestClassInfo> result = List.of();

        @Override
        public List<TestClassInfo> scan(AgentConfig config) {
            invoked = true;
            return result;
        }
    }

    private static final class RecordingSingleFileScanner extends SingleFileProjectScanner {
        boolean invoked;
        Path invokedWith;
        final List<TestClassInfo> result = List.of();

        @Override
        public List<TestClassInfo> scan(Path javaFile, AgentConfig config) {
            invoked = true;
            invokedWith = javaFile;
            return result;
        }
    }
}
