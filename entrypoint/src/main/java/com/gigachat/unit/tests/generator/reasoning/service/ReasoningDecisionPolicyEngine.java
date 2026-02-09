package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorClass;
import com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorReport;
import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningMemory;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import com.gigachat.unit.tests.generator.reasoning.model.ToolActionType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Enforces deterministic preconditions for tool actions produced by LLM and
 * provides safe fallbacks when the response is incomplete or contradictory.
 */
public class ReasoningDecisionPolicyEngine {

    private static final Pattern DEPENDENCY_PATTERN = Pattern.compile("[a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+:[a-zA-Z0-9+_.-]+");
    private static final Map<String, String> PACKAGE_DEPENDENCY_HINTS = Map.of(
            "org.mockito.junit.jupiter", "org.mockito:mockito-junit-jupiter:5.11.0",
            "org.mockito", "org.mockito:mockito-core:5.11.0",
            "org.junit.jupiter", "org.junit.jupiter:junit-jupiter:5.10.2",
            "org.assertj.core", "org.assertj:assertj-core:3.26.0"
    );
    private static final Set<ToolActionType> FIX_TYPES = Set.of(
            ToolActionType.APPLY_PATCH,
            ToolActionType.ADD_IMPORT,
            ToolActionType.ADD_DEPENDENCY,
            ToolActionType.ALIGN_MOCKS
    );
    private static final Set<ToolActionType> CONTEXT_TYPES = Set.of(
            ToolActionType.SHOW_FILE,
            ToolActionType.SHOW_IMPORTS,
            ToolActionType.SEARCH_SYMBOL,
            ToolActionType.READ_CLASS,
            ToolActionType.READ_METHOD,
            ToolActionType.LIST_METHODS
    );

    public ReasoningResponse normalize(ReasoningResponse raw,
                                       CompilationErrorReport report,
                                       CompilationErrorInfo errorInfo,
                                       ActionExecutionResult cumulativeResult,
                                       ReasoningMemory memory,
                                       Path testFile) {
        return normalize(raw, report, errorInfo, cumulativeResult, memory, testFile, Map.of());
    }

    public ReasoningResponse normalize(ReasoningResponse raw,
                                       CompilationErrorReport report,
                                       CompilationErrorInfo errorInfo,
                                       ActionExecutionResult cumulativeResult,
                                       ReasoningMemory memory,
                                       Path testFile,
                                       Map<String, Object> iterationContext) {
        if (raw == null) {
            return buildFallback(report, errorInfo, cumulativeResult, memory, testFile, iterationContext);
        }
        String decision = normalizeDecision(raw.getDecision());
        if ("APPLY_FIX".equals(decision)) {
            return normalizeApplyFix(raw, report, errorInfo, cumulativeResult, memory, testFile, iterationContext);
        }
        if ("REQUEST_CONTEXT".equals(decision)) {
            return normalizeRequestContext(raw, report, errorInfo, memory, testFile, iterationContext);
        }
        if ("MARK_FALSE_DEPENDENCY".equals(decision)) {
            return normalizeFalseDependency(raw, report, errorInfo, memory, testFile, iterationContext);
        }
        if ("STOP".equals(decision)) {
            if (memory.getContextRequestBudgetRemaining() > 0) {
                return buildFallback(report, errorInfo, cumulativeResult, memory, testFile, iterationContext);
            }
            raw.setDecision("STOP");
            raw.setActions(List.of());
            return raw;
        }
        raw.setDecision("STOP");
        raw.setActions(List.of());
        return raw;
    }

    public List<String> extractFixFingerprints(ReasoningResponse response, Path testFile) {
        if (response == null || response.getActions() == null || response.getActions().isEmpty()) {
            return List.of();
        }
        List<String> fingerprints = new ArrayList<>();
        for (ReasoningResponse.ReasoningAction action : response.getActions()) {
            if (action == null) {
                continue;
            }
            ToolActionType type = parseType(action.getType());
            if (type == null || !FIX_TYPES.contains(type)) {
                continue;
            }
            Map<String, Object> args = canonicalArgs(type, action.getArgs(), testFile);
            if (args.isEmpty()) {
                continue;
            }
            fingerprints.add(fingerprint(type, args));
        }
        return List.copyOf(fingerprints);
    }

    private ReasoningResponse normalizeApplyFix(ReasoningResponse raw,
                                                CompilationErrorReport report,
                                                CompilationErrorInfo errorInfo,
                                                ActionExecutionResult cumulativeResult,
                                                ReasoningMemory memory,
                                                Path testFile,
                                                Map<String, Object> iterationContext) {
        List<ReasoningResponse.ReasoningAction> normalized = new ArrayList<>();
        for (ReasoningResponse.ReasoningAction action : safeActions(raw)) {
            ToolActionType type = parseType(action.getType());
            if (type == null || !FIX_TYPES.contains(type)) {
                continue;
            }
            Map<String, Object> args = canonicalArgs(type, action.getArgs(), testFile);
            if (args.isEmpty()) {
                continue;
            }
            if (!arePreconditionsSatisfied(action.getPreconditions(), type, args, report, cumulativeResult, testFile)) {
                continue;
            }
            if (type == ToolActionType.ADD_IMPORT && !isImportActionAllowed(args, report, errorInfo, cumulativeResult, memory)) {
                memory.addForbiddenAction(ToolActionType.ADD_IMPORT.name());
                continue;
            }
            if (type == ToolActionType.ADD_DEPENDENCY && !isDependencyActionAllowed(args, report)) {
                memory.addForbiddenAction(ToolActionType.ADD_DEPENDENCY.name());
                continue;
            }
            if (type == ToolActionType.APPLY_PATCH && !isPatchActionAllowed(args)) {
                memory.addForbiddenAction(ToolActionType.APPLY_PATCH.name());
                continue;
            }
            String fingerprint = fingerprint(type, args);
            if (memory.isFixFingerprintBlocked(fingerprint)) {
                continue;
            }
            if (memory.getNoProgressStreak() >= 2
                    && type == ToolActionType.ADD_IMPORT
                    && memory.getFixFingerprintAttempts().getOrDefault(fingerprint, 0) > 0) {
                memory.blockFixFingerprint(fingerprint);
                continue;
            }
            normalized.add(createAction(type, args));
        }
        if (normalized.isEmpty()) {
            return buildFallback(report, errorInfo, cumulativeResult, memory, testFile, iterationContext);
        }
        raw.setDecision("APPLY_FIX");
        raw.setActions(normalized);
        return raw;
    }

