package com.gigachat.unit.tests.generator.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntryPointTaskApiTest {

    private final EntryPointTaskApi api = new EntryPointTaskApi();

    @Test
    void buildArgsIncludesCoverageFlagsAndGoals() {
        EntryPointTaskProperties properties = new EntryPointTaskProperties();
        properties.setMode("scan");
        properties.setPath("./example-project");
        properties.setTargetClasses(List.of("com.example.app.service.CoverageGoalWorkflowService"));
        properties.setCompile(true);
        properties.setExecute(true);
        properties.setCoverage(true);
        properties.setCoverageGoals("40,60");
        properties.setCoverageThreshold("80");

        List<String> args = api.buildArgs(properties);

        assertTrue(args.contains("--coverage"));
        assertEquals("40,60", valueOf(args, "--coverage-goals"));
        assertEquals("80", valueOf(args, "--coverage-threshold"));
    }

    private String valueOf(List<String> args, String key) {
        int index = args.indexOf(key);
        if (index < 0 || index + 1 >= args.size()) {
            return null;
        }
        return args.get(index + 1);
    }
}
