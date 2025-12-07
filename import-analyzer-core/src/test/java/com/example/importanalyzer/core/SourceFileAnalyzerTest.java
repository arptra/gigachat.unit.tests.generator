package com.example.importanalyzer.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SourceFileAnalyzerTest {
    @Test
    void parsesBasicFile() throws IOException {
        Path temp = Files.createTempFile("Sample", ".java");
        Files.writeString(temp, "package demo; import java.util.List; public class Sample { List<String> names; }");
        SourceFileResult result = SourceFileAnalyzer.analyze(temp);
        assertEquals("demo", result.packageName());
        assertTrue(result.imports().containsKey("java.util.List"));
        assertTrue(result.usedTypes().contains("List"));
        Files.deleteIfExists(temp);
    }

    @Test
    void capturesStaticMembersAndIdentifiers() throws IOException {
        Path temp = Files.createTempFile("StaticSample", ".java");
        Files.writeString(temp, """
                package demo;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                import static org.junit.jupiter.api.Assertions.*;
                import java.nio.file.Path;
                public class SampleStatic { void test() { assertTrue(true); Path.of("/tmp"); } }
                """.stripIndent());
        SourceFileResult result = SourceFileAnalyzer.analyze(temp);
        assertFalse(result.staticImports().isEmpty());
        assertFalse(result.staticWildcardImports().isEmpty());
        assertTrue(result.usedIdentifiers().contains("assertTrue"));
        assertTrue(result.usedTypes().contains("Path"));
        Files.deleteIfExists(temp);
    }

    @Test
    void tracksAnnotationUsage() throws IOException {
        Path temp = Files.createTempFile("AnnotationSample", ".java");
        Files.writeString(temp, """
                package demo;
                import org.junit.jupiter.api.Test;
                public class SampleAnnotation { @Test void go() {} }
                """.stripIndent());

        SourceFileResult result = SourceFileAnalyzer.analyze(temp);
        assertTrue(result.usedTypes().contains("Test"));
        assertTrue(result.usedIdentifiers().contains("Test"));
        Files.deleteIfExists(temp);
    }
}