    private ReasoningResponse normalizeRequestContext(ReasoningResponse raw,
                                                      CompilationErrorReport report,
                                                      CompilationErrorInfo errorInfo,
                                                      ReasoningMemory memory,
                                                      Path testFile,
                                                      Map<String, Object> iterationContext) {
        if (memory.getContextRequestBudgetRemaining() <= 0) {
            raw.setDecision("STOP");
            raw.setActions(List.of());
            return raw;
        }
        List<ReasoningResponse.ReasoningAction> normalized = new ArrayList<>();
        for (ReasoningResponse.ReasoningAction action : safeActions(raw)) {
            ToolActionType type = parseType(action.getType());
            if (type == null || !CONTEXT_TYPES.contains(type)) {
                continue;
            }
            Map<String, Object> args = canonicalArgs(type, action.getArgs(), testFile);
            if (requiresArguments(type) && args.isEmpty()) {
                continue;
            }
            if (!arePreconditionsSatisfied(action.getPreconditions(), type, args, report, ActionExecutionResult.empty(), testFile)) {
                continue;
            }
            normalized.add(createAction(type, args));
        }
        if (normalized.isEmpty()) {
            return buildFallback(report, errorInfo, ActionExecutionResult.empty(), memory, testFile, iterationContext);
        }
        raw.setDecision("REQUEST_CONTEXT");
        raw.setActions(normalized);
        return raw;
    }

    private ReasoningResponse normalizeFalseDependency(ReasoningResponse raw,
                                                       CompilationErrorReport report,
                                                       CompilationErrorInfo errorInfo,
                                                       ReasoningMemory memory,
                                                       Path testFile,
                                                       Map<String, Object> iterationContext) {
        String symbol = extractMissingSymbol(report, errorInfo, memory);
        if (symbol == null || symbol.isBlank()) {
            return buildFallback(report, errorInfo, ActionExecutionResult.empty(), memory, testFile, iterationContext);
        }
        ReasoningResponse.ReasoningAction action = createAction(ToolActionType.MARK_FALSE_DEPENDENCY, Map.of("symbol", symbol));
        raw.setDecision("MARK_FALSE_DEPENDENCY");
        raw.setActions(List.of(action));
        return raw;
    }

    private ReasoningResponse buildFallback(CompilationErrorReport report,
                                            CompilationErrorInfo errorInfo,
                                            ActionExecutionResult cumulativeResult,
                                            ReasoningMemory memory,
                                            Path testFile,
                                            Map<String, Object> iterationContext) {
        ReasoningResponse importFix = buildDeterministicImportFix(report, errorInfo, cumulativeResult, memory, testFile);
        if (importFix != null) {
            return importFix;
        }
        ReasoningResponse dependencyFix = buildDeterministicDependencyFix(report, memory);
        if (dependencyFix != null) {
            return dependencyFix;
        }
        ReasoningResponse mockFix = buildDeterministicMockFix(errorInfo, report, memory, testFile, iterationContext);
        if (mockFix != null) {
            return mockFix;
        }
        ReasoningResponse methodAndMockContext = buildMethodAndMockContextFallback(report, memory, testFile, iterationContext);
        if (methodAndMockContext != null) {
            return methodAndMockContext;
        }
        return buildContextFallback(report, errorInfo, memory, testFile);
    }

    private ReasoningResponse buildContextFallback(CompilationErrorReport report,
                                                   CompilationErrorInfo errorInfo,
                                                   ReasoningMemory memory,
                                                   Path testFile) {
        ReasoningResponse fallback = new ReasoningResponse();
        if (memory.getContextRequestBudgetRemaining() <= 0) {
            fallback.setDecision("STOP");
            fallback.setActions(List.of());
            return fallback;
        }
        String symbol = extractMissingSymbol(report, errorInfo, memory);
        List<ReasoningResponse.ReasoningAction> actions = new ArrayList<>();
        actions.add(createAction(ToolActionType.SHOW_IMPORTS, Map.of("path", testFile.toString())));
        if (symbol != null && !symbol.isBlank()) {
            actions.add(createAction(ToolActionType.SEARCH_SYMBOL, Map.of("symbol", symbol)));
        } else {
            actions.add(createAction(ToolActionType.SHOW_FILE, Map.of("path", testFile.toString())));
        }
        fallback.setDecision("REQUEST_CONTEXT");
        fallback.setActions(actions);
        return fallback;
    }

