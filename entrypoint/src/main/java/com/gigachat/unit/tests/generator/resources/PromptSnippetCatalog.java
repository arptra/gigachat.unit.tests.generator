package com.gigachat.unit.tests.generator.resources;

import java.util.List;
import java.util.Objects;

/**
 * Loads human-editable prompt directives from resources so prompt tuning does not require Java
 * code edits.
 */
public class PromptSnippetCatalog {

    private final List<String> commonGenerationRules;
    private final List<String> mockitoGenerationRules;
    private final List<String> realObjectGenerationRules;
    private final List<String> sourceSnippetRules;
    private final List<String> constructorLocalGenerationRules;
    private final List<String> repairRules;
    private final List<String> sutConstructionRules;
    private final List<String> stateModelRules;
    private final List<String> commonReasoningRules;
    private final List<String> compilationReasoningRules;
    private final List<String> executionReasoningRules;
    private final List<String> coverageReasoningRules;
    private final List<String> reasoningRecipeRules;

    public PromptSnippetCatalog() {
        this(new ResourceTextLoader());
    }

    public PromptSnippetCatalog(ResourceTextLoader loader) {
        Objects.requireNonNull(loader, "loader");
        this.commonGenerationRules = loader.readDirectiveLines("prompts/generation/common-rules.txt");
        this.mockitoGenerationRules = loader.readDirectiveLines("prompts/generation/mockito-rules.txt");
        this.realObjectGenerationRules = loader.readDirectiveLines("prompts/generation/real-object-rules.txt");
        this.sourceSnippetRules = loader.readDirectiveLines("prompts/generation/source-snippet-rules.txt");
        this.constructorLocalGenerationRules = loader.readDirectiveLines("prompts/generation/constructor-local-rules.txt");
        this.repairRules = loader.readDirectiveLines("prompts/generation/repair-rules.txt");
        this.sutConstructionRules = loader.readDirectiveLines("prompts/generation/sut-construction-rules.txt");
        this.stateModelRules = loader.readDirectiveLines("prompts/generation/state-model-rules.txt");
        this.commonReasoningRules = loader.readDirectiveLines("prompts/reasoning/common-rules.txt");
        this.compilationReasoningRules = loader.readDirectiveLines("prompts/reasoning/compilation-rules.txt");
        this.executionReasoningRules = loader.readDirectiveLines("prompts/reasoning/execution-rules.txt");
        this.coverageReasoningRules = loader.readDirectiveLines("prompts/reasoning/coverage-rules.txt");
        this.reasoningRecipeRules = loader.readDirectiveLines("prompts/reasoning/recipe-rules.txt");
    }

    public List<String> commonGenerationRules() {
        return commonGenerationRules;
    }

    public List<String> mockitoGenerationRules() {
        return mockitoGenerationRules;
    }

    public List<String> realObjectGenerationRules() {
        return realObjectGenerationRules;
    }

    public List<String> sourceSnippetRules() {
        return sourceSnippetRules;
    }

    public List<String> constructorLocalGenerationRules() {
        return constructorLocalGenerationRules;
    }

    public List<String> repairRules() {
        return repairRules;
    }

    public List<String> sutConstructionRules() {
        return sutConstructionRules;
    }

    public List<String> stateModelRules() {
        return stateModelRules;
    }

    public List<String> commonReasoningRules() {
        return commonReasoningRules;
    }

    public List<String> compilationReasoningRules() {
        return compilationReasoningRules;
    }

    public List<String> executionReasoningRules() {
        return executionReasoningRules;
    }

    public List<String> coverageReasoningRules() {
        return coverageReasoningRules;
    }

    public List<String> reasoningRecipeRules() {
        return reasoningRecipeRules;
    }
}
