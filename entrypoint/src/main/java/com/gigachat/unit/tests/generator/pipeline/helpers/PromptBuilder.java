package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.PromptConfig;
import com.gigachat.unit.tests.generator.config.PromptMode;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockTarget;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze.AnalysisSummary;
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.InstructionComposerFactory;
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.InstructionComposerStrategy;
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.InstructionContext;
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.PromptJsonRenderer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Combines different sources of information into a final prompt for LLM invocation.
 */
public class PromptBuilder {
    private final InstructionComposerFactory composerFactory;
    private final PromptJsonRenderer jsonRenderer;

    public PromptBuilder() {
        this(new InstructionComposerFactory(), new PromptJsonRenderer());
    }

    public PromptBuilder(InstructionComposerFactory composerFactory, PromptJsonRenderer jsonRenderer) {
        this.composerFactory = Objects.requireNonNull(composerFactory, "composerFactory");
        this.jsonRenderer = Objects.requireNonNull(jsonRenderer, "jsonRenderer");
    }

    public String build(AgentConfig config,
                        TestClassInfo classInfo,
                        TestMethodInfo methodInfo,
                        String skeletonJson,
                        AnalysisSummary summary) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(classInfo, "classInfo");
        Objects.requireNonNull(methodInfo, "methodInfo");
        Objects.requireNonNull(summary, "summary");
        PromptConfig promptConfig = config.getPromptConfig();
        InstructionContext context = new InstructionContext(classInfo,
                methodInfo,
                summary.methodAnalysis(),
                summary.mockPlan(),
                summary.verificationPolicy(),
                promptConfig);
        InstructionComposerStrategy strategy = composerFactory.select(promptConfig.mode(), context);
        Map<String, Object> instructions = strategy.compose(context);

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        String goal = resolveGoal(promptConfig.mode(), context);
        if (!goal.isBlank()) {
            root.put("goal", goal);
        }
        if (!instructions.isEmpty()) {
            root.put("instructions", instructions);
        }
        if (skeletonJson != null && !skeletonJson.isBlank()) {
            root.put("skeleton", PromptJsonRenderer.raw(skeletonJson));
        }
        String analysisJson = summary.jsonContext();
        if (analysisJson != null && !analysisJson.isBlank()) {
            root.put("analysis", PromptJsonRenderer.raw(analysisJson));
        }
        if (summary.mockPlan() != null) {
            Map<String, Object> planBlock = buildMockPlan(summary.mockPlan());
            if (!planBlock.isEmpty()) {
                root.put("mockPlan", planBlock);
            }
        }
        List<String> hints = determineHints(context);
        if (!hints.isEmpty()) {
            root.put("hints", hints);
        }
        return jsonRenderer.render(root);
    }

    private Map<String, Object> buildMockPlan(MockPlan plan) {
        LinkedHashMap<String, Object> block = new LinkedHashMap<>();
        block.put("strategy", plan.strategy());
        if (!plan.targets().isEmpty()) {
            List<Map<String, String>> targets = new ArrayList<>();
            for (MockTarget target : plan.targets()) {
                LinkedHashMap<String, String> entry = new LinkedHashMap<>();
                entry.put("type", target.qualifiedType());
                entry.put("identifier", target.identifier());
                targets.add(entry);
            }
            block.put("targets", targets);
        }
        if (!plan.shouldMock().isEmpty()) {
            block.put("shouldMock", plan.shouldMock());
        }
        if (!plan.shouldNotMock().isEmpty()) {
            block.put("shouldNotMock", plan.shouldNotMock());
        }
        return block;
    }

    private String resolveGoal(PromptMode mode, InstructionContext context) {
        return switch (mode) {
            case GENERATION -> generationGoal(context);
            case REPAIR -> "Repair an existing JUnit 5 unit test using the provided diagnostics.";
            case EXECUTION -> "Analyse runtime behaviour for the generated unit test and surface actionable checks.";
            case SYNTHESIS -> "Synthesize supporting fixtures or utilities that help stabilise the target unit test.";
        };
    }

    private String generationGoal(InstructionContext context) {
        if (context.isUtilityLike()) {
            return "Generate a JUnit 5 unit test for a utility method without relying on mocks.";
        }
        if (context.isPureFunction()) {
            return "Generate a focused JUnit 5 unit test for a pure function emphasising assertions.";
        }
        if (context.hasMocks()) {
            return "Generate a JUnit 5 unit test for the target method using Mockito to isolate dependencies.";
        }
        return "Generate a JUnit 5 unit test for the target method.";
    }

    private List<String> determineHints(InstructionContext context) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (context.isPureFunction()) {
            hints.add("Method appears to have no external dependencies.");
        }
        if (!context.hasMocks()) {
            hints.add("Use assertions instead of mocks.");
        }
        if (context.isStaticMethod()) {
            hints.add("Static method can be invoked directly; minimise setup.");
        }
        return new ArrayList<>(hints);
    }
}
