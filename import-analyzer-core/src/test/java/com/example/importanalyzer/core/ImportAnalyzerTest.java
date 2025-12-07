package com.example.importanalyzer.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImportAnalyzerTest {
    @Test
    void detectsMissingAndUnusedImports() throws Exception {
        Path root = Files.createTempDirectory("analyzer");
        Path src = root.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Path file = src.resolve("Sample.java");
        Files.writeString(file, "package demo; import java.util.List; import java.util.Set; public class Sample { String name; List<String> names; Map map; }");

        ImportAnalyzer analyzer = new ImportAnalyzerBuilder()
                .projectRoot(root)
                .sourceRoot(root.resolve("src/main/java"))
                .includeDependencies(false)
                .threads(2)
                .cacheEnabled(false)
                .build();
        List<ImportIssue> issues = analyzer.analyze();
        assertFalse(issues.isEmpty());
    }

    @Test
    void respectsImportsUsedInAnnotations() throws Exception {
        Path root = Files.createTempDirectory("analyzerAnnotations");
        Path src = root.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Path file = src.resolve("SampleTest.java");
        Files.writeString(file, """
                package demo;
                import org.junit.jupiter.api.Test;
                public class SampleTest { @Test void ok() {} }
                """.stripIndent());

        ImportAnalyzer analyzer = new ImportAnalyzerBuilder()
                .projectRoot(root)
                .sourceRoot(root.resolve("src/main/java"))
                .includeDependencies(true)
                .threads(2)
                .cacheEnabled(false)
                .build();
        List<ImportIssue> issues = analyzer.analyze();
        assertTrue(issues.stream().noneMatch(issue -> issue instanceof UnusedImportIssue), "Annotation imports should be marked as used");
    }

    @Test
    void reportsMissingPackageWithoutNoisySuggestions() throws Exception {
        Path root = Files.createTempDirectory("missingPackage");
        Path src = root.resolve("src/main/java");
        Files.createDirectories(src.resolve("demo"));
        Files.createDirectories(src.resolve("other"));

        Path other = src.resolve("other/UnknownType.java");
        Files.writeString(other, "package other; public class UnknownType {}");

        Path file = src.resolve("demo/Use.java");
        Files.writeString(file, """
                package demo;
                import demo.missing.UnknownType;
                public class Use { UnknownType field; }
                """.stripIndent());

        ImportAnalyzer analyzer = new ImportAnalyzerBuilder()
                .projectRoot(root)
                .sourceRoot(src)
                .includeDependencies(false)
                .threads(2)
                .cacheEnabled(false)
                .build();

        List<ImportIssue> issues = analyzer.analyze();
        assertEquals(1, issues.size());
        ImportIssue issue = issues.get(0);
        assertTrue(issue instanceof UnresolvedImportIssue);
        assertTrue(issue.message().contains("Package demo.missing not found"));
    }

    @Test
    void favorsCandidatesProvidingCalledStaticMethods() throws Exception {
        Path root = Files.createTempDirectory("staticPref");
        Path src = root.resolve("src/main/java");
        Files.createDirectories(src.resolve("a"));
        Files.createDirectories(src.resolve("b"));
        Files.createDirectories(src.resolve("sample"));

        Path aAssertions = src.resolve("a/Assertions.java");
        Files.writeString(aAssertions, "package a; public class Assertions { public static void ok() {} }");

        Path bAssertions = src.resolve("b/Assertions.java");
        Files.writeString(bAssertions, "package b; public class Assertions { public static void assertTrue(boolean val) {} }");

        Path file = src.resolve("sample/Use.java");
        Files.writeString(file, """
                package sample;
                public class Use { void test() { Assertions.assertTrue(true); } }
                """.stripIndent());

        ImportAnalyzer analyzer = new ImportAnalyzerBuilder()
                .projectRoot(root)
                .sourceRoot(src)
                .includeDependencies(false)
                .threads(2)
                .cacheEnabled(false)
                .build();

        List<ImportIssue> issues = analyzer.analyze();
        assertTrue(issues.stream().anyMatch(issue -> issue instanceof MissingImportIssue
                && issue.message().contains("b.Assertions")), "Should prefer Assertions class that defines assertTrue");
    }

    @Test
    void limitsAmbiguousSuggestionsWhenMembersUnavailable() throws Exception {
        Path root = Files.createTempDirectory("assertionsLimit");
        Path src = root.resolve("src/main/java");
        Files.createDirectories(src);

        for (int i = 1; i <= 6; i++) {
            Path pkg = src.resolve("p" + i);
            Files.createDirectories(pkg);
            Files.writeString(pkg.resolve("Assertions.java"), "package p" + i + "; public class Assertions {}");
        }

        Path file = src.resolve("Use.java");
        Files.writeString(file, """
                public class Use { void test() { Assertions.assertTrue(true); } }
                """.stripIndent());

        ImportAnalyzer analyzer = new ImportAnalyzerBuilder()
                .projectRoot(root)
                .sourceRoot(src)
                .includeDependencies(false)
                .threads(2)
                .cacheEnabled(false)
                .build();

        List<ImportIssue> issues = analyzer.analyze();
        ImportIssue issue = issues.stream().filter(i -> i instanceof AmbiguousImportIssue).findFirst().orElseThrow();
        String msg = issue.message();
        String list = msg.substring(msg.indexOf(":") + 1).trim();
        String[] suggestions = list.split(", ");
        assertTrue(suggestions.length <= 5, "Suggestions should be limited to 5 to remain readable");
    }

    @Test
    void indexesSiblingModuleSourcesSoProjectImportsResolve() throws Exception {
        Path root = Files.createTempDirectory("multi-root");
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name=\"sample\"");

        Path libSrc = root.resolve("lib/src/main/java/sample/lib");
        Files.createDirectories(libSrc);
        Files.writeString(libSrc.resolve("Helper.java"), "package sample.lib; public class Helper {}");

        Path appSrc = root.resolve("app/src/main/java/sample/app");
        Files.createDirectories(appSrc);
        Files.writeString(appSrc.resolve("Use.java"), """
                package sample.app;
                import sample.lib.Helper;
                public class Use { Helper h = new Helper(); }
                """.stripIndent());

        ImportAnalyzer analyzer = new ImportAnalyzerBuilder()
                .projectRoot(appSrc.getParent().getParent())
                .sourceRoot(appSrc.getParent())
                .includeDependencies(true)
                .cacheEnabled(false)
                .build();

        List<ImportIssue> issues = analyzer.analyze();

        assertTrue(issues.stream().noneMatch(i -> i instanceof UnresolvedImportIssue),
                "Sibling module declarations should satisfy dependent imports");
    }
}
