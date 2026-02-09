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
import com.gigachat.unit.tests.generator.reasoning.model.FixSession;
import com.gigachat.unit.tests.generator.reasoning.model.FixSessionState;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningIterationSnapshot;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;
import com.gigachat.unit.tests.generator.reasoning.service.NextContextBuilder;
import com.gigachat.unit.tests.generator.reasoning.service.ProjectContextCollector;
import com.gigachat.unit.tests.generator.reasoning.service.ReasoningDecisionPolicyEngine;
import com.gigachat.unit.tests.generator.reasoning.service.ToolActionExecutor;
import com.gigachat.unit.tests.generator.reasoning.workflow.ReasoningWorkflow;
import com.gigachat.unit.tests.generator.reasoning.workflow.exception.FixingFailureException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Drives the compile → reasoning → apply loop until compilation succeeds or a
 * failure threshold is reached.
 */
public class CompilationPipelineOrchestrator {

    private static final int MAX_ITERATIONS = 10;
    private static final int MAX_NO_PROGRESS_STREAK = 4;

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
    private final FixSession fixSession;
    private final Map<String, Object> staticReasoningContext;
    private final ReasoningDecisionPolicyEngine policyEngine;

    public CompilationPipelineOrchestrator(CompilerInvoker compilerInvoker,
                                           ReasoningWorkflow reasoningWorkflow,
                                           ProjectContextCollector projectContextCollector,
                                           ToolActionExecutor actionExecutor,
                                           CompilationErrorClassifier errorClassifier,
                                           Path projectRoot,
                                           Path testFile,
                                           String testFileFqcn,
                                           String methodName,
                                           FixSession fixSession) {
        this(compilerInvoker,
                reasoningWorkflow,
                projectContextCollector,
                actionExecutor,
                errorClassifier,
                projectRoot,
                testFile,
                testFileFqcn,
                methodName,
                fixSession,
                Map.of());
    }

    public CompilationPipelineOrchestrator(CompilerInvoker compilerInvoker,
                                           ReasoningWorkflow reasoningWorkflow,
                                           ProjectContextCollector projectContextCollector,
                                           ToolActionExecutor actionExecutor,
                                           CompilationErrorClassifier errorClassifier,
                                           Path projectRoot,
                                           Path testFile,
                                           String testFileFqcn,
                                           String methodName,
                                           FixSession fixSession,
                                           Map<String, Object> staticReasoningContext) {
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
        this.fixSession = Objects.requireNonNull(fixSession, "fixSession");
        this.staticReasoningContext = staticReasoningContext == null ? Map.of() : Map.copyOf(staticReasoningContext);
        this.policyEngine = new ReasoningDecisionPolicyEngine();
    }

