package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composer for pure functions with no external dependencies.
 */
class PureFunctionComposer extends AbstractComposer {

    @Override
    public Map<String, Object> compose(InstructionContext context) {
        LinkedHashMap<String, Object> instructions = baseInstructions(context);
        instructions.put("mockFramework", "None");
        instructions.put("assertions", List.of(
                "Cover edge cases and happy path",
                "Prefer assertEquals/assertThat",
                "Avoid mocks unless the model suggests otherwise"
        ));
        includeVerificationPolicy(context, instructions);
        return instructions;
    }
}
