package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composer for static utility or helper classes.
 */
class StaticUtilityComposer extends AbstractComposer {

    @Override
    public Map<String, Object> compose(InstructionContext context) {
        LinkedHashMap<String, Object> instructions = baseInstructions(context);
        instructions.put("mockFramework", "None");
        addListIfNotEmpty(instructions, "shouldNotMock", context.mockPlan().shouldNotMock());
        includeVerificationPolicy(context, instructions);
        instructions.put("assertions", List.of(
                "Call the utility directly",
                "Use descriptive assertions",
                "Avoid mocking static helpers"
        ));
        return instructions;
    }
}
