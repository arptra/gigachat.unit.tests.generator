package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AnalysisConfig;
import com.gigachat.unit.tests.generator.config.PipelineModuleConfig;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.testagent.entrypoint.pipeline.helpers.analyze.AnalysisFormatter;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalyzer;
import com.testagent.entrypoint.pipeline.helpers.analyze.InvocationInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.MockStrategyResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

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
        PipelineModuleConfig pipelineConfig = config == null
                ? PipelineModuleConfig.from(Map.of())
                : config.getPipelineModuleConfig();
        MethodAnalysisResult result = methodAnalyzer.analyze(classInfo, methodInfo, config, pipelineConfig);
        MockPlan plan = mockStrategyResolver.createPlan(result,
                analysisConfig,
                pipelineConfig.autoMockDetectionEnabled(),
                pipelineConfig.excludeInternalCollections());
        Map<String, String> verificationPolicy = analysisConfig.includeVerificationPolicy()
                ? buildVerificationPolicy(result.invocations())
                : Map.of();
        String contextJson = analysisFormatter.format(result);
        TestTargetContext targetContext = extractTestTargetContext(classInfo, methodInfo);
        if (logger != null) {
            logger.info("Analysis JSON context prepared for method " + result.method().name());
        }
        return new AnalysisSummary(plan, result, contextJson, verificationPolicy, targetContext);
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

    private TestTargetContext extractTestTargetContext(TestClassInfo classInfo, TestMethodInfo methodInfo) {
        String className = classInfo == null ? "" : defaultString(classInfo.getClassName());
        String instanceName = deriveInstanceName(className);
        MethodDeclaration declaration = methodInfo == null ? null : methodInfo.getDeclaration();
        boolean isStatic = determineStatic(declaration, methodInfo);
        boolean requiresInstance = !isStatic;
        return new TestTargetContext(className, instanceName, requiresInstance, isStatic);
    }

    private boolean determineStatic(MethodDeclaration declaration, TestMethodInfo methodInfo) {
        if (declaration != null) {
            return declaration.isStatic();
        }
        String signature = methodInfo == null ? null : methodInfo.getSignature();
        if (signature == null || signature.isBlank()) {
            return false;
        }
        return STATIC_KEYWORD.matcher(signature).find();
    }

    private String deriveInstanceName(String className) {
        String base = defaultString(className);
        if (base.isEmpty()) {
            return "instance";
        }
        String[] parts = CAMEL_CASE_SPLIT.split(base);
        String candidate = parts.length == 0 ? base : parts[parts.length - 1];
        if (candidate.isBlank()) {
            candidate = base;
        }
        if (candidate.length() == 1) {
            return candidate.toLowerCase(Locale.ROOT);
        }
        return candidate.substring(0, 1).toLowerCase(Locale.ROOT) + candidate.substring(1);
    }

    private String defaultString(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private static final Pattern CAMEL_CASE_SPLIT = Pattern.compile("(?<!^)(?=[A-Z])");
    private static final Pattern STATIC_KEYWORD = Pattern.compile("\\bstatic\\b");

    public record AnalysisSummary(MockPlan mockPlan,
                                  MethodAnalysisResult methodAnalysis,
                                  String jsonContext,
                                  Map<String, String> verificationPolicy,
                                  TestTargetContext testTargetContext) {
    }

    public record TestTargetContext(String className,
                                    String instanceName,
                                    boolean requiresInstance,
                                    boolean isStatic) {
    }
}