    private ReasoningResponse buildDeterministicImportFix(CompilationErrorReport report,
                                                          CompilationErrorInfo errorInfo,
                                                          ActionExecutionResult cumulativeResult,
                                                          ReasoningMemory memory,
                                                          Path testFile) {
        if (report == null || !report.has(CompilationErrorClass.MISSING_IMPORT_OR_SYMBOL)) {
            return null;
        }
        String missingSymbol = extractMissingSymbol(report, errorInfo, memory);
        if (missingSymbol == null || missingSymbol.isBlank()) {
            return null;
        }
        String resolvedCandidate = resolveUniqueCandidateForSymbol(missingSymbol, cumulativeResult);
        if (resolvedCandidate == null || resolvedCandidate.isBlank()) {
            return null;
        }
        Set<String> knownImports = extractKnownImports(cumulativeResult);
        if (knownImports.contains(resolvedCandidate)) {
            return null;
        }
        Map<String, Object> args = Map.of(
                "path", testFile.toString(),
                "import", resolvedCandidate
        );
        String fingerprint = fingerprint(ToolActionType.ADD_IMPORT, args);
        if (memory.isFixFingerprintBlocked(fingerprint)) {
            return null;
        }
        ReasoningResponse response = new ReasoningResponse();
        response.setHypothesis("Unique symbol resolution indicates a missing import in generated test.");
        ReasoningResponse.ExpectedDelta expectedDelta = new ReasoningResponse.ExpectedDelta();
        expectedDelta.setCompileErrors(-1);
        expectedDelta.setSymbol(missingSymbol);
        response.setExpectedDelta(expectedDelta);
        response.setDecision("APPLY_FIX");
        response.setActions(List.of(createAction(ToolActionType.ADD_IMPORT, args, List.of("symbol_resolved_unique", "test_file_targeted"))));
        return response;
    }

    private ReasoningResponse buildMethodAndMockContextFallback(CompilationErrorReport report,
                                                                ReasoningMemory memory,
                                                                Path testFile,
                                                                Map<String, Object> iterationContext) {
        if (report == null || memory.getContextRequestBudgetRemaining() <= 0) {
            return null;
        }
        boolean methodLikeError = report.has(CompilationErrorClass.METHOD_SIGNATURE_MISMATCH)
                || report.has(CompilationErrorClass.TYPE_MISMATCH)
                || report.has(CompilationErrorClass.ACCESS_VIOLATION);
        if (!methodLikeError) {
            return null;
        }
        List<ReasoningResponse.ReasoningAction> actions = new ArrayList<>();
        if (memory.getNoProgressStreak() <= 1) {
            actions.add(createAction(ToolActionType.SHOW_FILE,
                    Map.of("path", testFile.toString()),
                    List.of("inspect_current_test_source")));
            actions.add(createAction(ToolActionType.SHOW_IMPORTS,
                    Map.of("path", testFile.toString()),
                    List.of("inspect_current_imports")));
        }

        Map<String, Object> repairTargetContext = asMap(iterationContext == null ? null : iterationContext.get("repairTargetContext"));
        String targetClass = extractTargetClassName(repairTargetContext);
        String targetMethod = extractTargetMethodName(repairTargetContext);
        String targetSimpleClass = simpleClassName(targetClass);
        if (targetClass != null && targetMethod != null) {
            if (!hasCachedMethodContext(memory, targetSimpleClass, targetMethod)) {
                actions.add(createAction(ToolActionType.READ_METHOD,
                        Map.of("className", targetClass, "methodName", targetMethod),
                        List.of("method_signature_mismatch")));
            }
            if (!hasCachedMethodsList(memory, targetSimpleClass)) {
                actions.add(createAction(ToolActionType.LIST_METHODS,
                        Map.of("className", targetClass),
                        List.of("list_available_overloads")));
            }
        } else if (targetClass != null) {
            if (!hasCachedMethodsList(memory, targetSimpleClass)) {
                actions.add(createAction(ToolActionType.LIST_METHODS,
                        Map.of("className", targetClass),
                        List.of("class_signature_context")));
            }
        }

        for (String collaborator : extractCollaboratorClasses(repairTargetContext, 2)) {
            String collaboratorSimple = simpleClassName(collaborator);
            if (!hasCachedClassContext(memory, collaboratorSimple)) {
                actions.add(createAction(ToolActionType.READ_CLASS,
                        Map.of("className", collaborator),
                        List.of("mock_collaborator_alignment")));
            }
        }

        List<ReasoningResponse.ReasoningAction> deduped = dedupeActions(actions);
        if (deduped.isEmpty()) {
            return null;
        }
        ReasoningResponse response = new ReasoningResponse();
        response.setDecision("REQUEST_CONTEXT");
        response.setActions(deduped);
        return response;
    }

    private ReasoningResponse buildDeterministicDependencyFix(CompilationErrorReport report,
                                                              ReasoningMemory memory) {
        if (report == null || !report.has(CompilationErrorClass.MISSING_DEPENDENCY_OR_PACKAGE)) {
            return null;
        }
        String missingPackage = extractMissingPackage(report);
        if (missingPackage == null || missingPackage.isBlank()) {
            return null;
        }
        String dependency = resolveDependencyForPackage(missingPackage);
        if (dependency == null || dependency.isBlank()) {
            return null;
        }
        Map<String, Object> args = Map.of("dependency", dependency);
        String fingerprint = fingerprint(ToolActionType.ADD_DEPENDENCY, args);
        if (memory.isFixFingerprintBlocked(fingerprint)) {
            return null;
        }
        ReasoningResponse response = new ReasoningResponse();
        response.setHypothesis("Missing package indicates absent test dependency.");
        ReasoningResponse.ExpectedDelta expectedDelta = new ReasoningResponse.ExpectedDelta();
        expectedDelta.setCompileErrors(-1);
        expectedDelta.setSymbol(missingPackage);
        response.setExpectedDelta(expectedDelta);
        response.setDecision("APPLY_FIX");
        response.setActions(List.of(createAction(ToolActionType.ADD_DEPENDENCY,
                args,
                List.of("dependency_missing_package"))));
        return response;
    }

