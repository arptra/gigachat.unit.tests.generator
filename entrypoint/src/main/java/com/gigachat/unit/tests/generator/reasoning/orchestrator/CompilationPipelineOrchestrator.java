package com.gigachat.unit.tests.generator.reasoning.orchestrator;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfoBuilder;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.service.NextContextBuilder;
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
    private final NextContextBuilder nextContextBuilder;
    private final Path projectRoot;
    private final Path testFile;
    private final String testFileFqcn;
    private final String methodName;

    public CompilationPipelineOrchestrator(CompilerInvoker compilerInvoker,
                                           ReasoningWorkflow reasoningWorkflow,
                                           ProjectContextCollector projectContextCollector,
                                           ToolActionExecutor actionExecutor,
                                           Path projectRoot,
                                           Path testFile,
                                           String testFileFqcn,
                                           String methodName) {
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.reasoningWorkflow = Objects.requireNonNull(reasoningWorkflow, "reasoningWorkflow");
        this.projectContextCollector = Objects.requireNonNull(projectContextCollector, "projectContextCollector");
        this.actionExecutor = Objects.requireNonNull(actionExecutor, "actionExecutor");
        this.nextContextBuilder = new NextContextBuilder();
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
        ActionExecutionResult cumulativeResult = ActionExecutionResult.empty();
        for (int attempt = 0; attempt < MAX_ITERATIONS; attempt++) {
            lastResult = compilerInvoker.compile(projectRoot, testFile, methodName);
            if (lastResult.success()) {
                return lastResult;
            }
            CompilationErrorInfo errorInfo = CompilationErrorInfoBuilder.from(lastResult, testFile, testFileFqcn);
            ReasoningLoopContext loopContext = nextContextBuilder.build(errorInfo, projectContextCollector.collect(), cumulativeResult);
            ReasoningResponse response = reasoningWorkflow.process(loopContext);
            ActionExecutionResult iterationResult = actionExecutor.execute(response == null ? null : response.getAction());
            cumulativeResult = cumulativeResult.merge(iterationResult);
        }
        throw new FixingFailureException("Reached maximum reasoning iterations without a successful compile", lastResult);
    }
}

