package com.gigachat.unit.tests.generator.util;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArgsParserSingleFileTest {

    private final ArgsParser parser = new ArgsParser();

    @Test
    void parsesSingleFileOption() {
        String[] args = {
                "--mode", "scan",
                "--path", "./example-project",
                "--single-file", "./example-project/src/main/java/com/example/app/service/UserService.java"
        };

        AgentConfig config = parser.parse(args);

        assertTrue(config.getSingleFile().isPresent());
        Path expected = Path.of("./example-project/src/main/java/com/example/app/service/UserService.java")
                .toAbsolutePath()
                .normalize();
        assertEquals(expected, config.getSingleFile().get());
    }
}
