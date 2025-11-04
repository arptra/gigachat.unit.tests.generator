package com.gigachat.unit.tests.generator;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.pipeline.TestPipeline;
import com.gigachat.unit.tests.generator.util.ArgsParser;

import java.io.IOException;
import java.util.List;

public class MainAgentEntry {
    private final ArgsParser argsParser;
    private final TestPipeline pipeline;

    public MainAgentEntry(ArgsParser argsParser, TestPipeline pipeline) {
        this.argsParser = argsParser;
        this.pipeline = pipeline;
    }

    public static void main(String[] args) {
        ArgsParser parser = new ArgsParser();
        TestPipeline pipeline = new TestPipeline();
        MainAgentEntry entry = new MainAgentEntry(parser, pipeline);
        entry.launch(args);
    }

    public void launch(String[] args) {
        try {
            AgentConfig config = argsParser.parse(args);
            System.out.println("Launching TestRepairAgent with configuration:\n" + config.toYaml());
            List<TestClassInfo> classes = pipeline.execute(config);
            List<TestClassInfo> filtered = filterTargetClass(config, classes);
            report(filtered);
        } catch (IllegalArgumentException exception) {
            System.err.println("Invalid arguments: " + exception.getMessage());
            printUsage();
        } catch (IOException exception) {
            System.err.println("Failed to scan project: " + exception.getMessage());
        }
    }

    private List<TestClassInfo> filterTargetClass(AgentConfig config, List<TestClassInfo> scannedClasses) {
        String target = config.getTargetClass();
        if (target == null || target.isBlank()) {
            return scannedClasses;
        }

        String trimmed = target.trim();
        String simpleName = trimmed.contains(".")
                ? trimmed.substring(trimmed.lastIndexOf('.') + 1)
                : trimmed;
        String expectedTestName = simpleName.endsWith("Test") ? simpleName : simpleName + "Test";

        List<TestClassInfo> filtered = scannedClasses.stream()
                .filter(info -> matchesTarget(info.getClassName(), expectedTestName))
                .toList();

        if (filtered.isEmpty()) {
            System.out.printf("Target class '%s' was not discovered during scanning.%n", trimmed);
        }

        return filtered;
    }

    private boolean matchesTarget(String className, String expectedTestName) {
        if (className.equals(expectedTestName)) {
            return true;
        }
        return className.equalsIgnoreCase(expectedTestName);
    }

    private void report(List<TestClassInfo> classes) {
        System.out.printf("Pipeline discovered %d candidate classes.%n", classes.size());
        for (TestClassInfo info : classes) {
            System.out.printf(" - %s -> %s (%d methods)%n",
                    info.getClassName(),
                    info.resolveTestFile(),
                    info.getMethods().size());
        }
    }

    private void printUsage() {
        System.out.println("Usage: --mode <scan|test|repair|monitor> [--path <projectDir>] [--project] [--class <fqcn>]" +
                " [--include-modules <names>] [--include-classes <names>] [--parallel]" +
                " [--gigachat-token <token>] [--gigachat-endpoint <uri>]");
        System.out.println("Defaults: mode=scan, path=current working directory, project=false, parallel=false");
    }
}
