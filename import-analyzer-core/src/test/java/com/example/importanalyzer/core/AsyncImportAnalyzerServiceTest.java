package com.example.importanalyzer.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AsyncImportAnalyzerServiceTest {

    @Test
    void returnsAddActionWhenSingleCandidateExists() throws Exception {
        Path project = Files.createTempDirectory("import-analyzer-service");
        Path src = project.resolve("src/main/java");
        Path util = src.resolve("demo/util");
        Files.createDirectories(util);
        Path helper = util.resolve("Helper.java");
        Files.writeString(helper, "package demo.util; public class Helper {}");
        Path usageDir = src.resolve("demo");
        Files.createDirectories(usageDir);
        Path usage = usageDir.resolve("UsesHelper.java");
        Files.writeString(usage, "package demo; public class UsesHelper { Helper h; }");

        ImportAnalyzerConfig config = new ImportAnalyzerBuilder()
                .projectRoot(project)
                .sourceRoot(src.getParent())
                .threads(2)
                .includeDependencies(false)
                .buildConfig();

        AsyncImportAnalyzerService service = new AsyncImportAnalyzerService(config);
        ScanResult result;
        do {
            result = service.scan(usage).join();
            if (result.inProgress()) {
                Thread.sleep(10);
            }
        } while (result.inProgress());

        System.out.println("Scan result: " + result);

        assertEquals(ImportAction.ADD, result.action());
        assertTrue(result.candidates().contains("demo.util.Helper"));
        assertEquals(usage, result.file());
        assertEquals(ImportSource.LOCAL, result.source());
    }

    @Test
    void returnsDeleteForUnresolvedImport() throws IOException, InterruptedException {
        Path project = Files.createTempDirectory("import-analyzer-service2");
        Path src = project.resolve("src/main/java/sample");
        Files.createDirectories(src);
        Path usage = src.resolve("Broken.java");
        Files.writeString(usage, "package sample; import no.such.Type; public class Broken {}");

        ImportAnalyzerConfig config = new ImportAnalyzerBuilder()
                .projectRoot(project)
                .sourceRoot(src.getParent())
                .threads(2)
                .includeDependencies(false)
                .buildConfig();

        AsyncImportAnalyzerService service = new AsyncImportAnalyzerService(config);
        ScanResult result;
        do {
            result = service.scan(usage).join();
            if (result.inProgress()) {
                Thread.sleep(10);
            }
        } while (result.inProgress());

        assertEquals(ImportAction.DELETE, result.action());
        assertEquals(usage, result.file());
        assertTrue(result.line() >= 1);
        assertEquals(ImportSource.UNKNOWN, result.source());
    }

    @Test
    void reportsProgressWhileScanRunning() throws Exception {
        Path project = Files.createTempDirectory("import-analyzer-progress");
        Path src = project.resolve("src/main/java/demo");
        Files.createDirectories(src);

        int fileCount = 15;
        Path target = null;
        for (int i = 0; i < fileCount; i++) {
            Path file = src.resolve("C" + i + ".java");
            Files.writeString(file, "package demo; public class C" + i + " {}\n");
            if (target == null) {
                target = file;
            }
        }

        ImportAnalyzerConfig config = new ImportAnalyzerBuilder()
                .projectRoot(project)
                .sourceRoot(project.resolve("src/main/java"))
                .threads(1)
                .includeDependencies(false)
                .buildConfig();

        AsyncImportAnalyzerService service = new AsyncImportAnalyzerService(config);

        ScanResult early;
        do {
            early = service.scan(target).join();
            if (early.totalFiles() == 0) {
                Thread.sleep(10);
            }
        } while (early.totalFiles() == 0);

        assertEquals(fileCount, early.totalFiles());
        if (early.inProgress()) {
            assertTrue(early.scannedFiles() < fileCount);
        } else {
            assertEquals(fileCount, early.scannedFiles());
        }

        ScanResult finalResult;
        do {
            Thread.sleep(10);
            finalResult = service.scan(target).join();
        } while (finalResult.inProgress());

        assertFalse(finalResult.inProgress());
        assertEquals(fileCount, finalResult.totalFiles());
        assertEquals(fileCount, finalResult.scannedFiles());
        assertEquals(ImportAction.UNKNOWN, finalResult.action());
        assertEquals(target, finalResult.file());
        assertEquals(ImportSource.UNKNOWN, finalResult.source());
    }

    @Test
    void returnsSelectWhenMultipleCandidatesExist() throws Exception {
        Path project = Files.createTempDirectory("import-analyzer-select");
        Path src = project.resolve("src/main/java");
        Path pkgOne = src.resolve("demo/one");
        Path pkgTwo = src.resolve("demo/two");
        Files.createDirectories(pkgOne);
        Files.createDirectories(pkgTwo);

        Files.writeString(pkgOne.resolve("Helper.java"), "package demo.one; public class Helper {}\n");
        Files.writeString(pkgTwo.resolve("Helper.java"), "package demo.two; public class Helper {}\n");

        Path usageDir = src.resolve("demo");
        Files.createDirectories(usageDir);
        Path usage = usageDir.resolve("UsesHelper.java");
        Files.writeString(usage, "package demo; public class UsesHelper { Helper helper; }\n");

        ImportAnalyzerConfig config = new ImportAnalyzerBuilder()
                .projectRoot(project)
                .sourceRoot(src)
                .threads(2)
                .includeDependencies(false)
                .buildConfig();

        AsyncImportAnalyzerService service = new AsyncImportAnalyzerService(config);

        ScanResult result;
        do {
            result = service.scan(usage).join();
            if (result.inProgress()) {
                Thread.sleep(10);
            }
        } while (result.inProgress());

        assertEquals(ImportAction.SELECT, result.action());
        assertEquals(usage, result.file());
        assertTrue(result.candidates().contains("demo.one.Helper"));
        assertTrue(result.candidates().contains("demo.two.Helper"));
        assertEquals(ImportSource.UNKNOWN, result.source());
    }
}
