package com.gigachat.unit.tests.generator.pipeline.helpers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void detectsExistingMethodFromRegistry(@TempDir Path tempDir) throws IOException {
        TestGenerationRegistry registry = new TestGenerationRegistry(tempDir);
        ExistingTestDetector detector = new ExistingTestDetector(registry);

        Path testFile = prepareTestFile(tempDir, "com.example", "SampleTest", true);
        TestClassInfo classInfo = new TestClassInfo("com.example.Sample", "SampleTest", testFile, List.of(), List.of());
        TestMethodInfo methodInfo = new TestMethodInfo("shouldDoThing()", "void", "{}");

        detector.recordSuccessfulTest(classInfo, methodInfo);

        assertTrue(detector.isTestMethodPresent(classInfo, methodInfo));
    }

    @Test
    void skipsWhenMethodNotRecorded(@TempDir Path tempDir) throws IOException {
        TestGenerationRegistry registry = new TestGenerationRegistry(tempDir);
        ExistingTestDetector detector = new ExistingTestDetector(registry);

        Path testFile = prepareTestFile(tempDir, "com.example", "SampleTest", true);
        TestClassInfo classInfo = new TestClassInfo("com.example.Sample", "SampleTest", testFile, List.of(), List.of());
        TestMethodInfo methodInfo = new TestMethodInfo("shouldDoThing()", "void", "{}");

        assertFalse(detector.isTestMethodPresent(classInfo, methodInfo));
    }

    @Test
    void persistsAcrossDetectorInstances(@TempDir Path tempDir) throws IOException {
        Path testFile = prepareTestFile(tempDir, "com.example", "SampleTest", true);
        TestGenerationRegistry registry = new TestGenerationRegistry(tempDir);
        ExistingTestDetector detector = new ExistingTestDetector(registry);
        TestClassInfo classInfo = new TestClassInfo("com.example.Sample", "SampleTest", testFile, List.of(), List.of());
        TestMethodInfo methodInfo = new TestMethodInfo("shouldDoThing()", "void", "{}");
        detector.recordSuccessfulTest(classInfo, methodInfo);

        ExistingTestDetector secondDetector = new ExistingTestDetector(new TestGenerationRegistry(tempDir));
        assertTrue(secondDetector.isTestMethodPresent(classInfo, methodInfo));
    }

    @Test
    void ignoresStaleRegistryEntryWhenSkeletonHasNoRealTests(@TempDir Path tempDir) throws IOException {
        TestGenerationRegistry registry = new TestGenerationRegistry(tempDir);
        ExistingTestDetector detector = new ExistingTestDetector(registry);

        Path testFile = prepareTestFile(tempDir, "com.example", "SampleTest", false);
        TestClassInfo classInfo = new TestClassInfo("com.example.Sample", "SampleTest", testFile, List.of(), List.of());
        TestMethodInfo methodInfo = new TestMethodInfo("shouldDoThing()", "void", "{}");

        detector.recordSuccessfulTest(classInfo, methodInfo);

        assertFalse(detector.isTestMethodPresent(classInfo, methodInfo));
    }

    private Path prepareTestFile(Path root, String pkg, String className, boolean withRealTest) throws IOException {
        Path file = root.resolve("src/test/java/" + pkg.replace('.', '/') + "/" + className + ".java");
        Files.createDirectories(file.getParent());
        String contents = withRealTest
                ? """
                package com.example;

                import org.junit.jupiter.api.Test;

                class SampleTest {
                    @Test
                    void placeholder() {
                    }
                }
                """
                : """
                package com.example;

                import org.junit.jupiter.api.Test;

                class SampleTest {
                }
                """;
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        return file;
    }
}
