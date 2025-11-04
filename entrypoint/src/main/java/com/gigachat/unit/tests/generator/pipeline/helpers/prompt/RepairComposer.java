package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Composer for repair workflows.
 */
class RepairComposer extends AbstractComposer {

    @Override
    public Map<String, Object> compose(InstructionContext context) {
        LinkedHashMap<String, Object> instructions = baseInstructions(context);
        instructions.put("mode", "repair");
        includeVerificationPolicy(context, instructions);
        addStrategyNotes(instructions, context);
        instructions.put("errorContext", "Review compile/execute diagnostics and adjust assertions or mocks accordingly.");
        instructions.put("previousOutput", "No cached snippet available in this run.");
        return instructions;
    }
}