    /**
     * Runs the iterative fixing loop. Returns the last successful compile result or throws when
     * fixes could not be applied.
     */
    public CompileResult runFixingLoop() {
        prepareSessionForRun();
        CompileResult lastResult = null;
        ActionExecutionResult cumulativeResult = fixSession.getCumulativeExecutionResult();
        ReasoningMemory memory = fixSession.getMemory();
        if (memory.getState() == null) {
            memory.setState(AgentState.S0_INIT);
        }
        String previousSignature = latestSignature(memory);
        String bestSnapshot = readSnapshot(null);
        int bestErrorCount = Integer.MAX_VALUE;
        for (int attempt = 0; attempt < MAX_ITERATIONS; attempt++) {
            memory.incrementAttempt();
            lastResult = compilerInvoker.compileWithoutCache(projectRoot, testFile, methodName);
            if (lastResult.success()) {
                memory.setState(AgentState.S5_COMPILATION_SUCCESS);
                fixSession.transitionTo(FixSessionState.DONE, "compile_success");
                fixSession.appendSnapshot(new ReasoningIterationSnapshot(
                        fixSession.getId(),
                        memory.getAttempt(),
                        memory.getState(),
                        "",
                        "NONE",
                        List.of(),
                        List.of(),
                        "",
                        "COMPILE_SUCCESS"
                ));
                return lastResult;
            }
            memory.setState(AgentState.S2_COMPILATION_FAILED);
            fixSession.transitionTo(FixSessionState.DIAGNOSE, "compile_failed");
            CompilationErrorInfo errorInfo = CompilationErrorInfoBuilder.from(lastResult, testFile, testFileFqcn);
            CompilationErrorReport report = errorClassifier.classify(lastResult.stderr());
            int errorCount = report == null || report.getErrors() == null ? Integer.MAX_VALUE : report.getErrors().size();
            if (errorCount < bestErrorCount) {
                bestErrorCount = errorCount;
                bestSnapshot = readSnapshot(bestSnapshot);
            }
            String signature = deriveSignature(report, errorInfo);
            previousSignature = updateNoProgress(memory, previousSignature, signature);
            memory.addErrorSignature(signature);
            if (memory.getNoProgressStreak() >= MAX_NO_PROGRESS_STREAK) {
                memory.setState(AgentState.S6_GIVE_UP);
                fixSession.transitionTo(FixSessionState.ABORT, "no_progress_limit_reached");
                fixSession.appendSnapshot(new ReasoningIterationSnapshot(
                        fixSession.getId(),
                        memory.getAttempt(),
                        memory.getState(),
                        signature,
                        "STOP",
                        List.of(),
                        List.of(),
                        errorInfo.getPrimaryMessage(),
                        "NO_PROGRESS_LIMIT_REACHED"
                ));
                restoreSnapshot(bestSnapshot);
                throw new FixingFailureException("Reasoning loop stopped after no progress streak", lastResult, fixSession);
            }

            String missingSymbol = findMissingSymbol(report);
            if (missingSymbol != null) {
                memory.addKnownMissingSymbol(missingSymbol);
            }

            fixSession.transitionTo(FixSessionState.PLAN, "prepare_reasoning_prompt");
            Map<String, Object> iterationContext = buildIterationContext(signature, report, memory);
            ReasoningLoopContext loopContext = nextContextBuilder.build(
                    errorInfo,
                    projectContextCollector.collect(),
                    cumulativeResult,
                    report,
                    memory,
                    iterationContext
            );
            ReasoningResponse response = reasoningWorkflow.process(loopContext);
            response = policyEngine.normalize(response, report, errorInfo, cumulativeResult, memory, testFile, iterationContext);
            String decision = response == null ? "STOP" : response.getDecision();
            if (decision == null || decision.isBlank()) {
                decision = "STOP";
            }
            decision = decision.trim().toUpperCase(Locale.ROOT);
            List<String> plannedActions = extractPlannedActionTypes(response);
            List<String> plannedFixFingerprints = policyEngine.extractFixFingerprints(response, testFile);
            ActionExecutionResult iterationResult = ActionExecutionResult.empty();
            String outcome = decision;

            if ("REQUEST_CONTEXT".equals(decision)) {
                memory.setState(AgentState.S2_1_NEED_MORE_CONTEXT);
                fixSession.transitionTo(FixSessionState.APPLY, "execute_request_context_actions");
                iterationResult = actionExecutor.execute(response.toToolAction());
                cumulativeResult = cumulativeResult.merge(iterationResult);
                fixSession.mergeExecutionResult(iterationResult);
                fixSession.transitionTo(FixSessionState.VERIFY, "request_context_actions_applied");
                fixSession.transitionTo(FixSessionState.LEARN, "request_context_result_collected");
                memory.applyUpdates(response.getMemoryUpdates().getKnownMissingSymbols(),
                        response.getMemoryUpdates().getAppliedFixSignatures(),
                        extractContextCache(iterationResult));
                extractForbiddenActions(iterationResult).forEach(memory::addForbiddenAction);
                memory.decrementContextBudget();
                if (memory.getContextRequestBudgetRemaining() <= 0) {
                    memory.setState(AgentState.S6_GIVE_UP);
                    fixSession.transitionTo(FixSessionState.ABORT, "context_budget_exhausted");
                    fixSession.appendSnapshot(new ReasoningIterationSnapshot(
                            fixSession.getId(),
                            memory.getAttempt(),
                            memory.getState(),
                            signature,
                            decision,
                            plannedActions,
                            iterationResult.getPerformedActions(),
                            errorInfo.getPrimaryMessage(),
                            "REQUEST_CONTEXT_BUDGET_EXHAUSTED"
                    ));
                    restoreSnapshot(bestSnapshot);
                    throw new FixingFailureException("Context request budget exhausted", lastResult, fixSession);
                }
                fixSession.transitionTo(FixSessionState.OBSERVE, "continue_after_request_context");
                fixSession.appendSnapshot(new ReasoningIterationSnapshot(
                        fixSession.getId(),
                        memory.getAttempt(),
                        memory.getState(),
                        signature,
                        decision,
                        plannedActions,
                        iterationResult.getPerformedActions(),
                        errorInfo.getPrimaryMessage(),
                        outcome
                ));
                continue;
            } else if ("APPLY_FIX".equals(decision)) {
                for (String fingerprint : plannedFixFingerprints) {
                    memory.incrementFixFingerprintAttempt(fingerprint);
                }
                fixSession.transitionTo(FixSessionState.APPLY, "execute_fix_actions");
                iterationResult = actionExecutor.execute(response.toToolAction());
                cumulativeResult = cumulativeResult.merge(iterationResult);
                fixSession.mergeExecutionResult(iterationResult);
                fixSession.transitionTo(FixSessionState.VERIFY, "fix_actions_applied");
                fixSession.transitionTo(FixSessionState.LEARN, "fix_result_collected");
                memory.applyUpdates(response.getMemoryUpdates().getKnownMissingSymbols(),
                        response.getMemoryUpdates().getAppliedFixSignatures(),
                        extractContextCache(iterationResult));
                extractForbiddenActions(iterationResult).forEach(memory::addForbiddenAction);
                if (!iterationResult.getPerformedActions().isEmpty()) {
                    memory.setState(AgentState.S4_FIX_APPLIED);
                    memory.resetContextBudget();
                    for (String fingerprint : plannedFixFingerprints) {
                        memory.addAppliedFixSignature(fingerprint);
                    }
                } else {
                    outcome = "APPLY_FIX_NO_EFFECT";
                    for (String fingerprint : plannedFixFingerprints) {
                        memory.blockFixFingerprint(fingerprint);
                    }
                    if (plannedActions.contains(ToolActionType.ADD_IMPORT.name())) {
                        memory.addForbiddenAction(ToolActionType.ADD_IMPORT.name());
                    }
                }
                fixSession.transitionTo(FixSessionState.OBSERVE, "continue_after_apply_fix");
                fixSession.appendSnapshot(new ReasoningIterationSnapshot(
                        fixSession.getId(),
                        memory.getAttempt(),
                        memory.getState(),
                        signature,
                        decision,
                        plannedActions,
                        iterationResult.getPerformedActions(),
                        errorInfo.getPrimaryMessage(),
                        outcome
                ));
            } else if ("MARK_FALSE_DEPENDENCY".equals(decision)) {
                memory.applyUpdates(response.getMemoryUpdates().getKnownMissingSymbols(),
                        response.getMemoryUpdates().getAppliedFixSignatures(),
                        response.getMemoryUpdates().getContextCache());
                memory.setState(AgentState.S3_FALSE_DEPENDENCY_DETECTED);
                fixSession.transitionTo(FixSessionState.LEARN, "mark_false_dependency");
                fixSession.transitionTo(FixSessionState.OBSERVE, "continue_after_mark_false_dependency");
                fixSession.appendSnapshot(new ReasoningIterationSnapshot(
                        fixSession.getId(),
                        memory.getAttempt(),
                        memory.getState(),
                        signature,
                        decision,
                        plannedActions,
                        List.of(),
                        errorInfo.getPrimaryMessage(),
                        outcome
                ));
                continue;
            } else {
                memory.setState(AgentState.S6_GIVE_UP);
                fixSession.transitionTo(FixSessionState.ABORT, "unsupported_decision");
                fixSession.appendSnapshot(new ReasoningIterationSnapshot(
                        fixSession.getId(),
                        memory.getAttempt(),
                        memory.getState(),
                        signature,
                        decision,
                        plannedActions,
                        List.of(),
                        errorInfo.getPrimaryMessage(),
                        "UNSUPPORTED_DECISION"
                ));
                restoreSnapshot(bestSnapshot);
                throw new FixingFailureException("Reasoning agent stopped after repeated failures", lastResult, fixSession);
            }
        }
        fixSession.transitionTo(FixSessionState.ABORT, "max_iterations_exhausted");
        restoreSnapshot(bestSnapshot);
        throw new FixingFailureException("Reached maximum reasoning iterations without a successful compile", lastResult, fixSession);
    }

