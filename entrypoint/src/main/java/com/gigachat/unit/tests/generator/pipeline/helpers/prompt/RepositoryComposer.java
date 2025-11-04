package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composer for repository/DAO-like classes.
 */
class RepositoryComposer extends AbstractComposer {

    @Override
    public Map<String, Object> compose(InstructionContext context) {
        LinkedHashMap<String, Object> instructions = baseInstructions(context);
        instructions.put("mockFramework", "Mockito");
        addListIfNotEmpty(instructions, "shouldMock", context.mockPlan().shouldMock());
        addListIfNotEmpty(instructions, "shouldNotMock", context.mockPlan().shouldNotMock());
        addStrategyNotes(instructions, context);
        includeVerificationPolicy(context, instructions);
        instructions.put("dataAssertions", List.of(
                "Verify repository interactions",
                "Assert returned entities",
                "Cover error branches for missing data"
        ));
        return instructions;
    }
}
