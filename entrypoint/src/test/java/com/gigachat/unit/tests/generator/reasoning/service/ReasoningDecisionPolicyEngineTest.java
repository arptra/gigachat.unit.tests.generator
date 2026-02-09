package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.compile.classification.model.CompilationError;
import com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorClass;
import com.gigachat.unit.tests.generator.compile.classification.model.CompilationErrorReport;
import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfo;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningMemory;
import com.gigachat.unit.tests.generator.reasoning.model.ReasoningResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningDecisionPolicyEngineTest {

    private static final Path TEST_FILE = Path.of("src/test/java/example/GeneratedTest.java");

    @Test
    void shouldFallbackToContextWhenImportFixHasNoEvidence() {
        ReasoningDecisionPolicyEngine engine = new ReasoningDecisionPolicyEngine();
        ReasoningResponse raw = applyFix("ADD_IMPORT", Map.of("import", "com.example.UnknownType"), List.of("symbol_resolved_unique"));
        CompilationErrorReport report = missingSymbolReport("MissingType");
        CompilationErrorInfo errorInfo = new CompilationErrorInfo("out", "cannot find symbol MissingType", "example.GeneratedTest", TEST_FILE.toString(), 5, null);

        ReasoningResponse normalized = engine.normalize(
                raw,
                report,
                errorInfo,
                ActionExecutionResult.empty(),
                new ReasoningMemory(),
                TEST_FILE
        );

        assertEquals("REQUEST_CONTEXT", normalized.getDecision());
        assertEquals("SHOW_IMPORTS", normalized.getActions().get(0).getType());
        assertEquals("SEARCH_SYMBOL", normalized.getActions().get(1).getType());
    }

    @Test
    void shouldKeepAddImportWhenUniqueCandidateWasResolved() {
        ReasoningDecisionPolicyEngine engine = new ReasoningDecisionPolicyEngine();
        ReasoningResponse raw = applyFix("ADD_IMPORT", Map.of("import", "com.example.MissingType"), List.of("symbol_resolved_unique"));
        CompilationErrorReport report = missingSymbolReport("MissingType");
        CompilationErrorInfo errorInfo = new CompilationErrorInfo("out", "cannot find symbol MissingType", "example.GeneratedTest", TEST_FILE.toString(), 5, null);
        ActionExecutionResult executionResult = new ActionExecutionResult(Map.of(
                "symbolSearchResults", List.of(Map.of(
                        "searchStatus", "FOUND_ONE",
                        "symbol", "MissingType",
                        "candidates", List.of("com.example.MissingType"),
                        "source", "PROJECT_SOURCE"
                ))
        ));

        ReasoningResponse normalized = engine.normalize(
                raw,
                report,
                errorInfo,
                executionResult,
                new ReasoningMemory(),
                TEST_FILE
        );

        assertEquals("APPLY_FIX", normalized.getDecision());
        assertEquals("ADD_IMPORT", normalized.getActions().get(0).getType());
        assertEquals(TEST_FILE.toString(), normalized.getActions().get(0).getArgs().get("path"));
    }

    @Test
    void shouldFallbackWhenFingerprintIsBlocked() {
        ReasoningDecisionPolicyEngine engine = new ReasoningDecisionPolicyEngine();
        ReasoningResponse raw = applyFix("ADD_IMPORT", Map.of("import", "com.example.MissingType"), List.of("symbol_resolved_unique"));
        CompilationErrorReport report = missingSymbolReport("MissingType");
        CompilationErrorInfo errorInfo = new CompilationErrorInfo("out", "cannot find symbol MissingType", "example.GeneratedTest", TEST_FILE.toString(), 5, null);
        ActionExecutionResult executionResult = new ActionExecutionResult(Map.of(
                "symbolSearchResults", List.of(Map.of(
                        "searchStatus", "FOUND_ONE",
                        "symbol", "MissingType",
                        "candidates", List.of("com.example.MissingType"),
                        "source", "PROJECT_SOURCE"
                ))
        ));
        ReasoningMemory memory = new ReasoningMemory();
        memory.blockFixFingerprint("ADD_IMPORT|import=com.example.MissingType|path=" + TEST_FILE);

        ReasoningResponse normalized = engine.normalize(raw, report, errorInfo, executionResult, memory, TEST_FILE);

        assertEquals("REQUEST_CONTEXT", normalized.getDecision());
        assertEquals("SHOW_IMPORTS", normalized.getActions().get(0).getType());
    }

    @Test
    void shouldConvertStopToContextRequestWhenMissingSymbolStillUnresolved() {
        ReasoningDecisionPolicyEngine engine = new ReasoningDecisionPolicyEngine();
        ReasoningResponse raw = new ReasoningResponse();
        raw.setDecision("STOP");
        raw.setActions(List.of());

        ReasoningResponse normalized = engine.normalize(
                raw,
                missingSymbolReport("MissingType"),
                new CompilationErrorInfo("out", "cannot find symbol MissingType", "example.GeneratedTest", TEST_FILE.toString(), 5, null),
                ActionExecutionResult.empty(),
                new ReasoningMemory(),
                TEST_FILE
        );

        assertEquals("REQUEST_CONTEXT", normalized.getDecision());
        assertEquals("SEARCH_SYMBOL", normalized.getActions().get(1).getType());
    }

    @Test
    void shouldConvertStopToDeterministicImportFixWhenUniqueCandidateAlreadyKnown() {
        ReasoningDecisionPolicyEngine engine = new ReasoningDecisionPolicyEngine();
        ReasoningResponse raw = new ReasoningResponse();
        raw.setDecision("STOP");
        raw.setActions(List.of());
        ActionExecutionResult executionResult = new ActionExecutionResult(Map.of(
                "symbolSearchResults", List.of(Map.of(
                        "searchStatus", "FOUND_ONE",
                        "symbol", "MissingType",
                        "candidates", List.of("com.example.MissingType"),
                        "source", "PROJECT_SOURCE"
                )),
                "imports", List.of(Map.of(
                        "path", TEST_FILE.toString(),
                        "imports", List.of("import org.junit.jupiter.api.Test;")
                ))
        ));

        ReasoningResponse normalized = engine.normalize(
                raw,
                missingSymbolReport("MissingType"),
                new CompilationErrorInfo("out", "cannot find symbol MissingType", "example.GeneratedTest", TEST_FILE.toString(), 5, null),
                executionResult,
                new ReasoningMemory(),
                TEST_FILE
        );

        assertEquals("APPLY_FIX", normalized.getDecision());
        assertEquals("ADD_IMPORT", normalized.getActions().get(0).getType());
        assertEquals("com.example.MissingType", normalized.getActions().get(0).getArgs().get("import"));
    }

    @Test
    void shouldFallbackToMethodAndMockContextForSignatureMismatch() {
        ReasoningDecisionPolicyEngine engine = new ReasoningDecisionPolicyEngine();
        ReasoningResponse raw = new ReasoningResponse();
        raw.setDecision("STOP");
        raw.setActions(List.of());

        Map<String, Object> iterationContext = Map.of(
                "repairTargetContext", Map.of(
                        "testedMethod", Map.of("name", "processOrder"),
                        "analysisContext", Map.of(
                                "testTargetContext", Map.of("className", "com.example.service.OrderService")
                        ),
                        "mockPlan", Map.of(
                                "targets", List.of(
                                        Map.of("qualifiedType", "com.example.repository.OrderRepository")
                                )
                        )
                )
        );

        ReasoningResponse normalized = engine.normalize(
                raw,
                signatureMismatchReport("processOrder"),
                new CompilationErrorInfo("out", "method processOrder in class OrderService cannot be applied to given types", "example.GeneratedTest", TEST_FILE.toString(), 8, null),
                ActionExecutionResult.empty(),
                new ReasoningMemory(),
                TEST_FILE,
                iterationContext
        );

        assertEquals("REQUEST_CONTEXT", normalized.getDecision());
        List<String> actionTypes = normalized.getActions().stream().map(ReasoningResponse.ReasoningAction::getType).toList();
        assertTrue(actionTypes.contains("READ_METHOD"));
        assertTrue(actionTypes.contains("LIST_METHODS"));
        assertTrue(actionTypes.contains("READ_CLASS"));
    }

    @Test
    void shouldNotRepeatReadMethodWhenMethodContextAlreadyCached() {
        ReasoningDecisionPolicyEngine engine = new ReasoningDecisionPolicyEngine();
        ReasoningResponse raw = new ReasoningResponse();
        raw.setDecision("STOP");
        raw.setActions(List.of());

        Map<String, Object> iterationContext = Map.of(
                "repairTargetContext", Map.of(
                        "testedMethod", Map.of("name", "processOrder"),
                        "analysisContext", Map.of(
                                "testTargetContext", Map.of("className", "com.example.service.OrderService")
                        )
                )
        );

        ReasoningMemory memory = new ReasoningMemory();
        memory.addContextCacheEntry("/tmp/OrderService.java#processOrder", "void processOrder() {}");

        ReasoningResponse normalized = engine.normalize(
                raw,
                signatureMismatchReport("processOrder"),
                new CompilationErrorInfo("out", "method signature mismatch", "example.GeneratedTest", TEST_FILE.toString(), 8, null),
                ActionExecutionResult.empty(),
                memory,
                TEST_FILE,
                iterationContext
        );

        List<String> actionTypes = normalized.getActions().stream().map(ReasoningResponse.ReasoningAction::getType).toList();
        assertFalse(actionTypes.contains("READ_METHOD"));
        assertTrue(actionTypes.contains("LIST_METHODS"));
    }

    @Test
    void shouldConvertStopToDeterministicDependencyFixForMissingMockitoPackage() {
        ReasoningDecisionPolicyEngine engine = new ReasoningDecisionPolicyEngine();
        ReasoningResponse raw = new ReasoningResponse();
        raw.setDecision("STOP");
        raw.setActions(List.of());

        ReasoningResponse normalized = engine.normalize(
                raw,
                missingPackageReport("org.mockito.junit.jupiter"),
                new CompilationErrorInfo("out", "package org.mockito.junit.jupiter does not exist", "example.GeneratedTest", TEST_FILE.toString(), 5, null),
                ActionExecutionResult.empty(),
                new ReasoningMemory(),
                TEST_FILE
        );

        assertEquals("APPLY_FIX", normalized.getDecision());
        assertEquals("ADD_DEPENDENCY", normalized.getActions().get(0).getType());
        assertEquals("org.mockito:mockito-junit-jupiter:5.11.0", normalized.getActions().get(0).getArgs().get("dependency"));
    }

    @Test
    void shouldConvertExecuteStopToAlignMocksFix() {
        ReasoningDecisionPolicyEngine engine = new ReasoningDecisionPolicyEngine();
        ReasoningResponse raw = new ReasoningResponse();
        raw.setDecision("STOP");
        raw.setActions(List.of());

        Map<String, Object> iterationContext = Map.of(
                "executeFailure", Map.of("failedTests", List.of("example.GeneratedTest.shouldProcess")),
                "repairTargetContext", Map.of(
                        "analysisContext", Map.of(
                                "testTargetContext", Map.of(
                                        "className", "com.example.service.OrderService",
                                        "instanceName", "orderService"
                                )
                        ),
                        "mockPlan", Map.of(
                                "strategy", "MOCKITO",
                                "targets", List.of(
                                        Map.of("qualifiedType", "com.example.repository.OrderRepository", "identifier", "orderRepository")
                                )
                        )
                )
        );

        ReasoningResponse normalized = engine.normalize(
                raw,
                null,
                new CompilationErrorInfo("NullPointerException", "Cannot invoke because \"orderRepository\" is null", "example.GeneratedTest", TEST_FILE.toString(), 12, null),
                ActionExecutionResult.empty(),
                new ReasoningMemory(),
                TEST_FILE,
                iterationContext
        );

        assertEquals("APPLY_FIX", normalized.getDecision());
        assertEquals("ALIGN_MOCKS", normalized.getActions().get(0).getType());
        assertEquals(TEST_FILE.toString(), normalized.getActions().get(0).getArgs().get("path"));
    }

    @Test
    void shouldDeriveMockTargetsFromDependenciesWhenPlanTargetsMissing() {
        ReasoningDecisionPolicyEngine engine = new ReasoningDecisionPolicyEngine();
        ReasoningResponse raw = new ReasoningResponse();
        raw.setDecision("STOP");
        raw.setActions(List.of());

        Map<String, Object> iterationContext = Map.of(
                "executeFailure", Map.of("failedTests", List.of("example.GeneratedTest.shouldProcess")),
                "repairTargetContext", Map.of(
                        "analysisContext", Map.of(
                                "testTargetContext", Map.of(
                                        "className", "com.example.service.OrderService",
                                        "instanceName", "orderService"
                                )
                        ),
                        "testedMethod", Map.of(
                                "dependencies", List.of(
                                        Map.of(
                                                "className", "com.example.repository.OrderRepository",
                                                "variableName", "orderRepository",
                                                "externalDependency", true
                                        )
                                ),
                                "invocations", List.of(
                                        Map.of(
                                                "target", "orderRepository",
                                                "methodName", "save",
                                                "argTypes", List.of("com.example.domain.Order")
                                        )
                                )
                        ),
                        "mockPlan", Map.of(
                                "strategy", "MOCKITO",
                                "targets", List.of()
                        )
                )
        );

        ReasoningResponse normalized = engine.normalize(
                raw,
                null,
                new CompilationErrorInfo("NullPointerException", "Cannot invoke because \"orderRepository\" is null", "example.GeneratedTest", TEST_FILE.toString(), 12, null),
                ActionExecutionResult.empty(),
                new ReasoningMemory(),
                TEST_FILE,
                iterationContext
        );

        assertEquals("APPLY_FIX", normalized.getDecision());
        assertEquals("ALIGN_MOCKS", normalized.getActions().get(0).getType());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mockTargets = (List<Map<String, Object>>) normalized.getActions().get(0).getArgs().get("mockTargets");
        assertEquals(1, mockTargets.size());
        assertEquals("com.example.repository.OrderRepository", mockTargets.get(0).get("qualifiedType"));
        assertEquals("orderRepository", mockTargets.get(0).get("identifier"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mockStubs = (List<Map<String, Object>>) normalized.getActions().get(0).getArgs().get("mockStubs");
        assertEquals(1, mockStubs.size());
        assertEquals("orderRepository", mockStubs.get(0).get("identifier"));
        assertEquals("save", mockStubs.get(0).get("methodName"));
    }

    private ReasoningResponse applyFix(String type, Map<String, Object> args) {
        return applyFix(type, args, List.of("policy_validated"));
    }

    private ReasoningResponse applyFix(String type, Map<String, Object> args, List<String> preconditions) {
        ReasoningResponse.ReasoningAction action = new ReasoningResponse.ReasoningAction();
        action.setType(type);
        action.setArgs(args);
        action.setPreconditions(preconditions);
        ReasoningResponse response = new ReasoningResponse();
        response.setDecision("APPLY_FIX");
        response.setActions(List.of(action));
        return response;
    }

    private CompilationErrorReport missingSymbolReport(String symbol) {
        CompilationError error = CompilationError.builder()
                .rawMessage("cannot find symbol")
                .normalizedMessage("cannot find symbol")
                .symbol(symbol)
                .errorClass(CompilationErrorClass.MISSING_IMPORT_OR_SYMBOL)
                .build();
        return new CompilationErrorReport(List.of(error), "cannot find symbol");
    }

    private CompilationErrorReport signatureMismatchReport(String methodName) {
        CompilationError error = CompilationError.builder()
                .rawMessage("method signature mismatch")
                .normalizedMessage("method signature mismatch")
                .methodName(methodName)
                .errorClass(CompilationErrorClass.METHOD_SIGNATURE_MISMATCH)
                .build();
        return new CompilationErrorReport(List.of(error), "method signature mismatch");
    }

    private CompilationErrorReport missingPackageReport(String packageName) {
        CompilationError error = CompilationError.builder()
                .rawMessage("package does not exist")
                .normalizedMessage("package does not exist")
                .packageName(packageName)
                .errorClass(CompilationErrorClass.MISSING_DEPENDENCY_OR_PACKAGE)
                .build();
        return new CompilationErrorReport(List.of(error), "package does not exist");
    }
}
