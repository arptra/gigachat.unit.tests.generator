package com.gigachat.unit.tests.generator;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.config.AgentMode;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.scanner.JavaProjectScanner;
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

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(workingDirectory)
                .build();

        JavaProjectScanner scanner = new JavaProjectScanner();
        List<TestClassInfo> result = scanner.scan(config);

        assertEquals(1, result.size());
        TestClassInfo info = result.get(0);
        assertEquals("SampleServiceTest", info.getClassName());
        Path expectedTarget = workingDirectory.resolve(Path.of("src", "test", "java", "com", "example", "demo"))
                .toAbsolutePath().normalize();
        assertEquals(expectedTarget, info.getTargetPath());
        assertTrue(info.getImports().contains("org.junit.jupiter.api.Test"));
        assertFalse(info.getMethods().isEmpty());
        assertEquals("public void findAllTest()", info.getMethods().get(0).getSignature());
    }

    @Test
    void scansExampleProject() throws IOException {
        Path projectRoot = Path.of("..", "example-project").toAbsolutePath().normalize();
        assertTrue(Files.exists(projectRoot), "example project directory is missing");

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(projectRoot)
                .includeClasses(List.of("com.example.DoesNotExist"))
                .scanWholeProject(true)
                .build();

        JavaProjectScanner scanner = new JavaProjectScanner();
        List<TestClassInfo> result = scanner.scan(config);

        assertFalse(result.isEmpty(), "Scanner did not find classes in example project");
        result.forEach(info -> assertTrue(info.getImports().contains("org.junit.jupiter.api.Test")));

        Map<String, TestClassInfo> index = result.stream()
                .collect(Collectors.toMap(TestClassInfo::getClassName, info -> info));

        TestClassInfo application = index.get("ApplicationTest");
        assertNotNull(application, "Expected ApplicationTest info");
        assertEquals(Path.of("src", "test", "java", "com", "example", "app").toString(),
                relativize(projectRoot, application.getTargetPath()));

        TestClassInfo library = index.get("LibraryComponentTest");
        assertNotNull(library, "Expected LibraryComponentTest info");
        assertEquals(Path.of("src", "test", "java", "com", "example", "lib").toString(),
                relativize(projectRoot, library.getTargetPath()));

        Map<String, Integer> methodCounts = result.stream()
                .collect(Collectors.toMap(TestClassInfo::getClassName, info -> info.getMethods().size()));

        methodCounts.forEach((className, count) -> assertTrue(count > 0, "Expected methods for " + className));

        List<String> orderedClasses = result.stream()
                .map(TestClassInfo::getClassName)
                .sorted(Comparator.naturalOrder())
                .toList();
        assertFalse(orderedClasses.isEmpty());
    }

    private String relativize(Path projectRoot, Path targetPath) {
        return projectRoot.relativize(targetPath).toString();
    }
}
