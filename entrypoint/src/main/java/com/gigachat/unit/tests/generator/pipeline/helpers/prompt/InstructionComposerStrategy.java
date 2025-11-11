package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import java.util.Map;

/**
 * Contract for building instruction blocks tailored for a specific prompt scenario.
 */
public interface InstructionComposerStrategy {

    Map<String, Object> compose(InstructionContext context);
}
