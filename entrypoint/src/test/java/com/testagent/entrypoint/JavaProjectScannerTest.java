package com.testagent.entrypoint;

import com.testagent.entrypoint.config.AgentConfig;
import com.testagent.entrypoint.dto.TestClassInfo;
import com.testagent.entrypoint.scanner.JavaProjectScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void scansExampleProject() throws IOException {
        Path projectRoot = Path.of("..", "example-project").toAbsolutePath().normalize();
        assertTrue(Files.exists(projectRoot), "example project directory is missing");

        AgentConfig config = AgentConfig.builder()
                .mode(AgentConfig.Mode.SCAN)
                .projectPath(projectRoot)
                .includeClasses(List.of("com.example.DoesNotExist"))
                .scanWholeProject(true)
                .build();

        JavaProjectScanner scanner = new JavaProjectScanner();
        List<TestClassInfo> result = scanner.scan(config);

        assertEquals(6, result.size());
        result.forEach(info -> assertTrue(info.imports().contains("org.junit.jupiter.api.Test")));

        Map<String, TestClassInfo> index = result.stream()
                .collect(Collectors.toMap(TestClassInfo::className, info -> info));

        TestClassInfo application = index.get("ApplicationTest");
        assertNotNull(application, "Expected ApplicationTest info");
        assertEquals(Path.of("src", "test", "java", "com", "example", "app").toString(),
                relativize(projectRoot, application.targetPath()));

        TestClassInfo library = index.get("LibraryComponentTest");
        assertNotNull(library, "Expected LibraryComponentTest info");
        assertEquals(Path.of("src", "test", "java", "com", "example", "lib").toString(),
                relativize(projectRoot, library.targetPath()));

        Map<String, Integer> expectedMethodCounts = Map.of(
                "ApplicationTest", 3,
                "UserServiceTest", 3,
                "AuditTrailServiceTest", 3,
                "HiddenFeatureTest", 2,
                "MathUtilTest", 2,
                "LibraryComponentTest", 4
        );

        expectedMethodCounts.forEach((className, methodCount) -> {
            TestClassInfo info = index.get(className);
            assertNotNull(info, "Expected class not found: " + className);
            assertEquals(methodCount, info.methods().size(), "Method count mismatch for " + className);
        });

        List<String> expectedClassNames = expectedMethodCounts.keySet().stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        List<String> orderedClasses = result.stream()
                .map(TestClassInfo::className)
                .sorted(Comparator.naturalOrder())
                .toList();
        assertEquals(expectedClassNames, orderedClasses);
    }

    private String relativize(Path projectRoot, Path targetPath) {
        return projectRoot.relativize(targetPath).toString();
    }
}
