package com.gigachat.unit.tests.generator.reasoning.orchestrator;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.compile.classification.classify.CompilationErrorClassifier;
import com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorReport;
import com.gigachat.unit.tests.generator.compile.classification.model.CompilationError;
import com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorClass;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfoBuilder;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningLoopContext;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningMemory;
import com.gigachat.unit.tests.generator.reasoning.model.AgentState;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;
import com.gigachat.unit.tests.generator.reasoning.service.NextContextBuilder;
import com.gigachat.unit.tests.generator.reasoning.service.ProjectContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.ToolActionExecutor;
import com.gigachat.unit.tests.generator.reasoning.workflow.ReasoningWorkflow;
import com.gigachat.unit.tests.generator.reasoning.workflow.exception.FixingFailureException;

import java.io.IOException;
import java.nio.file.Files;
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
    private final CompilationErrorClassifier errorClassifier;
    private final Path projectRoot;
    private final Path testFile;
    private final String testFileFqcn;
    private final String methodName;

    public CompilationPipelineOrchestrator(CompilerInvoker compilerInvoker,
                                           ReasoningWorkflow reasoningWorkflow,
                                           ProjectContextCollector projectContextCollector,
                                           ToolActionExecutor actionExecutor,
                                           CompilationErrorClassifier errorClassifier,
                                           Path projectRoot,
                                           Path testFile,
                                           String testFileFqcn,
                                           String methodName) {
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.reasoningWorkflow = Objects.requireNonNull(reasoningWorkflow, "reasoningWorkflow");
        this.projectContextCollector = Objects.requireNonNull(projectContextCollector, "projectContextCollector");
        this.actionExecutor = Objects.requireNonNull(actionExecutor, "actionExecutor");
        this.nextContextBuilder = new NextContextBuilder();
        this.errorClassifier = errorClassifier == null ? new CompilationErrorClassifier() : errorClassifier;
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
        ReasoningMemory memory = new ReasoningMemory();
        memory.setState(AgentState.S0_INIT);
        memory.addForbiddenAction("ADD_DEPENDENCY");
        for (int attempt = 0; attempt < MAX_ITERATIONS; attempt++) {
            memory.incrementAttempt();
            lastResult = compilerInvoker.compileWithoutCache(projectRoot, testFile, methodName);
            if (lastResult.success()) {
                memory.setState(AgentState.S5_COMPILATION_SUCCESS);
                return lastResult;
            }
            memory.setState(AgentState.S2_COMPILATION_FAILED);
            CompilationErrorInfo errorInfo = CompilationErrorInfoBuilder.from(lastResult, testFile, testFileFqcn);
            CompilationErrorReport report = errorClassifier.classify(lastResult.stderr());
            String signature = deriveSignature(report, errorInfo);
            memory.addErrorSignature(signature);
            if (memory.countOccurrences(signature) > 2) {
                memory.setState(AgentState.S6_GIVE_UP);
                throw new FixingFailureException("Repeated compilation errors detected. Giving up.", lastResult);
            }

            String missingSymbol = findMissingSymbol(report);
            if (missingSymbol != null && !symbolExistsInSources(missingSymbol)) {
                memory.setState(AgentState.S3_FALSE_DEPENDENCY_DETECTED);
                memory.addKnownMissingSymbol(missingSymbol);
                memory.addForbiddenAction(ToolActionType.ADD_IMPORT.name());
            }

            ReasoningLoopContext loopContext = nextContextBuilder.build(errorInfo, projectContextCollector.collect(), cumulativeResult, report, memory);
            ReasoningResponse response = reasoningWorkflow.process(loopContext);
            if (response == null || response.getDecision() == null) {
                throw new FixingFailureException("Reasoning response missing decision", lastResult);
            }
            memory.applyUpdates(response.getMemoryUpdates().getKnownMissingSymbols(),
                    response.getMemoryUpdates().getAppliedFixSignatures());

            ToolActionType decision = ToolActionType.valueOf(response.getDecision());
            if (decision == ToolActionType.STOP || memory.getState() == AgentState.S6_GIVE_UP) {
                memory.setState(AgentState.S6_GIVE_UP);
                throw new FixingFailureException("Reasoning agent stopped after repeated failures", lastResult);
            }
            if (decision == ToolActionType.MARK_FALSE_DEPENDENCY) {
                memory.setState(AgentState.S3_FALSE_DEPENDENCY_DETECTED);
                continue;
            }
            ActionExecutionResult iterationResult = actionExecutor.execute(response.toToolAction());
            if (!iterationResult.getPerformedActions().isEmpty()) {
                memory.setState(AgentState.S4_FIX_APPLIED);
            }
            cumulativeResult = cumulativeResult.merge(iterationResult);
        }
        throw new FixingFailureException("Reached maximum reasoning iterations without a successful compile", lastResult);
    }

    private String deriveSignature(CompilationErrorReport report, CompilationErrorInfo fallback) {
        if (report != null && report.getErrors() != null && !report.getErrors().isEmpty()) {
            CompilationError first = report.getErrors().get(0);
            String symbol = first.getSymbol() == null ? first.getNormalizedMessage() : first.getSymbol();
            String file = first.getFilePath() == null ? "" : first.getFilePath();
            return first.getErrorClass().name() + "|" + symbol + "|" + file;
        }
        return "UNKNOWN|" + fallback.getPrimaryMessage() + "|" + fallback.getTestFilePath();
    }

    private String findMissingSymbol(CompilationErrorReport report) {
        if (report == null || report.getErrors() == null) {
            return null;
        }
        return report.getErrors().stream()
                .filter(error -> error.getErrorClass() == CompilationErrorClass.MISSING_IMPORT_OR_SYMBOL)
                .map(CompilationError::getSymbol)
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .findFirst()
                .orElse(null);
    }

    private boolean symbolExistsInSources(String symbol) {
        try {
            Path sourceRoot = projectRoot.resolve("src/main/java").toAbsolutePath().normalize();
            if (!Files.exists(sourceRoot)) {
                return false;
            }
            try (var paths = Files.walk(sourceRoot)) {
                for (Path path : (Iterable<Path>) paths
                        .filter(candidate -> Files.isRegularFile(candidate) && candidate.toString().endsWith(".java"))
                        ::iterator) {
                    String content = Files.readString(path);
                    if (content.contains(symbol)) {
                        return true;
                    }
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return false;
    }
}
