package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fallback composer when no specific behaviour matches.
 */
class GenericComposer extends AbstractComposer {

    @Override
    public Map<String, Object> compose(InstructionContext context) {
        LinkedHashMap<String, Object> instructions = baseInstructions(context);
        if (context.hasMocks()) {
            instructions.put("mockFramework", "Mockito");
            addListIfNotEmpty(instructions, "shouldMock", context.mockPlan().shouldMock());
        } else {
            instructions.put("mockFramework", "None");
        }
        addListIfNotEmpty(instructions, "shouldNotMock", context.mockPlan().shouldNotMock());
        addStrategyNotes(instructions, context);
        includeVerificationPolicy(context, instructions);
        instructions.put("guidance", List.of("Focus on observable behaviour", "Keep tests deterministic"));
        return instructions;
    }
}
