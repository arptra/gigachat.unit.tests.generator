package com.gigachat.unit.tests.generator;

import com.gigachat.unit.tests.generator.cleaner.TestCleaner;
import com.gigachat.unit.tests.generator.compile.GradleCompilerInvoker;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentMode;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.pipeline.TestPipeline;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.execute.JUnitExecutionInvoker;
import com.gigachat.unit.tests.generator.util.ArgsParser;
import com.gigachat.unit.tests.generator.util.TargetClassMatcher;

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
            if (config.getMode() == AgentMode.CLEAN) {
                runCleaner(config);
                return;
            }
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
        List<String> targets = config.getTargetClasses();
        if (targets == null || targets.isEmpty()) {
            return scannedClasses;
        }

        List<TestClassInfo> filtered = scannedClasses.stream()
                .filter(info -> matchesTargets(info, targets))
                .toList();

        if (filtered.isEmpty()) {
            System.out.printf("Target classes %s were not discovered during scanning.%n", targets);
        }

        return filtered;
    }

    private boolean matchesTargets(TestClassInfo info, List<String> targets) {
        for (String target : targets) {
            if (TargetClassMatcher.matches(info, target)) {
                return true;
            }
        }
        return false;
    }

    private void report(List<TestClassInfo> classes) {
        System.out.printf("Pipeline discovered %d candidate classes.%n", classes.size());
        for (TestClassInfo info : classes) {
            System.out.printf(" - %s (test: %s) -> %s (%d methods)%n",
                    info.getClassName(),
                    info.getTestClassName(),
                    info.resolveTestFile(),
                    info.getMethods().size());
        }
    }

    private void printUsage() {
        System.out.println("Usage: --mode <scan|diffGenUnitTest|test|repair|monitor|clean> [--clean] [--path <projectDir>] [--project] [--class <fqcn>]" +
                " [--include-modules <names>] [--include-classes <names>] [--parallel]" +
                " [--single-file] [--compile] [--execute] [--source-branch <branch>] [--target-branch <branch>] [--proxy]" +
                " [--token <gigachatToken> | --cert <clientCert> --rootCert <rootCert> --key <privateKey>]" +
                " [--endpoint <uri>]");
        System.out.println("Defaults: mode=scan, path=current working directory, project=false, parallel=false");
    }

    private void runCleaner(AgentConfig config) throws IOException {
        PipelineLogger logger = new PipelineLogger(config.getProjectPath());
        TestCleaner cleaner = new TestCleaner(logger,
                new GradleCompilerInvoker(logger),
                new JUnitExecutionInvoker(logger));
        cleaner.clean(config);
    }
}
