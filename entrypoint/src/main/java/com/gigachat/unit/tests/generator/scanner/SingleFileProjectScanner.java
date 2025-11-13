package com.gigachat.unit.tests.generator.scanner;

import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.github.javaparser.JavaParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Scanner implementation that focuses on a single Java source file.
 * <p>
 * Unlike {@link JavaProjectScanner}, this scanner does not traverse the entire project tree.
 * It simply parses the provided file and feeds discovered classes into the pipeline.
 */
public class SingleFileProjectScanner extends JavaProjectScanner {

    public SingleFileProjectScanner() {
        super();
    }

    public SingleFileProjectScanner(MethodSignatureRegistry registry) {
        super(registry);
    }

    public SingleFileProjectScanner(JavaParser parser, MethodSignatureRegistry registry) {
        super(parser, registry);
    }

    /**
     * Scans the provided Java file and returns discovered class metadata.
     *
     * @param javaFile path to the Java source file that should be analysed
     * @param config   current agent configuration
     * @return a list of {@link TestClassInfo} discovered in the file
     * @throws IOException if the file cannot be read or parsed
     */
    public List<TestClassInfo> scan(Path javaFile, AgentConfig config) throws IOException {
        Objects.requireNonNull(javaFile, "javaFile");
        Objects.requireNonNull(config, "config");

        Path normalisedFile = javaFile.toAbsolutePath().normalize();
        if (!Files.exists(normalisedFile) || !Files.isRegularFile(normalisedFile)) {
            throw new IOException("Java file does not exist: " + javaFile);
        }

        Path moduleRoot = determineModuleRoot(normalisedFile, config.getProjectPath());
        List<TestClassInfo> collector = new ArrayList<>();
        parseJavaFile(normalisedFile, moduleRoot, config, collector);
        return List.copyOf(collector);
    }

    private Path determineModuleRoot(Path javaFile, Path projectRoot) {
        Path normalisedProjectRoot = Objects.requireNonNull(projectRoot, "projectRoot")
                .toAbsolutePath()
                .normalize();
        if (!javaFile.startsWith(normalisedProjectRoot)) {
            throw new IllegalArgumentException("File is outside of configured project root: " + javaFile);
        }

        Path relative = normalisedProjectRoot.relativize(javaFile);
        for (int i = 0; i < relative.getNameCount(); i++) {
            if ("src".equals(relative.getName(i).toString())) {
                if (i == 0) {
                    return normalisedProjectRoot;
                }
                return normalisedProjectRoot.resolve(relative.subpath(0, i));
            }
        }
        Path parent = javaFile.getParent();
        return parent == null ? normalisedProjectRoot : parent;
    }
}
