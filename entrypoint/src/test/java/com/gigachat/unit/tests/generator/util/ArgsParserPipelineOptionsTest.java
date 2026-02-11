package com.gigachat.unit.tests.generator.util;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AgentMode;
import com.gigachat.unit.tests.generator.config.PipelineModuleConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArgsParserPipelineOptionsTest {

    private final ArgsParser parser = new ArgsParser();

    @Test
    void compileAndExecuteDisabledByDefault() {
        String[] args = {
                "--mode", "scan",
                "--path", "./example-project"
        };

        AgentConfig config = parser.parse(args);
        PipelineModuleConfig moduleConfig = config.getPipelineModuleConfig();

        assertFalse(moduleConfig.compileEnabled());
        assertFalse(moduleConfig.executeEnabled());
    }

    @Test
    void compileAndExecuteFlagsEnablePipelineSteps() {
        String[] args = {
                "--mode", "scan",
                "--path", "./example-project",
                "--compile",
                "--execute"
        };

        AgentConfig config = parser.parse(args);
        PipelineModuleConfig moduleConfig = config.getPipelineModuleConfig();

        assertTrue(moduleConfig.compileEnabled());
        assertTrue(moduleConfig.executeEnabled());
    }

    @Test
    void cleanFlagSwitchesAgentMode() {
        String[] args = {
                "--clean",
                "--path", "./example-project"
        };

        AgentConfig config = parser.parse(args);

        assertEquals(AgentMode.CLEAN, config.getMode());
    }

    @Test
    void diffModeUsesDefaultBranchesWhenNotProvided() {
        String[] args = {
                "--mode", "diffGenUnitTest",
                "--path", "./example-project"
        };

        AgentConfig config = parser.parse(args);

        assertEquals(AgentMode.DIFF_GEN_UNIT_TEST, config.getMode());
        assertEquals("master", config.getTargetBranch());
    }

    @Test
    void diffModeParsesBranchArguments() {
        String[] args = {
                "--mode", "diffGenUnitTest",
                "--path", "./example-project",
                "--source-branch", "feature/test",
                "--target-branch", "main"
        };

        AgentConfig config = parser.parse(args);

        assertEquals(AgentMode.DIFF_GEN_UNIT_TEST, config.getMode());
        assertEquals("feature/test", config.getSourceBranch());
        assertEquals("main", config.getTargetBranch());
    }

}
