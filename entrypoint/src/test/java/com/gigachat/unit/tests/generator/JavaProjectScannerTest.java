package com.gigachat.unit.tests.generator;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentConfigBuilder;
import com.gigachat.unit.tests.generator.config.AgentMode;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.scanner.JavaProjectScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        assertEquals("SampleService", info.getClassName());
        assertEquals("SampleServiceTest", info.getTestClassName());
        Path expectedTarget = workingDirectory.resolve(Path.of("src", "test", "java", "com", "example", "demo", "SampleServiceTest.java"))
                .toAbsolutePath().normalize();
        assertEquals(expectedTarget, info.getTargetPath());
        assertTrue(info.getImports().isEmpty());
        assertFalse(info.getMethods().isEmpty());
        TestMethodInfo method = info.getMethods().get(0);
        assertEquals("public String findAll()", method.getSignature());
        assertEquals("String", method.getReturnType());
        assertTrue(method.getBody().contains("return \"ok\";"));
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
        Map<String, TestClassInfo> index = result.stream()
                .collect(Collectors.toMap(TestClassInfo::getTestClassName, info -> info));

        TestClassInfo application = index.get("ApplicationTest");
        assertNotNull(application, "Expected ApplicationTest info");
        assertTrue(application.getImports().contains("import com.example.app.service.UserService;"));
        assertEquals(Path.of("src", "test", "java", "com", "example", "app", "ApplicationTest.java").toString(),
                relativize(projectRoot, application.getTargetPath()));

        TestClassInfo library = index.get("LibraryComponentTest");
        assertNotNull(library, "Expected LibraryComponentTest info");
        assertTrue(library.getImports().contains("import java.util.HashMap;"));
        assertEquals(Path.of("src", "test", "java", "com", "example", "lib", "LibraryComponentTest.java").toString(),
                relativize(projectRoot, library.getTargetPath()));

        Map<String, Integer> methodCounts = result.stream()
                .collect(Collectors.toMap(TestClassInfo::getClassName, info -> info.getMethods().size()));

        methodCounts.forEach((className, count) -> assertTrue(count > 0, "Expected methods for " + className));

        List<String> orderedClasses = result.stream()
                .map(TestClassInfo::getTestClassName)
                .sorted(Comparator.naturalOrder())
                .toList();
        assertFalse(orderedClasses.isEmpty());
    }

    @Test
    void respectsTargetClassesFilter() throws IOException {
        Path projectRoot = Path.of("..", "example-project").toAbsolutePath().normalize();
        assertTrue(Files.exists(projectRoot), "example project directory is missing");

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(projectRoot)
                .scanWholeProject(true)
                .targetClasses(List.of(
                        "com.example.app.service.UserService",
                        "com.example.lib.LibraryComponent"
                ))
                .build();

        JavaProjectScanner scanner = new JavaProjectScanner();
        List<TestClassInfo> result = scanner.scan(config);

        assertEquals(2, result.size());
        Set<String> discovered = result.stream()
                .map(TestClassInfo::getClassName)
                .collect(Collectors.toSet());
        assertTrue(discovered.contains("UserService"));
        assertTrue(discovered.contains("LibraryComponent"));
    }

    @Test
    void supportsModuleWildcardTargets() throws IOException {
        Path moduleRoot = workingDirectory.resolve("mtd");
        Path sourceFolder = moduleRoot.resolve(Path.of("src", "main", "java", "com", "example", "mod"));
        Files.createDirectories(sourceFolder);
        Files.writeString(sourceFolder.resolve("Alpha.java"), """
                package com.example.mod;

                public class Alpha {
                    public void run() {}
                }
                """);

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.SCAN)
                .projectPath(workingDirectory)
                .targetClasses(List.of("mtd.*"))
                .build();

        JavaProjectScanner scanner = new JavaProjectScanner();
        List<TestClassInfo> result = scanner.scan(config);

        assertEquals(1, result.size());
        TestClassInfo info = result.get(0);
        assertEquals("Alpha", info.getClassName());
        assertTrue(info.getTargetPath().toString().contains("mtd"));
    }


    @Test
    void diffModeScansOnlyChangedJavaFilesBetweenBranches() throws IOException {
        Path projectRoot = workingDirectory.resolve("repo");
        Path sourceFolder = projectRoot.resolve(Path.of("src", "main", "java", "com", "example", "demo"));
        Files.createDirectories(sourceFolder);

        Path alpha = sourceFolder.resolve("Alpha.java");
        Path beta = sourceFolder.resolve("Beta.java");
        Files.writeString(alpha, """
                package com.example.demo;

                public class Alpha {
                    public String value() {
                        return "A";
                    }
                }
                """);
        Files.writeString(beta, """
                package com.example.demo;

                public class Beta {
                    public String value() {
                        return "B";
                    }
                }
                """);

        run(projectRoot, "git init");
        run(projectRoot, "git config user.email test@example.com");
        run(projectRoot, "git config user.name test");
        run(projectRoot, "git add .");
        run(projectRoot, "git commit -m init");
        run(projectRoot, "git checkout -b feature/diff");
        Files.writeString(alpha, """
                package com.example.demo;

                public class Alpha {
                    public String value() {
                        return "AA";
                    }
                }
                """);
        run(projectRoot, "git add .");
        run(projectRoot, "git commit -m update-alpha");

        AgentConfig config = new AgentConfigBuilder()
                .mode(AgentMode.DIFF_GEN_UNIT_TEST)
                .projectPath(projectRoot)
                .sourceBranch("feature/diff")
                .targetBranch("master")
                .build();

        JavaProjectScanner scanner = new JavaProjectScanner();
        List<TestClassInfo> result = scanner.scan(config);

        assertEquals(1, result.size());
        assertEquals("Alpha", result.get(0).getClassName());
    }

    private void run(Path directory, String command) throws IOException {
        Process process = new ProcessBuilder("bash", "-lc", command)
                .directory(directory.toFile())
                .start();
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("Command failed: " + command + "\nstdout: " + stdout + "\nstderr: " + stderr);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running command: " + command, ex);
        }
    }

    private String relativize(Path projectRoot, Path targetPath) {
        return projectRoot.relativize(targetPath).toString();
    }
}
