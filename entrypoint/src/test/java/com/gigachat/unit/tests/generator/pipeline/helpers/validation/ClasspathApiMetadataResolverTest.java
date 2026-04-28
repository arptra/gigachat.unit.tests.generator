package com.gigachat.unit.tests.generator.pipeline.helpers.validation;

import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathApiMetadataResolverTest {

    @TempDir
    Path projectRoot;

    @Test
    void registersConstructorsAndMethodsForGradleClasspathType() throws Exception {
        compileMainClasses(List.of(new SourceFile("rt/Varchar2.java", """
                package rt;

                public class Varchar2 {
                    public Varchar2() {}
                    public Varchar2(String value) {}
                    public String getValue() {
                        return "";
                    }
                    public void assign(String value) {}
                }
                """)));
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        ClasspathApiMetadataResolver resolver = new ClasspathApiMetadataResolver(new PipelineLogger(projectRoot), registry);

        assertTrue(resolver.registerType(projectRoot, testClassFile(), "rt.Varchar2"));

        assertTrue(registry.constructorExists("Varchar2", 0));
        assertTrue(registry.constructorExists("Varchar2", 1));
        assertTrue(registry.methodExists("Varchar2", "getValue", 0));
        assertTrue(registry.methodExists("Varchar2", "assign", 1));
    }

    @Test
    void registersNestedClasspathTypeUsingSourceStyleName() throws Exception {
        compileMainClasses(List.of(new SourceFile("bd/Abonent.java", """
                package bd;

                public class Abonent {
                    public static class Ref {
                        public Ref() {}
                        public Ref createObject(String classId) {
                            return this;
                        }
                    }
                }
                """)));
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        ClasspathApiMetadataResolver resolver = new ClasspathApiMetadataResolver(new PipelineLogger(projectRoot), registry);

        assertTrue(resolver.registerType(projectRoot, testClassFile(), "bd.Abonent.Ref"));

        assertTrue(registry.constructorExists("Ref", 0));
        assertTrue(registry.methodExists("Ref", "createObject", 1));
    }

    private void compileMainClasses(List<SourceFile> sourceFiles) throws Exception {
        Path sourceRoot = projectRoot.resolve("compile-src");
        Path classesDir = projectRoot.resolve("build/classes/java/main");
        Files.createDirectories(classesDir);
        List<String> args = new java.util.ArrayList<>();
        args.add("-d");
        args.add(classesDir.toString());
        for (SourceFile sourceFile : sourceFiles) {
            Path path = sourceRoot.resolve(sourceFile.relativePath());
            Files.createDirectories(path.getParent());
            Files.writeString(path, sourceFile.source());
            args.add(path.toString());
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for this test");
        int result = compiler.run(null, null, null, args.toArray(String[]::new));
        assertTrue(result == 0, "helper class compilation failed");
    }

    private Path testClassFile() throws Exception {
        Path testClassFile = projectRoot.resolve("src/test/java/mtd/abonent/NEW_AUTOTest.java");
        Files.createDirectories(testClassFile.getParent());
        return testClassFile;
    }

    private record SourceFile(String relativePath, String source) {
    }
}
