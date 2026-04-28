package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.reasoning.model.ActionExecutionResult;
import com.gigachat.unit.tests.generator.reasoning.model.CompilationErrorInfoBuilder;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

final class ToolRunActionExecutor {

    private final CompilerInvoker compilerInvoker;
    private final ExecutionInvoker executionInvoker;
    private final Path projectRoot;
    private final Path testFile;
    private final String testFileFqcn;
    private final String methodName;

    ToolRunActionExecutor(CompilerInvoker compilerInvoker,
                          ExecutionInvoker executionInvoker,
                          Path projectRoot,
                          Path testFile,
                          String testFileFqcn,
                          String methodName) {
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.executionInvoker = executionInvoker;
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.testFile = Objects.requireNonNull(testFile, "testFile");
        this.testFileFqcn = testFileFqcn;
        this.methodName = methodName;
    }

    ActionExecutionResult handleRecompile() {
        CompileResult result = compilerInvoker.compileWithoutCache(projectRoot, testFile, methodName);
        Map<String, Object> detail = new java.util.HashMap<>();
        detail.put("success", result.success());
        if (!result.success()) {
            detail.put("errorInfo", CompilationErrorInfoBuilder.from(result, testFile, testFileFqcn));
        }
        return new ActionExecutionResult(Map.of("compilationResult", detail));
    }

    ActionExecutionResult handleRunTests(Map<String, Object> args) {
        Map<String, Object> detail = new java.util.HashMap<>();
        if (executionInvoker == null) {
            detail.put("success", false);
            detail.put("stdout", "");
            detail.put("stderr", "Execution invoker is not configured");
            detail.put("stacktrace", "");
        } else {
            ExecuteResult executeResult = executionInvoker.execute(projectRoot, testFile, resolveExecutionMethodName(args));
            detail.put("success", executeResult.success());
            detail.put("stdout", executeResult.stdout());
            detail.put("stderr", executeResult.stderr());
            detail.put("stacktrace", executeResult.failedTests().stream().collect(Collectors.joining("\n")));
        }
        return new ActionExecutionResult(Map.of("testRuns", List.of(detail)));
    }

    private String resolveExecutionMethodName(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return methodName;
        }
        String requestedTestClass = requireString(args, "testClass");
        String requestedMethod = requireString(args, "methodName");
        if (requestedMethod == null) {
            requestedMethod = requireString(args, "testMethod");
        }
        if (matchesCurrentTestClass(requestedTestClass)) {
            return requestedMethod == null || requestedMethod.isBlank() ? "" : requestedMethod;
        }
        return methodName;
    }

    private boolean matchesCurrentTestClass(String requestedTestClass) {
        if (requestedTestClass == null || requestedTestClass.isBlank()) {
            return false;
        }
        String simpleCurrentName = testFile.getFileName().toString().replace(".java", "");
        return requestedTestClass.equals(testFileFqcn)
                || requestedTestClass.equals(simpleCurrentName)
                || (testFileFqcn != null && testFileFqcn.endsWith("." + requestedTestClass));
    }

    private String requireString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : value.toString();
    }
}
