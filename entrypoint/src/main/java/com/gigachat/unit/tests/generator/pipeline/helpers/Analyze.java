package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.MockTarget;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.AnalysisFormatter;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalyzer;
import com.testagent.entrypoint.pipeline.helpers.analyze.MockType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Performs analysis of the target method to understand mocking needs and provide context for the LLM.
 */
public class Analyze {
    private final MethodAnalyzer methodAnalyzer;
    private final AnalysisFormatter analysisFormatter;
    private final PipelineLogger logger;

    public Analyze() {
        this(null);
    }

    public Analyze(PipelineLogger logger) {
        this(new MethodAnalyzer(logger), new AnalysisFormatter(), logger);
    }

    public Analyze(MethodAnalyzer methodAnalyzer, AnalysisFormatter analysisFormatter, PipelineLogger logger) {
        this.methodAnalyzer = Objects.requireNonNull(methodAnalyzer, "methodAnalyzer");
        this.analysisFormatter = Objects.requireNonNull(analysisFormatter, "analysisFormatter");
        this.logger = logger;
    }

    public AnalysisSummary analyze(AgentConfig config, TestClassInfo classInfo, TestMethodInfo methodInfo) {
        MethodAnalysisResult result = methodAnalyzer.analyze(methodInfo, config);
        MockPlan plan = buildPlan(result);
        String contextJson = analysisFormatter.format(result);
        if (logger != null) {
            logger.info("Analysis JSON context prepared for method " + result.method().name());
        }
        return new AnalysisSummary(plan, result, contextJson);
    }

    public MockPlan analyze(TestClassInfo classInfo, TestMethodInfo methodInfo) {
        AnalysisSummary summary = analyze(null, classInfo, methodInfo);
        return summary.mockPlan();
    }

    private MockPlan buildPlan(MethodAnalysisResult result) {
        Map<String, MockTarget> collected = result.dependencies().stream()
                .filter(dependency -> dependency.mockType() != MockType.STATIC)
                .collect(LinkedHashMap::new,
                        (map, dependency) -> {
                            String key = dependency.className() + "#" + dependency.variableName();
                            map.putIfAbsent(key, new MockTarget(dependency.className(), dependency.variableName()));
                        },
                        Map::putAll);
        List<MockTarget> targets = List.copyOf(collected.values());
        MockStrategy strategy = determineStrategy(result, targets);
        return new MockPlan(targets, strategy);
    }

    private MockStrategy determineStrategy(MethodAnalysisResult result, List<MockTarget> targets) {
        boolean hasStatic = !result.staticUsages().isEmpty();
        boolean hasChain = result.dependencies().stream().anyMatch(dep -> dep.mockType() == MockType.CHAIN);
        if (hasStatic) {
            return MockStrategy.STATIC;
        }
        if (hasChain) {
            return MockStrategy.SPY;
        }
        if (!targets.isEmpty() || !result.invocations().isEmpty()) {
            return MockStrategy.MOCKITO;
        }
        return MockStrategy.NONE;
    }

    public record AnalysisSummary(MockPlan mockPlan,
                                  MethodAnalysisResult methodAnalysis,
                                  String jsonContext) {
    }
}
