package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Instructions tailored for typical service-style classes.
 */
class ServiceMethodComposer extends AbstractComposer {

    @Override
    public Map<String, Object> compose(InstructionContext context) {
        LinkedHashMap<String, Object> instructions = baseInstructions(context);
        instructions.put("mockFramework", "Mockito");
        addListIfNotEmpty(instructions, "shouldMock", context.mockPlan().shouldMock());
        addListIfNotEmpty(instructions, "shouldNotMock", context.mockPlan().shouldNotMock());
        addStrategyNotes(instructions, context);
        includeVerificationPolicy(context, instructions);
        instructions.put("focus", List.of("Arrange mocks", "Assert business rules", "Verify side effects"));
        return instructions;
    }
}
