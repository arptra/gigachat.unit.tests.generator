package com.gigachat.unit.tests.generator.util;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ArgsParserSingleFileTest {

    private final ArgsParser parser = new ArgsParser();

    @Test
    void parsesSingleFileOption() {
        String[] args = {
                "--mode", "scan",
                "--path", "./example-project",
                "--single-file"
        };

        AgentConfig config = parser.parse(args);

        assertTrue(config.isSingleFileMode());
    }
}