    private ReasoningResponse buildDeterministicMockFix(CompilationErrorInfo errorInfo,
                                                        CompilationErrorReport report,
                                                        ReasoningMemory memory,
                                                        Path testFile,
                                                        Map<String, Object> iterationContext) {
        boolean executeStage = iterationContext != null && iterationContext.containsKey("executeFailure");
        if (!executeStage) {
            return null;
        }
        Map<String, Object> repairTargetContext = asMap(iterationContext == null ? null : iterationContext.get("repairTargetContext"));
        Map<String, Object> mockPlan = asMap(repairTargetContext.get("mockPlan"));
        if (!isLikelyMockFailure(errorInfo)) {
            return null;
        }
        Map<String, Object> analysisContext = asMap(repairTargetContext.get("analysisContext"));
        Map<String, Object> testTargetContext = asMap(analysisContext.get("testTargetContext"));
        String targetClass = asString(testTargetContext.get("className"));
        String targetIdentifier = asString(testTargetContext.get("instanceName"));
        List<Map<String, Object>> normalizedTargets = normalizeMockTargets(mockPlan.get("targets"));
        if (normalizedTargets.isEmpty()) {
            normalizedTargets = deriveMockTargetsFromDependencies(repairTargetContext, targetClass);
        }
        if (normalizedTargets.isEmpty()) {
            return null;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", testFile.toString());
        if (targetClass != null) {
            args.put("targetClass", targetClass);
        }
        if (targetIdentifier != null) {
            args.put("targetIdentifier", targetIdentifier);
        }
        args.put("mockTargets", normalizedTargets);
        List<Map<String, Object>> mockStubs = deriveMockStubs(repairTargetContext, normalizedTargets);
        if (!mockStubs.isEmpty()) {
            args.put("mockStubs", mockStubs);
        }
        String strategy = asString(mockPlan.get("strategy"));
        args.put("strategy", strategy == null ? "MOCKITO" : strategy);
        String fingerprint = fingerprint(ToolActionType.ALIGN_MOCKS, args);
        if (memory.isFixFingerprintBlocked(fingerprint)) {
            return null;
        }
        ReasoningResponse response = new ReasoningResponse();
        response.setHypothesis("Execution failure indicates missing or misaligned Mockito scaffold.");
        ReasoningResponse.ExpectedDelta expectedDelta = new ReasoningResponse.ExpectedDelta();
        expectedDelta.setCompileErrors(-1);
        expectedDelta.setSymbol("ALIGN_MOCKS");
        response.setExpectedDelta(expectedDelta);
        response.setDecision("APPLY_FIX");
        response.setActions(List.of(createAction(ToolActionType.ALIGN_MOCKS,
                args,
                List.of("mock_collaborator_alignment", "test_file_targeted"))));
        return response;
    }

    private boolean isImportActionAllowed(Map<String, Object> args,
                                          CompilationErrorReport report,
                                          CompilationErrorInfo errorInfo,
                                          ActionExecutionResult cumulativeResult,
                                          ReasoningMemory memory) {
        String importValue = asString(args.get("import"));
        if (importValue == null || !importValue.contains(".")) {
            return false;
        }
        if (report == null || !report.has(CompilationErrorClass.MISSING_IMPORT_OR_SYMBOL)) {
            return false;
        }
        String simpleName = importValue.substring(importValue.lastIndexOf('.') + 1);
        Set<String> missingSymbols = new LinkedHashSet<>(report.getTopMissingSymbols());
        if (memory.getKnownMissingSymbols() != null) {
            missingSymbols.addAll(memory.getKnownMissingSymbols());
        }
        Set<String> resolvedCandidates = extractResolvedCandidates(cumulativeResult);
        boolean fromKnownMissing = missingSymbols.stream().anyMatch(symbol -> simpleName.equals(symbol));
        boolean fromResolvedSearch = resolvedCandidates.contains(importValue);
        boolean fromPrimaryMessage = errorInfo != null
                && errorInfo.getPrimaryMessage() != null
                && errorInfo.getPrimaryMessage().contains(simpleName);
        return fromKnownMissing || fromResolvedSearch || fromPrimaryMessage;
    }

    private boolean isDependencyActionAllowed(Map<String, Object> args, CompilationErrorReport report) {
        String dependency = asString(args.get("dependency"));
        if (dependency == null || !DEPENDENCY_PATTERN.matcher(dependency).matches()) {
            return false;
        }
        return report != null && report.has(CompilationErrorClass.MISSING_DEPENDENCY_OR_PACKAGE);
    }

    private boolean isPatchActionAllowed(Map<String, Object> args) {
        String patch = asString(args.get("patch"));
        return patch != null && patch.contains("@@");
    }

    private Set<String> extractResolvedCandidates(ActionExecutionResult cumulativeResult) {
        if (cumulativeResult == null || cumulativeResult.getInformation().isEmpty()) {
            return Set.of();
        }
        Object payload = cumulativeResult.getInformation().get("symbolSearchResults");
        if (!(payload instanceof List<?> entries)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            String status = asString(map.get("searchStatus"));
            if (!"FOUND_ONE".equalsIgnoreCase(status)) {
                continue;
            }
            Object candidatesValue = map.get("candidates");
            if (!(candidatesValue instanceof List<?> candidates) || candidates.size() != 1) {
                continue;
            }
            Object candidate = candidates.get(0);
            if (candidate != null && !candidate.toString().isBlank()) {
                result.add(candidate.toString().trim());
            }
        }
        return Set.copyOf(result);
    }

    private String resolveUniqueCandidateForSymbol(String symbol, ActionExecutionResult cumulativeResult) {
        if (cumulativeResult == null || cumulativeResult.getInformation().isEmpty()) {
            return null;
        }
        Object payload = cumulativeResult.getInformation().get("symbolSearchResults");
        if (!(payload instanceof List<?> entries)) {
            return null;
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            String status = asString(map.get("searchStatus"));
            if (!"FOUND_ONE".equalsIgnoreCase(status)) {
                continue;
            }
            String resolvedSymbol = asString(map.get("symbol"));
            if (resolvedSymbol != null && !resolvedSymbol.equals(symbol)) {
                continue;
            }
            Object candidatesValue = map.get("candidates");
            if (!(candidatesValue instanceof List<?> candidates) || candidates.size() != 1) {
                continue;
            }
            Object candidate = candidates.get(0);
            if (candidate == null) {
                continue;
            }
            String candidateText = candidate.toString().trim();
            if (candidateText.isBlank() || !candidateText.contains(".")) {
                continue;
            }
            String simple = candidateText.substring(candidateText.lastIndexOf('.') + 1);
            if (!simple.equals(symbol)) {
                continue;
            }
            return candidateText;
        }
        return null;
    }

    private Set<String> extractKnownImports(ActionExecutionResult cumulativeResult) {
        if (cumulativeResult == null || cumulativeResult.getInformation().isEmpty()) {
            return Set.of();
        }
        Object payload = cumulativeResult.getInformation().get("imports");
        if (!(payload instanceof List<?> entries)) {
            return Set.of();
        }
        Set<String> imports = new LinkedHashSet<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Object importsValue = map.get("imports");
            if (!(importsValue instanceof List<?> list)) {
                continue;
            }
            for (Object value : list) {
                if (value == null) {
                    continue;
                }
                String text = value.toString().trim();
                if (text.startsWith("import ")) {
                    text = text.substring("import ".length()).trim();
                }
                if (text.endsWith(";")) {
                    text = text.substring(0, text.length() - 1).trim();
                }
                if (!text.isBlank()) {
                    imports.add(text);
                }
            }
        }
        return Set.copyOf(imports);
    }

