package com.gigachat.unit.tests.generator.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptSnippetCatalogTest {

    @Test
    void shouldLoadGenerationPromptDirectivesFromResources() {
        PromptSnippetCatalog catalog = new PromptSnippetCatalog();

        assertTrue(catalog.commonGenerationRules().contains("Never access internal or private fields of the tested class."));
        assertTrue(catalog.commonGenerationRules().stream()
                .anyMatch(line -> line.contains("empty array") && line.contains("non-instantiable")));
        assertTrue(catalog.mockitoGenerationRules().stream()
                .anyMatch(line -> line.contains("constructor-created local objects")));
        assertTrue(catalog.constructorLocalGenerationRules().stream()
                .anyMatch(line -> line.contains("constructorLocalContexts")));
        assertTrue(catalog.stateModelRules().contains("The JSON contains the bounded state/action model in \"stateModel\"; follow it instead of inventing a new repair strategy."));
        assertTrue(catalog.commonReasoningRules().contains("Operate on generated tests only."));
        assertTrue(catalog.compilationReasoningRules().stream()
                .anyMatch(line -> line.contains("deterministic compile recipe")));
    }
}
