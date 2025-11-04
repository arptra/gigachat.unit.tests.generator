package com.testagent.entrypoint;

import com.testagent.entrypoint.config.AgentConfig;
import com.testagent.entrypoint.dto.TestClassInfo;
import com.testagent.entrypoint.scanner.JavaProjectScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaProjectScannerTest {

    @TempDir
    Path workingDirectory;

    @Test
    void scansSingleModuleProject() throws IOException {
        Path sourceFolder = workingDirectory.resolve(Path.of("src", "main", "java", "com", "example", "demo"));
        Files.createDirectories(sourceFolder);
        Path javaFile = sourceFolder.resolve("SampleService.java");
        Files.writeString(javaFile, """
                package com.example.demo;
                
                public class SampleService {
                    public String findAll() {
                        return "ok";
                    }
                }
                """);

        AgentConfig config = AgentConfig.builder()
                .mode(AgentConfig.Mode.SCAN)
                .projectPath(workingDirectory)
                .build();

        JavaProjectScanner scanner = new JavaProjectScanner();
        List<TestClassInfo> result = scanner.scan(config);

        assertEquals(1, result.size());
        TestClassInfo info = result.getFirst();
        assertEquals("SampleServiceTest", info.className());
        Path expectedTarget = workingDirectory.resolve(Path.of("src", "test", "java", "com", "example", "demo"))
                .toAbsolutePath().normalize();
        assertEquals(expectedTarget, info.targetPath());
        assertTrue(info.imports().contains("org.junit.jupiter.api.Test"));
        assertFalse(info.methods().isEmpty());
        assertEquals("public void findAllTest()", info.methods().getFirst().signature());
    }
}
