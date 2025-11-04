package com.gigachat.unit.tests.generator;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.pipeline.TestPipeline;
import com.gigachat.unit.tests.generator.scanner.JavaProjectScanner;
import com.gigachat.unit.tests.generator.util.ArgsParser;

import java.io.IOException;
import java.util.List;

public final class MainAgentEntry {

    private MainAgentEntry() {
    }

    public static void main(String[] args) {
        try {
            AgentConfig config = ArgsParser.parse(args);
            System.out.println("Launching TestRepairAgent with configuration:\n" + config.toYaml());

            JavaProjectScanner scanner = new JavaProjectScanner();
            List<TestClassInfo> scannedClasses = switch (config.mode()) {
                case SCAN, TEST, REPAIR -> scanner.scan(config);
                case MONITOR -> List.of();
            };

            List<TestClassInfo> pipelineClasses = filterTargetClass(config, scannedClasses);
            TestPipeline pipeline = new TestPipeline(config, pipelineClasses);
            pipeline.execute();
        } catch (IllegalArgumentException exception) {
            System.err.println("Invalid arguments: " + exception.getMessage());
            System.exit(1);
        } catch (IOException exception) {
            System.err.println("Failed to scan project: " + exception.getMessage());
            System.exit(2);
        }
    }

    private static List<TestClassInfo> filterTargetClass(AgentConfig config, List<TestClassInfo> scannedClasses) {
        String target = config.targetClass();
        if (target == null || target.isBlank()) {
            return scannedClasses;
        }

        String trimmed = target.trim();
        String simpleName = trimmed.contains(".")
                ? trimmed.substring(trimmed.lastIndexOf('.') + 1)
                : trimmed;
        String expectedTestName = simpleName.endsWith("Test") ? simpleName : simpleName + "Test";

        List<TestClassInfo> filtered = scannedClasses.stream()
                .filter(info -> matchesTarget(info.className(), expectedTestName))
                .toList();

        if (filtered.isEmpty()) {
            System.out.printf("Target class '%s' was not discovered during scanning.%n", trimmed);
        }

        return filtered;
    }

    private static boolean matchesTarget(String className, String expectedTestName) {
        if (className.equals(expectedTestName)) {
            return true;
        }
        return className.equalsIgnoreCase(expectedTestName);
    }
}
