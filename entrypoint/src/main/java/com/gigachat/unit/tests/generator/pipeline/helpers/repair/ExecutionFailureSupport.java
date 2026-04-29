package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.cleaner.parser.ExecutionFailureLogParser;
import com.gigachat.unit.tests.generator.cleaner.parser.ExecutionFailureParseResult;
import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.reasoning.orchestrator.CompilationPipelineOrchestrator;
import com.gigachat.unit.tests.generator.reasoning.orchestrator.ExecutionPipelineOrchestrator;
import com.gigachat.unit.tests.generator.reasoning.orchestrator.ExecutionPipelineOrchestrator.ExecutionRepairResult;
import com.gigachat.unit.tests.generator.reasoning.service.CompilationReasoningService;
import com.gigachat.unit.tests.generator.reasoning.service.ExecutionFailureContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.ToolActionExecutor;
import com.gigachat.unit.tests.generator.report.parser.ExecutionReportParser;
import com.gigachat.unit.tests.generator.report.parser.TestReportFailure;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns execution-failure artifact parsing plus the runtime repair-loop handoff.
 */
public class ExecutionFailureSupport {

    private final PipelineLogger logger;
    private final CompilerInvoker compilerInvoker;
    private final ExecutionInvoker executionInvoker;
    private final ExecutionFailureLogParser executionFailureLogParser;
    private final ExecutionReportParser executionReportParser;
    private final ExecutionFailureContextCollector executionFailureContextCollector;
    private final CompilationReasoningService reasoningService;

    public ExecutionFailureSupport(PipelineLogger logger,
                                   CompilerInvoker compilerInvoker,
                                   ExecutionInvoker executionInvoker,
                                   ExecutionFailureLogParser executionFailureLogParser,
                                   ExecutionReportParser executionReportParser,
                                   ExecutionFailureContextCollector executionFailureContextCollector,
                                   CompilationReasoningService reasoningService) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.executionInvoker = Objects.requireNonNull(executionInvoker, "executionInvoker");
        this.executionFailureLogParser = Objects.requireNonNull(executionFailureLogParser, "executionFailureLogParser");
        this.executionReportParser = Objects.requireNonNull(executionReportParser, "executionReportParser");
        this.executionFailureContextCollector = Objects.requireNonNull(executionFailureContextCollector, "executionFailureContextCollector");
        this.reasoningService = Objects.requireNonNull(reasoningService, "reasoningService");
    }

    public ExecutionRepairResult repairExecutionFailure(AgentConfig config,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        Analyze.AnalysisSummary analysisSummary,
                                                        ToolActionExecutor actionExecutor,
                                                        CompilationPipelineOrchestrator fixingOrchestrator,
                                                        CompileResult compileResult,
                                                        ExecuteResult executeResult,
                                                        String generatedMethodName,
                                                        ExecutionFailureParseResult failureParseResult,
                                                        List<TestReportFailure> reportFailures,
                                                        GeneratedTestSnippet snippet) {
        return repairExecutionFailure(config,
                classInfo,
                methodInfo,
                analysisSummary,
                actionExecutor,
                fixingOrchestrator,
                compileResult,
                executeResult,
                generatedMethodName,
                generatedMethodName,
                failureParseResult,
                reportFailures,
                snippet);
    }

    public ExecutionRepairResult repairExecutionFailure(AgentConfig config,
                                                        TestClassInfo classInfo,
                                                        TestMethodInfo methodInfo,
                                                        Analyze.AnalysisSummary analysisSummary,
                                                        ToolActionExecutor actionExecutor,
                                                        CompilationPipelineOrchestrator fixingOrchestrator,
                                                        CompileResult compileResult,
                                                        ExecuteResult executeResult,
                                                        String generatedMethodName,
                                                        String executionMethodName,
                                                        ExecutionFailureParseResult failureParseResult,
                                                        List<TestReportFailure> reportFailures,
                                                        GeneratedTestSnippet snippet) {
        ExecutionPipelineOrchestrator orchestrator = new ExecutionPipelineOrchestrator(
                logger,
                compilerInvoker,
                executionInvoker,
                executionFailureLogParser,
                executionReportParser,
                executionFailureContextCollector,
                fixingOrchestrator,
                reasoningService);
        return orchestrator.runRepairLoop(config,
                classInfo,
                methodInfo,
                analysisSummary,
                actionExecutor,
                compileResult,
                executeResult,
                generatedMethodName,
                executionMethodName,
                failureParseResult,
                reportFailures);
    }

    public ExecutionFailureParseResult parseExecutionLog(ExecuteResult executeResult) {
        if (executeResult == null) {
            return new ExecutionFailureParseResult(List.of(), Optional.empty());
        }
        String combined = (executeResult.stdout() + System.lineSeparator() + executeResult.stderr()).trim();
        return executionFailureLogParser.parse(combined);
    }

    public List<TestReportFailure> parseExecutionReport(Path projectRoot, ExecutionFailureParseResult parseResult) {
        if (parseResult == null || parseResult.reportPath().isEmpty()) {
            return List.of();
        }
        Path reportPath = parseResult.reportPath().get();
        Path resolved = reportPath.isAbsolute() ? reportPath : projectRoot.resolve(reportPath);
        try {
            return executionReportParser.parse(resolved);
        } catch (Exception exception) {
            logger.warn("Failed to parse execution report at " + resolved + ": " + exception.getMessage());
            return List.of();
        }
    }
}