    public List<ReasoningIterationSnapshot> getIterationSnapshots() {
        return fixSession.getJournal().getIterationSnapshots();
    }

    public FixSession getFixSession() {
        return fixSession;
    }

    private void prepareSessionForRun() {
        FixSessionState state = fixSession.getState();
        if (state == FixSessionState.DONE || state == FixSessionState.ABORT) {
            fixSession.transitionTo(FixSessionState.OBSERVE, "start_new_orchestrator_run");
            return;
        }
        if (state != FixSessionState.OBSERVE) {
            fixSession.transitionTo(FixSessionState.OBSERVE, "resume_orchestrator_run");
        }
    }

    private String updateNoProgress(ReasoningMemory memory, String previousSignature, String signature) {
        if (signature == null || signature.isBlank()) {
            memory.resetNoProgressStreak();
            return signature;
        }
        if (signature.equals(previousSignature)) {
            memory.incrementNoProgressStreak();
        } else {
            memory.resetNoProgressStreak();
        }
        return signature;
    }

    private String latestSignature(ReasoningMemory memory) {
        if (memory == null || memory.getRecentErrorSignatures().isEmpty()) {
            return null;
        }
        List<String> signatures = memory.getRecentErrorSignatures();
        return signatures.get(signatures.size() - 1);
    }

