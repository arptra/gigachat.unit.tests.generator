package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class ExistingTestDetectorTest {

    private final ExistingTestDetector detector = new ExistingTestDetector();

    @Test
    void detectsExistingMethod(@TempDir Path tempDir) throws IOException {
        Path testFile = prepareTestFile(tempDir, "com.example", "SampleTest",
                "package com.example;\n\npublic class SampleTest {\n    @org.junit.jupiter.api.Test\n    void shouldDoThing() {}\n}\n");
        TestClassInfo classInfo = new TestClassInfo("com.example.Sample", "SampleTest", testFile, List.of(), List.of());
        TestMethodInfo methodInfo = new TestMethodInfo("shouldDoThing()", "void", "{}");

        org.junit.jupiter.api.Assertions.assertTrue(detector.isTestMethodPresent(classInfo, methodInfo));
    }

    @Test
    void skipsWhenMethodAbsent(@TempDir Path tempDir) throws IOException {
        Path testFile = prepareTestFile(tempDir, "com.example", "SampleTest",
                "package com.example;\n\npublic class SampleTest {\n}\n");
        TestClassInfo classInfo = new TestClassInfo("com.example.Sample", "SampleTest", testFile, List.of(), List.of());
        TestMethodInfo methodInfo = new TestMethodInfo("shouldDoThing()", "void", "{}");

        org.junit.jupiter.api.Assertions.assertFalse(detector.isTestMethodPresent(classInfo, methodInfo));
    }

    private Path prepareTestFile(Path root, String pkg, String className, String content) throws IOException {
        Path file = root.resolve("src/test/java/" + pkg.replace('.', '/') + "/" + className + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
