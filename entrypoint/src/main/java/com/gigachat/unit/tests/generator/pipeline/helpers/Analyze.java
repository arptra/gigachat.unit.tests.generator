package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AnalysisConfig;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.AnalysisFormatter;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalyzer;
import com.testagent.entrypoint.pipeline.helpers.analyze.InvocationInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.MockStrategyResolver;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.ArrayList;

/**
 * Performs analysis of the target method to understand mocking needs and provide context for the LLM.
 */
public class Analyze {
    private final MethodAnalyzer methodAnalyzer;
    private final AnalysisFormatter analysisFormatter;
    private final MockStrategyResolver mockStrategyResolver;
    private final PipelineLogger logger;

    public Analyze() {
        this(null);
    }

    public Analyze(PipelineLogger logger) {
        this(new MethodAnalyzer(logger), new AnalysisFormatter(), new MockStrategyResolver(), logger);
    }

    public Analyze(MethodAnalyzer methodAnalyzer,
                   AnalysisFormatter analysisFormatter,
                   MockStrategyResolver mockStrategyResolver,
                   PipelineLogger logger) {
        this.methodAnalyzer = Objects.requireNonNull(methodAnalyzer, "methodAnalyzer");
        this.analysisFormatter = Objects.requireNonNull(analysisFormatter, "analysisFormatter");
        this.mockStrategyResolver = Objects.requireNonNull(mockStrategyResolver, "mockStrategyResolver");
        this.logger = logger;
    }

    public AnalysisSummary analyze(AgentConfig config, TestClassInfo classInfo, TestMethodInfo methodInfo) {
        AnalysisConfig analysisConfig = config == null ? AnalysisConfig.from(Map.of()) : config.getAnalysisConfig();
        MethodAnalysisResult result = methodAnalyzer.analyze(methodInfo, config);
        MockPlan plan = mockStrategyResolver.createPlan(result, analysisConfig);
        Map<String, String> verificationPolicy = analysisConfig.includeVerificationPolicy()
                ? buildVerificationPolicy(result.invocations())
                : Map.of();
        String contextJson = analysisFormatter.format(result);
        if (logger != null) {
            logger.info("Analysis JSON context prepared for method " + result.method().name());
        }
        return new AnalysisSummary(plan, result, contextJson, verificationPolicy);
    }

    public MockPlan analyze(TestClassInfo classInfo, TestMethodInfo methodInfo) {
        AnalysisSummary summary = analyze(null, classInfo, methodInfo);
        return summary.mockPlan();
    }

    public Map<String, String> buildVerificationPolicy(List<InvocationInfo> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return Map.of();
        }
        Map<String, String> policy = new LinkedHashMap<>();
        for (InvocationInfo invocation : invocations) {
            if (invocation == null) {
                continue;
            }
            String key = invocation.target() + "." + invocation.methodName();
            policy.putIfAbsent(key, toVerificationExpression(invocation));
        }
        return policy;
    }

    private String toVerificationExpression(InvocationInfo invocation) {
        List<String> args = invocation.argTypes();
        if (args == null || args.isEmpty()) {
            return "verify(" + invocation.target() + ")." + invocation.methodName() + "()";
        }
        List<String> normalised = new ArrayList<>(args.size());
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                normalised.add("...");
                continue;
            }
            String trimmed = arg.trim();
            if (trimmed.matches("[A-Za-z0-9_]+")) {
                normalised.add(trimmed);
            } else {
                normalised.add("...");
            }
        }
        return "verify(" + invocation.target() + ")." + invocation.methodName() + "(" + String.join(", ", normalised) + ")";
    }

    public record AnalysisSummary(MockPlan mockPlan,
                                  MethodAnalysisResult methodAnalysis,
                                  String jsonContext,
                                  Map<String, String> verificationPolicy) {
    }
}
