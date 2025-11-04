package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides convenience helpers for building instruction blocks.
 */
abstract class AbstractComposer implements InstructionComposerStrategy {

    protected LinkedHashMap<String, Object> baseInstructions(InstructionContext context) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("testFramework", "JUnit5");
        map.put("namingConvention", context.classInfo().getClassName() + "Test");
        return map;
    }

    protected void includeVerificationPolicy(InstructionContext context, Map<String, Object> instructions) {
        if (context.hasVerificationPolicy()) {
            instructions.put("verificationPolicy", context.verificationPolicy());
        }
    }

    protected void addListIfNotEmpty(Map<String, Object> map, String key, List<String> values) {
        if (values != null && !values.isEmpty()) {
            map.put(key, values);
        }
    }

    protected Map<String, String> describeStrategy(InstructionContext context) {
        MockPlan plan = context.mockPlan();
        LinkedHashMap<String, String> notes = new LinkedHashMap<>();
        MockStrategy strategy = plan.strategy();
        switch (strategy) {
            case MOCKITO -> notes.put("MOCKITO", "Use Mockito.mock() or @Mock to isolate collaborators.");
            case STATIC -> notes.put("MOCK_STATIC", "Wrap static collaborators with Mockito.mockStatic().");
            case STATIC_SKIP -> notes.put("STATIC_SKIP", "Do not mock platform static helpers; rely on real implementations.");
            case SPY -> notes.put("SPY", "Wrap real instances with Mockito.spy() when behaviour must be verified.");
            case CHAIN_PARTIAL -> notes.put("CHAIN_PARTIAL", "Mock the entry point of call chains and let nested invocations use defaults.");
            case LLM_ASSISTED -> notes.put("LLM_ASSISTED", "Defer complex mocking decisions to the language model.");
            case NONE -> {
                // no-op
            }
        }
        if (!plan.shouldMock().isEmpty()) {
            notes.put("FIELD_MOCK", "Inject mocks for: " + String.join(", ", plan.shouldMock()));
        }
        if (!plan.shouldNotMock().isEmpty()) {
            notes.put("REAL_OBJECTS", "Use real objects for: " + String.join(", ", plan.shouldNotMock()));
        }
        return notes;
    }

    protected void addStrategyNotes(Map<String, Object> instructions, InstructionContext context) {
        Map<String, String> strategyNotes = describeStrategy(context);
        if (!strategyNotes.isEmpty()) {
            instructions.put("mockStrategy", strategyNotes);
        }
    }
}
