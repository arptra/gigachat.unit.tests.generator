package com.gigachat.unit.tests.generator;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.config.AgentMode;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.scanner.SingleFileProjectScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SingleFileProjectScannerTest {

    @TempDir
    Path workingDirectory;

    @Test
    void scansOnlySpecifiedFile() throws IOException {
        Path moduleRoot = workingDirectory.resolve("module-a");
        Path sourceFolder = moduleRoot.resolve(Path.of("src", "main", "java", "com", "example", "demo"));
        Files.createDirectories(sourceFolder);
        Path javaFile = sourceFolder.resolve("SingleModeService.java");
        Files.writeString(javaFile, """
                package com.example.demo;

                public class SingleModeService {
                    public String status() {
                        return \"ok\";
                    }
                }
                """);

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(workingDirectory)
                .build();

        SingleFileProjectScanner scanner = new SingleFileProjectScanner();
        List<TestClassInfo> result = scanner.scan(javaFile, config);

        assertEquals(1, result.size());
        TestClassInfo info = result.get(0);
        assertEquals("SingleModeService", info.getClassName());
        assertEquals("SingleModeServiceTest", info.getTestClassName());
        Path expectedTarget = moduleRoot.resolve(Path.of(
                "src", "test", "java", "com", "example", "demo", "SingleModeServiceTest.java"));
        assertEquals(expectedTarget.toAbsolutePath().normalize(), info.getTargetPath());
        assertFalse(info.getMethods().isEmpty());
        assertEquals("public String status()", info.getMethods().get(0).getSignature());
    }
}