    private String extractMissingSymbol(CompilationErrorReport report,
                                        CompilationErrorInfo errorInfo,
                                        ReasoningMemory memory) {
        if (report != null && report.getTopMissingSymbols() != null && !report.getTopMissingSymbols().isEmpty()) {
            return report.getTopMissingSymbols().get(0);
        }
        if (memory != null && !memory.getKnownMissingSymbols().isEmpty()) {
            return memory.getKnownMissingSymbols().iterator().next();
        }
        if (errorInfo != null && errorInfo.getPrimaryMessage() != null) {
            String message = errorInfo.getPrimaryMessage();
            String[] parts = message.split("\\s+");
            for (String part : parts) {
                String token = part.replaceAll("[^A-Za-z0-9_]", "");
                if (!token.isBlank()
                        && Character.isUpperCase(token.charAt(0))
                        && token.length() > 2) {
                    return token;
                }
            }
        }
        return null;
    }

    private boolean requiresArguments(ToolActionType type) {
        return type == ToolActionType.SHOW_FILE
                || type == ToolActionType.SHOW_IMPORTS
                || type == ToolActionType.SEARCH_SYMBOL
                || type == ToolActionType.READ_CLASS
                || type == ToolActionType.READ_METHOD
                || type == ToolActionType.LIST_METHODS;
    }

    private List<ReasoningResponse.ReasoningAction> safeActions(ReasoningResponse response) {
        if (response == null || response.getActions() == null) {
            return List.of();
        }
        return response.getActions();
    }

    private ReasoningResponse.ReasoningAction createAction(ToolActionType type, Map<String, Object> args) {
        return createAction(type, args, List.of("policy_validated"));
    }

    private ReasoningResponse.ReasoningAction createAction(ToolActionType type,
                                                           Map<String, Object> args,
                                                           List<String> preconditions) {
        ReasoningResponse.ReasoningAction action = new ReasoningResponse.ReasoningAction();
        action.setType(type.name());
        action.setPreconditions(preconditions == null ? List.of("policy_validated") : preconditions);
        action.setArgs(args == null ? Map.of() : new LinkedHashMap<>(args));
        return action;
    }

