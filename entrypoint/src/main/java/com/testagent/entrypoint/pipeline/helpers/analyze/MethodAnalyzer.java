package com.testagent.entrypoint.pipeline.helpers.analyze;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.StaticJavaParser;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Coordinates dependency and invocation analysis for a method.
 */
public class MethodAnalyzer {
    private final DependencyAnalyzer dependencyAnalyzer;
    private final InvocationAnalyzer invocationAnalyzer;
    private final PipelineLogger logger;

    public MethodAnalyzer(PipelineLogger logger) {
        MockStrategyResolver resolver = new MockStrategyResolver();
        this.dependencyAnalyzer = new DependencyAnalyzer(resolver);
        this.invocationAnalyzer = new InvocationAnalyzer(resolver);
        this.logger = logger;
    }

    public MethodAnalysisResult analyze(TestMethodInfo methodInfo, AgentConfig config) {
        AnalysisOptions options = AnalysisOptions.from(config);
        BlockStmt body = parseBody(methodInfo.getBody());
        MethodMetadata metadata = new MethodMetadata(extractMethodName(methodInfo.getSignature()),
                methodInfo.getSignature(),
                methodInfo.getReturnType());
        List<DependencyInfo> dependencies = dependencyAnalyzer.analyze(body, options);
        InvocationAnalyzer.InvocationAnalysis invocationAnalysis = invocationAnalyzer.analyze(body, options);
        MethodAnalysisResult result = new MethodAnalysisResult(metadata,
                dependencies,
                invocationAnalysis.invocations(),
                invocationAnalysis.staticUsages(),
                invocationAnalysis.unresolved());
        if (logger != null) {
            logger.info("Method analysis completed for " + metadata.name() + ": "
                    + dependencies.size() + " dependencies, "
                    + invocationAnalysis.invocations().size() + " invocations");
        }
        return result;
    }

    private BlockStmt parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return StaticJavaParser.parseBlock(body);
        } catch (ParseProblemException exception) {
            if (logger != null) {
                logger.warn("Unable to parse method body for analysis: " + exception.getMessage());
            }
            return null;
        }
    }

    private String extractMethodName(String signature) {
        if (signature == null || signature.isBlank()) {
            return "method";
        }
        int parenIndex = signature.indexOf('(');
        String before = parenIndex >= 0 ? signature.substring(0, parenIndex) : signature;
        String[] parts = before.trim().split("\\s+");
        return parts.length == 0 ? "method" : parts[parts.length - 1];
    }
}
