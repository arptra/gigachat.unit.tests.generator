package com.gigachat.unit.tests.generator.api;

import com.gigachat.unit.tests.generator.MainAgentEntry;
import com.gigachat.unit.tests.generator.pipeline.TestPipeline;
import com.gigachat.unit.tests.generator.util.ArgsParser;

import java.util.ArrayList;
import java.util.List;

public class EntryPointTaskApi {

    public void execute(EntryPointTaskProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("properties is required");
        }
        List<String> args = buildArgs(properties);
        MainAgentEntry entry = new MainAgentEntry(new ArgsParser(), new TestPipeline());
        entry.launch(args.toArray(String[]::new));
    }

    List<String> buildArgs(EntryPointTaskProperties properties) {
        List<String> args = new ArrayList<>();
        addArg(args, "mode", properties.getMode());
        addArg(args, "path", properties.getPath());
        addJoinedArg(args, "include-modules", properties.getIncludeModules());
        addJoinedArg(args, "include-classes", properties.getIncludeClasses());
        addJoinedArg(args, "class", properties.getTargetClasses());

        if (properties.isSingleFile()) {
            args.add("--single-file");
        }
        if (properties.isParallel()) {
            args.add("--parallel");
            args.add("true");
        }
        if (properties.isProject()) {
            args.add("--project");
            args.add("true");
        }
        if (properties.isCompile()) {
            args.add("--compile");
        }
        if (properties.isExecute()) {
            args.add("--execute");
        }
        if (properties.isCoverage()) {
            args.add("--coverage");
        }
        addArg(args, "coverage-goals", properties.getCoverageGoals());
        addArg(args, "coverage-threshold", properties.getCoverageThreshold());

        addArg(args, "source-branch", properties.getSourceBranch());
        addArg(args, "target-branch", properties.getTargetBranch());

        addArg(args, "token", properties.getToken());
        addArg(args, "endpoint", properties.getEndpoint());
        addArg(args, "auth-url", properties.getAuthUrl());
        addArg(args, "cert", properties.getCert());
        addArg(args, "rootCert", properties.getRootCert());
        addArg(args, "key", properties.getKey());

        if (properties.isProxy()) {
            args.add("--proxy");
            args.add("true");
        }

        if (properties.isSsl()) {
            args.add("--ssl");
            args.add("true");
        }
        addArg(args, "model", properties.getModel());
        return args;
    }

    private void addArg(List<String> args, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        args.add("--" + key);
        args.add(value);
    }

    private void addJoinedArg(List<String> args, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        String joined = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        if (!joined.isBlank()) {
            addArg(args, key, joined);
        }
    }
}
