package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import com.gigachat.unit.tests.generator.config.PromptMode;

/**
 * Selects the most appropriate instruction composer given the prompt mode and context.
 */
public class InstructionComposerFactory {

    public InstructionComposerStrategy select(PromptMode mode, InstructionContext context) {
        return switch (mode) {
            case GENERATION -> selectGenerationComposer(context);
            case REPAIR -> new RepairComposer();
            case EXECUTION -> new ExecutionComposer();
            case SYNTHESIS -> new GenericComposer();
        };
    }

    private InstructionComposerStrategy selectGenerationComposer(InstructionContext context) {
        if (context.isRepositoryLike()) {
            return new RepositoryComposer();
        }
        if (context.isUtilityLike()) {
            return new StaticUtilityComposer();
        }
        if (context.isPureFunction()) {
            return new PureFunctionComposer();
        }
        return new ServiceMethodComposer();
    }
}