    private List<String> extractPlannedActionTypes(ReasoningResponse response) {
        if (response == null || response.getActions() == null || response.getActions().isEmpty()) {
            return List.of();
        }
        List<String> planned = new ArrayList<>();
        for (ReasoningResponse.ReasoningAction action : response.getActions()) {
            if (action != null && action.getType() != null && !action.getType().isBlank()) {
                planned.add(action.getType().trim().toUpperCase(Locale.ROOT));
            }
        }
        return List.copyOf(planned);
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

    @SuppressWarnings("unchecked")
    private java.util.Map<String, String> extractContextCache(ActionExecutionResult result) {
        if (result == null || result.getInformation().isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        Object updates = result.getInformation().get("contextCacheUpdates");
        if (updates instanceof Map<?, ?> map) {
            java.util.Map<String, String> converted = new java.util.HashMap<>();
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    converted.put(k.toString(), v.toString());
                }
            });
            return converted;
        }
        return java.util.Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractForbiddenActions(ActionExecutionResult result) {
        if (result == null || result.getInformation().isEmpty()) {
            return List.of();
        }
        Object raw = result.getInformation().get("forbiddenActions");
        if (raw instanceof List<?> list) {
            List<String> forbidden = new ArrayList<>();
            for (Object value : list) {
                if (value != null && !value.toString().isBlank()) {
                    forbidden.add(value.toString());
                }
            }
            return List.copyOf(forbidden);
        }
        return List.of();
    }

    private Map<String, Object> buildIterationContext(String signature,
                                                      CompilationErrorReport report,
                                                      ReasoningMemory memory) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (!staticReasoningContext.isEmpty()) {
            context.put("repairTargetContext", staticReasoningContext);
        }
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", fixSession.getId());
        session.put("state", fixSession.getState().name());
        session.put("attempt", memory.getAttempt());
        session.put("contextBudgetRemaining", memory.getContextRequestBudgetRemaining());
        session.put("currentErrorSignature", signature);
        session.put("errorCount", report == null || report.getErrors() == null ? 0 : report.getErrors().size());
        context.put("fixSession", session);
        context.put("recentTransitions", tailTransitions(6));
        context.put("recentSnapshots", tailSnapshots(5));
        return context;
    }

    private List<Map<String, Object>> tailTransitions(int limit) {
        List<com.gigachat.unit.tests.generator.reasoning.model.FixSessionTransition> transitions = fixSession.getJournal().getTransitions();
        if (transitions.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, transitions.size() - Math.max(limit, 1));
        List<Map<String, Object>> tail = new ArrayList<>();
        for (int i = start; i < transitions.size(); i++) {
            com.gigachat.unit.tests.generator.reasoning.model.FixSessionTransition transition = transitions.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", transition.getIndex());
            entry.put("from", transition.getFrom() == null ? "" : transition.getFrom().name());
            entry.put("to", transition.getTo() == null ? "" : transition.getTo().name());
            entry.put("reason", transition.getReason());
            tail.add(entry);
        }
        return List.copyOf(tail);
    }

    private List<Map<String, Object>> tailSnapshots(int limit) {
        List<ReasoningIterationSnapshot> snapshots = fixSession.getJournal().getIterationSnapshots();
        if (snapshots.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, snapshots.size() - Math.max(limit, 1));
        List<Map<String, Object>> tail = new ArrayList<>();
        for (int i = start; i < snapshots.size(); i++) {
            ReasoningIterationSnapshot snapshot = snapshots.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("iteration", snapshot.getIteration());
            entry.put("state", snapshot.getState() == null ? "" : snapshot.getState().name());
            entry.put("decision", snapshot.getDecision());
            entry.put("outcome", snapshot.getOutcome());
            entry.put("errorSignature", snapshot.getErrorSignature());
            entry.put("plannedActions", snapshot.getPlannedActions());
            entry.put("performedActions", snapshot.getPerformedActions());
            tail.add(entry);
        }
        return List.copyOf(tail);
    }

    private String readSnapshot(String fallback) {
        try {
            return Files.readString(testFile, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return fallback;
        }
    }

    private void restoreSnapshot(String snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            Files.writeString(testFile, snapshot, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // best effort rollback
        }
    }
}