    private Map<String, Object> canonicalArgs(ToolActionType type, Map<String, Object> args, Path testFile) {
        Map<String, Object> normalized = args == null ? Map.of() : ArgumentNormalizer.normalize(args);
        Map<String, Object> canonical = new LinkedHashMap<>();
        if (type == ToolActionType.ADD_IMPORT) {
            String importValue = asString(normalized.get("import"));
            if (importValue != null) {
                canonical.put("import", importValue.trim());
            }
            canonical.put("path", asStringOrDefault(normalized.get("path"), testFile.toString()));
            return canonical;
        }
        if (type == ToolActionType.ADD_DEPENDENCY) {
            String dependency = asString(normalized.get("dependency"));
            if (dependency != null) {
                canonical.put("dependency", dependency.trim());
            }
            return canonical;
        }
        if (type == ToolActionType.ALIGN_MOCKS) {
            canonical.put("path", asStringOrDefault(normalized.get("path"), testFile.toString()));
            String targetClass = asString(normalized.get("targetClass"));
            if (targetClass != null) {
                canonical.put("targetClass", targetClass);
            }
            String targetIdentifier = asString(normalized.get("targetIdentifier"));
            if (targetIdentifier != null) {
                canonical.put("targetIdentifier", targetIdentifier);
            }
            Object mockTargets = normalized.get("mockTargets");
            if (mockTargets instanceof List<?>) {
                canonical.put("mockTargets", mockTargets);
            }
            Object mockStubs = normalized.get("mockStubs");
            if (mockStubs instanceof List<?>) {
                canonical.put("mockStubs", mockStubs);
            }
            String strategy = asString(normalized.get("strategy"));
            if (strategy != null) {
                canonical.put("strategy", strategy);
            }
            return canonical;
        }
        if (type == ToolActionType.APPLY_PATCH) {
            String patch = asString(normalized.get("patch"));
            if (patch != null) {
                canonical.put("patch", patch);
            }
            canonical.put("path", asStringOrDefault(normalized.get("path"), testFile.toString()));
            return canonical;
        }
        if (type == ToolActionType.SHOW_FILE || type == ToolActionType.SHOW_IMPORTS) {
            canonical.put("path", asStringOrDefault(normalized.get("path"), testFile.toString()));
            return canonical;
        }
        if (type == ToolActionType.SEARCH_SYMBOL || type == ToolActionType.MARK_FALSE_DEPENDENCY) {
            String symbol = asString(normalized.get("symbol"));
            if (symbol != null) {
                canonical.put("symbol", symbol.trim());
            }
            return canonical;
        }
        if (type == ToolActionType.READ_CLASS || type == ToolActionType.LIST_METHODS) {
            String className = asString(normalized.get("className"));
            if (className != null) {
                canonical.put("className", className.trim());
            }
            return canonical;
        }
        if (type == ToolActionType.READ_METHOD) {
            String className = asString(normalized.get("className"));
            String methodName = asString(normalized.get("methodName"));
            if (className != null) {
                canonical.put("className", className.trim());
            }
            if (methodName != null) {
                canonical.put("methodName", methodName.trim());
            }
            return canonical;
        }
        return Map.of();
    }

    private String fingerprint(ToolActionType type, Map<String, Object> args) {
        Map<String, String> ordered = new TreeMap<>();
        args.forEach((key, value) -> {
            if (key != null && value != null) {
                ordered.put(key, value.toString());
            }
        });
        StringBuilder builder = new StringBuilder(type.name());
        ordered.forEach((key, value) -> builder.append("|").append(key).append("=").append(value));
        return builder.toString();
    }

    private ToolActionType parseType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        try {
            return ToolActionType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String normalizeDecision(String decision) {
        if (decision == null || decision.isBlank()) {
            return "STOP";
        }
        return decision.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }

    private String asStringOrDefault(Object value, String defaultValue) {
        String resolved = asString(value);
        return resolved == null ? defaultValue : resolved;
    }

    private Boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        return null;
    }

    private String extractMissingPackage(CompilationErrorReport report) {
        if (report == null || report.getTopMissingPackages() == null || report.getTopMissingPackages().isEmpty()) {
            return null;
        }
        return report.getTopMissingPackages().get(0);
    }

    private String resolveDependencyForPackage(String missingPackage) {
        if (missingPackage == null || missingPackage.isBlank()) {
            return null;
        }
        String normalized = missingPackage.trim();
        String bestPrefix = null;
        for (String prefix : PACKAGE_DEPENDENCY_HINTS.keySet()) {
            if (normalized.startsWith(prefix) && (bestPrefix == null || prefix.length() > bestPrefix.length())) {
                bestPrefix = prefix;
            }
        }
        return bestPrefix == null ? null : PACKAGE_DEPENDENCY_HINTS.get(bestPrefix);
    }

    private boolean isLikelyMockFailure(CompilationErrorInfo errorInfo) {
        if (errorInfo == null) {
            return false;
        }
        String output = (errorInfo.getCompilerOutput() == null ? "" : errorInfo.getCompilerOutput())
                + " "
                + (errorInfo.getPrimaryMessage() == null ? "" : errorInfo.getPrimaryMessage());
        String lower = output.toLowerCase(Locale.ROOT);
        return lower.contains("nullpointerexception")
                || lower.contains("cannot invoke")
                || lower.contains("wanted but not invoked")
                || lower.contains("notamockexception")
                || lower.contains("potentialstubbingproblem")
                || lower.contains("unnecessarystubbingexception");
    }

