package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Composer for execution-analysis workflows.
 */
class ExecutionComposer extends AbstractComposer {

    @Override
    public Map<String, Object> compose(InstructionContext context) {
        LinkedHashMap<String, Object> instructions = baseInstructions(context);
        instructions.put("mode", "execution");
        includeVerificationPolicy(context, instructions);
        addStrategyNotes(instructions, context);
        instructions.put("executionReport", "Provide insights about runtime failures and suggest focused reruns.");
        return instructions;
    }
}
