package com.gigachat.unit.tests.generator.reasoning.orchestrator;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfoBuilder;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.service.ProjectContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.ToolActionExecutor;
import com.gigachat.unit.tests.generator.reasoning.workflow.ReasoningWorkflow;
import com.gigachat.unit.tests.generator.reasoning.workflow.exception.FixingFailureException;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Drives the compile → reasoning → apply loop until compilation succeeds or a
 * failure threshold is reached.
 */
public class CompilationPipelineOrchestrator {

    private static final int MAX_ITERATIONS = 3;

    private final CompilerInvoker compilerInvoker;
    private final ReasoningWorkflow reasoningWorkflow;
    private final ProjectContextCollector projectContextCollector;
    private final ToolActionExecutor actionExecutor;
    private final PipelineLogger logger;
    private final Path projectRoot;
    private final Path testFile;
    private final String testFileFqcn;
    private final String methodName;

    public CompilationPipelineOrchestrator(CompilerInvoker compilerInvoker,
                                           ReasoningWorkflow reasoningWorkflow,
                                           ProjectContextCollector projectContextCollector,
                                           ToolActionExecutor actionExecutor,
                                           PipelineLogger logger,
                                           Path projectRoot,
                                           Path testFile,
                                           String testFileFqcn,
                                           String methodName) {
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.reasoningWorkflow = Objects.requireNonNull(reasoningWorkflow, "reasoningWorkflow");
        this.projectContextCollector = Objects.requireNonNull(projectContextCollector, "projectContextCollector");
        this.actionExecutor = Objects.requireNonNull(actionExecutor, "actionExecutor");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.testFile = Objects.requireNonNull(testFile, "testFile");
        this.testFileFqcn = testFileFqcn;
        this.methodName = methodName;
    }

    /**
     * Runs the iterative fixing loop. Returns the last successful compile result or throws when
     * fixes could not be applied.
     */
    public CompileResult runFixingLoop() {
        CompileResult lastResult = null;
        for (int attempt = 0; attempt < MAX_ITERATIONS; attempt++) {
            lastResult = compilerInvoker.compile(projectRoot, testFile, methodName);
            if (lastResult.success()) {
                return lastResult;
            }
            logger.warn("Compilation failed; invoking reasoning loop (attempt " + (attempt + 1) + ")");
            CompilationErrorInfo errorInfo = CompilationErrorInfoBuilder.from(lastResult, testFile, testFileFqcn);
            ReasoningResponse response = reasoningWorkflow.process(errorInfo, projectContextCollector.collect());
            actionExecutor.execute(response == null ? null : response.getAction());
        }
        throw new FixingFailureException("Reached maximum reasoning iterations without a successful compile", lastResult);
    }
}