    private List<Map<String, Object>> normalizeMockTargets(Object rawTargets) {
        if (!(rawTargets instanceof List<?> targets) || targets.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, Map<String, Object>> normalized = new LinkedHashMap<>();
        for (Object target : targets) {
            Map<String, Object> map = asMap(target);
            String qualifiedType = asString(map.get("qualifiedType"));
            if (qualifiedType == null) {
                qualifiedType = asString(map.get("className"));
            }
            String identifier = asString(map.get("identifier"));
            if (identifier == null) {
                identifier = asString(map.get("variableName"));
            }
            if (qualifiedType == null || identifier == null || !isMeaningfulTypeName(qualifiedType)) {
                continue;
            }
            normalized.putIfAbsent(identifier, Map.of("qualifiedType", qualifiedType, "identifier", identifier));
        }
        return List.copyOf(normalized.values());
    }

    private List<Map<String, Object>> deriveMockTargetsFromDependencies(Map<String, Object> repairTargetContext,
                                                                        String targetClass) {
        Map<String, Object> testedMethod = asMap(repairTargetContext.get("testedMethod"));
        Object dependenciesValue = testedMethod.get("dependencies");
        if (!(dependenciesValue instanceof List<?> dependencies) || dependencies.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, Map<String, Object>> derived = new LinkedHashMap<>();
        String targetSimple = simpleClassName(targetClass);
        for (Object dependency : dependencies) {
            Map<String, Object> map = asMap(dependency);
            String className = asString(map.get("className"));
            if (!isMeaningfulTypeName(className)) {
                continue;
            }
            String classSimple = simpleClassName(className);
            if (targetSimple != null && !targetSimple.isBlank() && targetSimple.equals(classSimple)) {
                continue;
            }
            Boolean external = asBoolean(map.get("externalDependency"));
            if (external != null && !external) {
                continue;
            }
            String variableName = asString(map.get("variableName"));
            String identifier = variableName == null ? deriveIdentifier(classSimple) : variableName;
            if (identifier == null || identifier.isBlank()) {
                continue;
            }
            derived.putIfAbsent(identifier, Map.of(
                    "qualifiedType", className,
                    "identifier", identifier
            ));
        }
        return List.copyOf(derived.values());
    }

    private List<Map<String, Object>> deriveMockStubs(Map<String, Object> repairTargetContext,
                                                      List<Map<String, Object>> mockTargets) {
        if (mockTargets == null || mockTargets.isEmpty()) {
            return List.of();
        }
        Map<String, Object> testedMethod = asMap(repairTargetContext.get("testedMethod"));
        Object invocationsValue = testedMethod.get("invocations");
        if (!(invocationsValue instanceof List<?> invocations) || invocations.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, String> knownIdentifiers = new LinkedHashMap<>();
        for (Map<String, Object> target : mockTargets) {
            String identifier = asString(target.get("identifier"));
            if (identifier != null) {
                knownIdentifiers.putIfAbsent(identifier.toLowerCase(Locale.ROOT), identifier);
            }
        }
        if (knownIdentifiers.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, Map<String, Object>> stubs = new LinkedHashMap<>();
        for (Object invocation : invocations) {
            Map<String, Object> map = asMap(invocation);
            String methodName = asString(map.get("methodName"));
            if (methodName == null || methodName.isBlank()) {
                continue;
            }
            String targetIdentifier = extractInvocationTargetIdentifier(asString(map.get("target")));
            if (targetIdentifier == null) {
                continue;
            }
            String resolvedIdentifier = knownIdentifiers.get(targetIdentifier.toLowerCase(Locale.ROOT));
            if (resolvedIdentifier == null) {
                continue;
            }
            List<String> argTypes = normalizeArgTypes(map.get("argTypes"));
            String key = resolvedIdentifier + "|" + methodName + "|" + argTypes.size();
            stubs.putIfAbsent(key, Map.of(
                    "identifier", resolvedIdentifier,
                    "methodName", methodName,
                    "argTypes", argTypes
            ));
        }
        return List.copyOf(stubs.values());
    }

    private String extractInvocationTargetIdentifier(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        String normalized = target.trim();
        if (normalized.startsWith("this.")) {
            normalized = normalized.substring("this.".length());
        }
        int dot = normalized.indexOf('.');
        if (dot > 0) {
            normalized = normalized.substring(0, dot);
        }
        int paren = normalized.indexOf('(');
        if (paren > 0) {
            normalized = normalized.substring(0, paren);
        }
        if (!normalized.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return null;
        }
        return normalized;
    }

    private List<String> normalizeArgTypes(Object rawArgTypes) {
        if (!(rawArgTypes instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        List<String> argTypes = new ArrayList<>();
        for (Object value : values) {
            String type = asString(value);
            if (type != null) {
                argTypes.add(type);
            }
        }
        return List.copyOf(argTypes);
    }

    private String simpleClassName(String className) {
        if (className == null || className.isBlank()) {
            return "";
        }
        int idx = className.lastIndexOf('.');
        return idx < 0 ? className : className.substring(idx + 1);
    }

    private String deriveIdentifier(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        if (type.length() == 1) {
            return type.toLowerCase(Locale.ROOT);
        }
        return type.substring(0, 1).toLowerCase(Locale.ROOT) + type.substring(1);
    }

    private boolean isMeaningfulTypeName(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return false;
        }
        String trimmed = typeName.trim();
        String simple = simpleClassName(trimmed);
        if (simple.isBlank()) {
            return false;
        }
        String lower = simple.toLowerCase(Locale.ROOT);
        if (lower.equals("string")
                || lower.equals("int")
                || lower.equals("long")
                || lower.equals("short")
                || lower.equals("byte")
                || lower.equals("double")
                || lower.equals("float")
                || lower.equals("boolean")
                || lower.equals("char")
                || lower.equals("void")) {
            return false;
        }
        if (trimmed.startsWith("java.") || trimmed.startsWith("javax.")) {
            return false;
        }
        return true;
    }

    private boolean hasCachedMethodContext(ReasoningMemory memory, String simpleClassName, String methodName) {
        if (memory == null || methodName == null || methodName.isBlank()) {
            return false;
        }
        return hasCachedEntry(memory, key -> {
            String lower = key.toLowerCase(Locale.ROOT);
            boolean classMatches = simpleClassName == null || simpleClassName.isBlank()
                    || lower.contains(simpleClassName.toLowerCase(Locale.ROOT) + ".java");
            return classMatches && key.endsWith("#" + methodName);
        });
    }

    private boolean hasCachedMethodsList(ReasoningMemory memory, String simpleClassName) {
        if (memory == null) {
            return false;
        }
        return hasCachedEntry(memory, key -> {
            String lower = key.toLowerCase(Locale.ROOT);
            boolean classMatches = simpleClassName == null || simpleClassName.isBlank()
                    || lower.contains(simpleClassName.toLowerCase(Locale.ROOT) + ".java");
            return classMatches && key.endsWith("#methods");
        });
    }

    private boolean hasCachedClassContext(ReasoningMemory memory, String simpleClassName) {
        if (memory == null || simpleClassName == null || simpleClassName.isBlank()) {
            return false;
        }
        return hasCachedEntry(memory, key -> key.toLowerCase(Locale.ROOT)
                .endsWith(simpleClassName.toLowerCase(Locale.ROOT) + ".java"));
    }

    private boolean hasCachedEntry(ReasoningMemory memory, Predicate<String> predicate) {
        if (memory == null || predicate == null || memory.getContextCache().isEmpty()) {
            return false;
        }
        for (String key : memory.getContextCache().keySet()) {
            if (key != null && predicate.test(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean arePreconditionsSatisfied(List<String> preconditions,
                                              ToolActionType type,
                                              Map<String, Object> args,
                                              CompilationErrorReport report,
                                              ActionExecutionResult cumulativeResult,
                                              Path testFile) {
        if (preconditions == null || preconditions.isEmpty()) {
            return false;
        }
        for (String raw : preconditions) {
            String condition = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (condition.isBlank()) {
                return false;
            }
            if ("policy_validated".equals(condition)) {
                continue;
            }
            if ("symbol_resolved_unique".equals(condition)) {
                String importValue = asString(args.get("import"));
                if (importValue == null || !extractResolvedCandidates(cumulativeResult).contains(importValue)) {
                    return false;
                }
                continue;
            }
            if ("dependency_missing_package".equals(condition)) {
                if (report == null || !report.has(CompilationErrorClass.MISSING_DEPENDENCY_OR_PACKAGE)) {
                    return false;
                }
                continue;
            }
            if ("patch_applies_cleanly".equals(condition)) {
                if (type != ToolActionType.APPLY_PATCH || !isPatchActionAllowed(args)) {
                    return false;
                }
                continue;
            }
            if ("mock_collaborator_alignment".equals(condition)) {
                if (type == ToolActionType.ALIGN_MOCKS) {
                    Object targets = args.get("mockTargets");
                    if (!(targets instanceof List<?> list) || list.isEmpty()) {
                        return false;
                    }
                }
                continue;
            }
            if ("test_file_targeted".equals(condition)) {
                String path = asString(args.get("path"));
                if (path == null || !path.equals(testFile.toString())) {
                    return false;
                }
                continue;
            }
            if ("method_signature_mismatch".equals(condition)) {
                if (report == null
                        || !(report.has(CompilationErrorClass.METHOD_SIGNATURE_MISMATCH)
                        || report.has(CompilationErrorClass.TYPE_MISMATCH)
                        || report.has(CompilationErrorClass.ACCESS_VIOLATION))) {
                    return false;
                }
                continue;
            }
            if ("list_available_overloads".equals(condition)
                    || "class_signature_context".equals(condition)
                    || "inspect_current_test_source".equals(condition)
                    || "inspect_current_imports".equals(condition)) {
                continue;
            }
            return false;
        }
        return true;
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null) {
                    converted.put(k.toString(), v);
                }
            });
            return converted;
        }
        return Map.of();
    }

    private String extractTargetClassName(Map<String, Object> repairTargetContext) {
        Map<String, Object> analysisContext = asMap(repairTargetContext.get("analysisContext"));
        Map<String, Object> testTargetContext = asMap(analysisContext.get("testTargetContext"));
        return asString(testTargetContext.get("className"));
    }

    private String extractTargetMethodName(Map<String, Object> repairTargetContext) {
        Map<String, Object> testedMethod = asMap(repairTargetContext.get("testedMethod"));
        return asString(testedMethod.get("name"));
    }

    private List<String> extractCollaboratorClasses(Map<String, Object> repairTargetContext, int limit) {
        LinkedHashSet<String> collaborators = new LinkedHashSet<>();
        Map<String, Object> mockPlan = asMap(repairTargetContext.get("mockPlan"));
        Object targetsValue = mockPlan.get("targets");
        if (targetsValue instanceof List<?> targets) {
            for (Object target : targets) {
                Map<String, Object> map = asMap(target);
                String qualifiedType = asString(map.get("qualifiedType"));
                if (qualifiedType != null && qualifiedType.contains(".")) {
                    collaborators.add(qualifiedType);
                }
                if (collaborators.size() >= Math.max(limit, 1)) {
                    break;
                }
            }
        }
        if (collaborators.size() < Math.max(limit, 1)) {
            Map<String, Object> testedMethod = asMap(repairTargetContext.get("testedMethod"));
            Object dependenciesValue = testedMethod.get("dependencies");
            if (dependenciesValue instanceof List<?> dependencies) {
                for (Object dep : dependencies) {
                    Map<String, Object> map = asMap(dep);
                    String className = asString(map.get("className"));
                    if (className != null && className.contains(".")) {
                        collaborators.add(className);
                    }
                    if (collaborators.size() >= Math.max(limit, 1)) {
                        break;
                    }
                }
            }
        }
        return List.copyOf(collaborators);
    }

    private List<ReasoningResponse.ReasoningAction> dedupeActions(List<ReasoningResponse.ReasoningAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, ReasoningResponse.ReasoningAction> deduped = new LinkedHashMap<>();
        for (ReasoningResponse.ReasoningAction action : actions) {
            if (action == null) {
                continue;
            }
            ToolActionType type = parseType(action.getType());
            if (type == null) {
                continue;
            }
            Map<String, Object> args = action.getArgs() == null ? Map.of() : action.getArgs();
            String key = fingerprint(type, args);
            deduped.putIfAbsent(key, action);
        }
        return List.copyOf(deduped.values());
    }
}
