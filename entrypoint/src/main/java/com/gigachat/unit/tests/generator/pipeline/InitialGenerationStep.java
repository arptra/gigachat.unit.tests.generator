package com.gigachat.unit.tests.generator.pipeline;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ExternalCollaboratorDetector;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.PipelineModuleConfig;
import com.gigachat.unit.tests.generator.config.ParallelMode;
import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.dto.CompileErrors;
import com.gigachat.unit.tests.generator.dto.ErrorsReport;
import com.gigachat.unit.tests.generator.dto.ExecuteErrors;
import com.gigachat.unit.tests.generator.dto.FailedMethodSnapshot;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.ClassMetadata;
import com.gigachat.unit.tests.generator.dto.FieldMetadata;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.llm.LlmClient;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.DiffEngine;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.pipeline.helpers.PromptBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.SkeletonPromptBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.SnapshotStorage;
import com.gigachat.unit.tests.generator.pipeline.helpers.TestClassWriter;
import com.gigachat.unit.tests.generator.pipeline.InvalidLLMResponseException;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.AutoCorrectionStage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;

/**
 * Executes the first seven stages of the generation pipeline for each discovered method.
 */
public class InitialGenerationStep {
    private final PipelineLogger logger;
    private final TestClassWriter testClassWriter;
    private final SkeletonPromptBuilder skeletonPromptBuilder;
    private final Analyze analyze;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final DiffEngine diffEngine;
    private final CompilerInvoker compilerInvoker;
    private final ExecutionInvoker executionInvoker;
    private final SnapshotStorage snapshotStorage;
    private final ExternalCollaboratorDetector collaboratorDetector;
    private final MethodSignatureRegistry signatureRegistry;
    private final AutoCorrectionStage autoCorrectionStage;

    public InitialGenerationStep(PipelineLogger logger,
                                 TestClassWriter testClassWriter,
                                 SkeletonPromptBuilder skeletonPromptBuilder,
                                 Analyze analyze,
                                 PromptBuilder promptBuilder,
                                 LlmClient llmClient,
                                 DiffEngine diffEngine,
                                 CompilerInvoker compilerInvoker,
                                 ExecutionInvoker executionInvoker,
                                 SnapshotStorage snapshotStorage,
                                 MethodSignatureRegistry signatureRegistry) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.testClassWriter = Objects.requireNonNull(testClassWriter, "testClassWriter");
        this.skeletonPromptBuilder = Objects.requireNonNull(skeletonPromptBuilder, "skeletonPromptBuilder");
        this.analyze = Objects.requireNonNull(analyze, "analyze");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.diffEngine = Objects.requireNonNull(diffEngine, "diffEngine");
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.executionInvoker = Objects.requireNonNull(executionInvoker, "executionInvoker");
        this.snapshotStorage = Objects.requireNonNull(snapshotStorage, "snapshotStorage");
        this.collaboratorDetector = new ExternalCollaboratorDetector();
        this.signatureRegistry = Objects.requireNonNull(signatureRegistry, "signatureRegistry");
        this.autoCorrectionStage = new AutoCorrectionStage();
    }

    public ErrorsReport run(AgentConfig config, List<TestClassInfo> classes) {
        ErrorsReport report = new ErrorsReport();
        if (classes == null || classes.isEmpty()) {
            logger.warn("No classes to process in initial generation step");
            return report;
        }
        PipelineModuleConfig moduleConfig = config.getPipelineModuleConfig();
        ParallelMode parallelMode = moduleConfig.parallelMode();
        logger.info("Starting initial pipeline generation with parallel mode " + parallelMode);
        if (parallelMode.paralleliseClasses()) {
            classes.parallelStream().forEach(classInfo -> processClass(config, classInfo, moduleConfig, report));
        } else {
            for (TestClassInfo classInfo : classes) {
                processClass(config, classInfo, moduleConfig, report);
            }
        }
        return report;
    }

    private void processClass(AgentConfig config,
                              TestClassInfo classInfo,
                              PipelineModuleConfig moduleConfig,
                              ErrorsReport report) {
        testClassWriter.ensureTestClassExists(classInfo);
        if (!classInfo.hasMethods()) {
            logger.warn("Class " + classInfo.getClassName() + " has no eligible methods for generation");
            return;
        }
        List<TestMethodInfo> methods = classInfo.getMethods();
        if (moduleConfig.parallelMode().paralleliseMethods()) {
            methods.parallelStream().forEach(method -> processMethod(config, classInfo, method, moduleConfig, report));
        } else {
            for (TestMethodInfo methodInfo : methods) {
                processMethod(config, classInfo, methodInfo, moduleConfig, report);
            }
        }
    }

    private void processMethod(AgentConfig config,
                               TestClassInfo classInfo,
                               TestMethodInfo methodInfo,
                               PipelineModuleConfig moduleConfig,
                               ErrorsReport report) {
        logger.info("Processing method " + methodInfo.getSignature() + " for class " + classInfo.getClassName());
        String skeletonPrompt = skeletonPromptBuilder.build(classInfo, methodInfo);
        Analyze.AnalysisSummary analysisSummary = analyze.analyze(config, classInfo, methodInfo);
        if (!analysisSummary.invalidCalls().isEmpty()) {
            logger.warn("[WARN] Some inferred invocations were excluded (nonexistent in class metadata):");
            for (String invalidCall : analysisSummary.invalidCalls()) {
                logger.warn("  - " + invalidCall);
            }
        }
        MockPlan plan = analysisSummary.mockPlan();
        String promptJson = promptBuilder.build(config, classInfo, methodInfo, skeletonPrompt, analysisSummary);
        JSONObject contextJson = toJsonObject(promptJson, methodInfo);
        GeneratedTestSnippet snippet;
        try {
            snippet = generateSnippetWithRetry(config,
                    classInfo,
                    methodInfo,
                    skeletonPrompt,
                    plan,
                    contextJson,
                    analysisSummary,
                    moduleConfig);
        } catch (InvalidLLMResponseException exception) {
            logger.info("Skipping method " + methodInfo.getSignature() + " due to invalid LLM response: " + exception.getMessage());
            return;
        }
        DiffEngine.MergeResult mergeResult = diffEngine.merge(classInfo, snippet);
        if (!mergeResult.changed()) {
            logger.warn("Merge step did not change target class for method " + snippet.methodName());
            return;
        }
        if (moduleConfig.compileEnabled()) {
            CompileResult compileResult = compilerInvoker.compile(config.getProjectPath(), classInfo.getTargetPath(), snippet.methodName());
            if (!compileResult.success()) {
                logger.warn("Compilation failed for method " + snippet.methodName());
                report.addCompileErrors(new CompileErrors(classInfo.getTargetPath(),
                        snippet.methodName(),
                        compileResult.messages(),
                        compileResult.stdout(),
                        compileResult.stderr()));
                handleFailure(classInfo, snippet, mergeResult, moduleConfig, "Compilation failure");
                return;
            }
        } else {
            logger.info("Compilation disabled via configuration; skipping compile step.");
        }
        if (moduleConfig.executeEnabled()) {
            ExecuteResult executeResult = executionInvoker.execute(config.getProjectPath(), classInfo.getTargetPath(), snippet.methodName());
            if (!executeResult.success()) {
                logger.warn("Execution failed for method " + snippet.methodName());
                report.addExecuteErrors(new ExecuteErrors(classInfo.getTargetPath(),
                        snippet.methodName(),
                        executeResult.failedTests(),
                        executeResult.stdout(),
                        executeResult.stderr()));
                handleFailure(classInfo, snippet, mergeResult, moduleConfig, "Execution failure");
                return;
            }
        } else {
            logger.info("Execution disabled via configuration; skipping execution step.");
        }
        logger.info("Generation pipeline completed successfully for method " + snippet.methodName());
    }


    private JSONObject toJsonObject(String promptJson, TestMethodInfo methodInfo) {
        if (promptJson == null || promptJson.isBlank()) {
            logger.warn("Prompt JSON was empty for method " + methodInfo.getSignature());
            return new JSONObject();
        }
        try {
            return new JSONObject(promptJson);
        } catch (JSONException exception) {
            logger.warn("Failed to parse prompt JSON for method " + methodInfo.getSignature() + ": " + exception.getMessage());
            return new JSONObject();
        }
    }

    private GeneratedTestSnippet generateSnippetWithRetry(AgentConfig config,
                                                          TestClassInfo classInfo,
                                                          TestMethodInfo methodInfo,
                                                          String skeletonPrompt,
                                                          MockPlan plan,
                                                          JSONObject contextJson,
                                                          Analyze.AnalysisSummary analysisSummary,
                                                          PipelineModuleConfig moduleConfig) {
        try {
            return requestSnippet(config,
                    classInfo,
                    methodInfo,
                    plan,
                    contextJson,
                    analysisSummary,
                    moduleConfig,
                    false);
        } catch (InvalidLLMResponseException first) {
            if (!shouldRetry(first)) {
                throw first;
            }
            logger.warn("Retrying generation for method " + methodInfo.getSignature()
                    + " due to invalid response (" + first.getMessage() + ")");
            Analyze.AnalysisSummary refreshedSummary = analyze.analyze(config, classInfo, methodInfo);
            MockPlan refreshedPlan = refreshedSummary.mockPlan();
            String refreshedPromptJson = promptBuilder.build(config,
                    classInfo,
                    methodInfo,
                    skeletonPrompt,
                    refreshedSummary);
            JSONObject refreshedContextJson = toJsonObject(refreshedPromptJson, methodInfo);
            if (first.getMessage() != null && first.getMessage().contains("E104")) {
                logger.warn("Triggering constructor metadata refresh prior to retry.");
            }
            appendRetryHint(refreshedContextJson);
            return requestSnippet(config,
                    classInfo,
                    methodInfo,
                    refreshedPlan,
                    refreshedContextJson,
                    refreshedSummary,
                    moduleConfig,
                    true);
        }
    }

    private GeneratedTestSnippet requestSnippet(AgentConfig config,
                                                TestClassInfo classInfo,
                                                TestMethodInfo methodInfo,
                                                MockPlan plan,
                                                JSONObject contextJson,
                                                Analyze.AnalysisSummary analysisSummary,
                                                PipelineModuleConfig moduleConfig,
                                                boolean retryAttempt) {
        String llmPrompt = promptBuilder.buildPromptForLLM(contextJson, config.getPromptConfig());
        logger.info("Prepared LLM prompt for method " + methodInfo.getSignature()
                + (retryAttempt ? " [retry]" : ""));
        GeneratedTestSnippet snippet = llmClient.generateTestSnippet(llmPrompt, classInfo, methodInfo, plan);
        snippet = autoCorrectionStage.apply(snippet);
        validateGeneratedSnippet(config, classInfo, snippet, methodInfo, analysisSummary, moduleConfig);
        return snippet;
    }

    private boolean shouldRetry(InvalidLLMResponseException exception) {
        if (exception == null) {
            return false;
        }
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("E102") || message.contains("E103") || message.contains("E104");
    }

    private void appendRetryHint(JSONObject contextJson) {
        if (contextJson == null) {
            return;
        }
        final String hint = "Skip unreachable or undefined constructors.";
        JSONArray hints = contextJson.optJSONArray("hints");
        if (hints == null) {
            hints = new JSONArray();
            contextJson.put("hints", hints);
        }
        for (int i = 0; i < hints.length(); i++) {
            if (hint.equalsIgnoreCase(hints.optString(i))) {
                return;
            }
        }
        hints.put(hint);
    }

    private void handleFailure(TestClassInfo classInfo,
                               GeneratedTestSnippet snippet,
                               DiffEngine.MergeResult mergeResult,
                               PipelineModuleConfig moduleConfig,
                               String reason) {
        logger.warn("Preparing repair step for method " + snippet.methodName() + " due to " + reason);
        revertMerge(classInfo, mergeResult);
        if (moduleConfig.snapshotsEnabled()) {
            FailedMethodSnapshot snapshot = new FailedMethodSnapshot(classInfo.getTargetPath(),
                    snippet.methodName(),
                    snippet.methodBody(),
                    snippet.imports(),
                    reason,
                    Instant.now());
            snapshotStorage.save(snapshot);
        }
    }

    private void revertMerge(TestClassInfo classInfo, DiffEngine.MergeResult mergeResult) {
        Path file = classInfo.getTargetPath();
        testClassWriter.writeSource(file, mergeResult.originalSource());
        logger.info("Reverted generated method from " + file);
    }

    private void validateGeneratedSnippet(AgentConfig config,
                                          TestClassInfo classInfo,
                                          GeneratedTestSnippet snippet,
                                          TestMethodInfo methodInfo,
                                          Analyze.AnalysisSummary analysisSummary,
                                          PipelineModuleConfig moduleConfig) {
        if (snippet == null || methodInfo == null) {
            return;
        }
        String fullSource = snippet.fullClassSource();
        if (fullSource == null || fullSource.isBlank()) {
            return;
        }
        String signature = methodInfo.getSignature();
        if (signature == null || signature.isBlank()) {
            return;
        }
        String normalisedSignature = signature.replaceAll("\\s+", " ").trim();
        String normalisedSource = fullSource.replaceAll("\\s+", " ").trim();
        if (normalisedSource.contains(normalisedSignature + " {")) {
            String methodName = analysisSummary.methodAnalysis().method().name();
            logger.warn("⚠️  LLM reimplemented method " + methodName + " inside test class. Marking generation as invalid.");
            throw new InvalidLLMResponseException("LLM returned reimplementation of tested method instead of test.");
        }
        CompilationUnit compilationUnit = parseCompilationUnit(fullSource);
        if (compilationUnit == null) {
            return;
        }
        Map<String, String> variableTypes = ensureMethodAndConstructorUsageIsValid(config,
                compilationUnit,
                analysisSummary,
                classInfo,
                methodInfo);
        ensureNoInternalFieldAccess(fullSource, analysisSummary);
        if (moduleConfig != null && moduleConfig.validateMockUsage()) {
            ensureMockUsageIsValid(fullSource, analysisSummary, classInfo);
        }
    }

    private CompilationUnit parseCompilationUnit(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            return StaticJavaParser.parse(source);
        } catch (ParseProblemException exception) {
            logger.warn("Unable to parse generated source for API validation: " + exception.getMessage());
            return null;
        }
    }

    private Map<String, String> ensureMethodAndConstructorUsageIsValid(AgentConfig config,
                                                                       CompilationUnit compilationUnit,
                                                                       Analyze.AnalysisSummary analysisSummary,
                                                                       TestClassInfo classInfo,
                                                                       TestMethodInfo methodInfo) {
        if (compilationUnit == null) {
            return Map.of();
        }
        Map<String, String> variableTypes = collectVariableTypes(compilationUnit, analysisSummary, classInfo);
        LinkedHashSet<String> issues = new LinkedHashSet<>();
        LinkedHashSet<String> missingConstructorMetadata = new LinkedHashSet<>();
        Set<String> signatureTypes = collectMethodSignatureTypeNames(methodInfo);
        compilationUnit.findAll(ObjectCreationExpr.class).forEach(expr -> {
            String type = simpleName(expr.getType().asString());
            if (type.isEmpty() || !signatureRegistry.hasClass(type)) {
                return;
            }
            int argumentCount = expr.getArguments().size();
            signatureRegistry.registerConstructorsIfAbsent(type);
            List<ConstructorMetadata> constructors = signatureRegistry.getConstructorsForClass(type);
            if (constructors.isEmpty() || !signatureRegistry.constructorExists(type, argumentCount)) {
                if (signatureTypes.contains(type)) {
                    attemptConstructorRefresh(config, classInfo, methodInfo, type);
                    constructors = signatureRegistry.getConstructorsForClass(type);
                    if (!constructors.isEmpty() && signatureRegistry.constructorExists(type, argumentCount)) {
                        return;
                    }
                }
                missingConstructorMetadata.add(type);
                issues.add("E104: Missing constructor metadata for " + formatConstructorInvocation(type, expr));
            }
        });
        compilationUnit.findAll(MethodCallExpr.class).forEach(expr -> {
            Optional<Expression> scope = expr.getScope();
            if (scope.isEmpty()) {
                return;
            }
            String resolvedType = resolveExpressionType(scope.get(), variableTypes, classInfo);
            if (isStandardLibraryType(resolvedType)) {
                return;
            }
            String simple = simpleName(resolvedType);
            if (simple.isEmpty() || !signatureRegistry.hasClass(simple)) {
                return;
            }
            if (!signatureRegistry.methodExists(simple, expr.getNameAsString(), expr.getArguments().size())) {
                issues.add("E102: Invented method " + formatMethodInvocation(simple, expr));
            }
        });
        if (!missingConstructorMetadata.isEmpty()) {
            logger.warn("Constructor metadata missing for: " + String.join(", ", missingConstructorMetadata));
        }
        if (!issues.isEmpty()) {
            String message = String.join("; ", issues);
            logger.warn("⚠️  " + message);
            throw new InvalidLLMResponseException(message);
        }
        return variableTypes;
    }

    private Set<String> collectMethodSignatureTypeNames(TestMethodInfo methodInfo) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (methodInfo == null) {
            return names;
        }
        MethodDeclaration declaration = methodInfo.getDeclaration();
        if (declaration != null) {
            declaration.getParameters().forEach(parameter ->
                    extractTypeNames(parameter.getType(), names));
            extractTypeNames(declaration.getType(), names);
        } else {
            String returnType = methodInfo.getReturnType();
            if (returnType != null && !returnType.isBlank()) {
                names.add(simpleName(returnType));
            }
        }
        return names;
    }

    private void extractTypeNames(com.github.javaparser.ast.type.Type type, Set<String> collector) {
        if (type == null || collector == null) {
            return;
        }
        if (type.isPrimitiveType()) {
            return;
        }
        if (type.isArrayType()) {
            extractTypeNames(type.asArrayType().getComponentType(), collector);
            return;
        }
        if (type.isUnionType()) {
            type.asUnionType().getElements().forEach(element -> extractTypeNames(element, collector));
            return;
        }
        if (type.isIntersectionType()) {
            type.asIntersectionType().getElements().forEach(element -> extractTypeNames(element, collector));
            return;
        }
        if (type.isWildcardType()) {
            type.asWildcardType().getExtendedType().ifPresent(t -> extractTypeNames(t, collector));
            type.asWildcardType().getSuperType().ifPresent(t -> extractTypeNames(t, collector));
            return;
        }
        if (type.isClassOrInterfaceType()) {
            collector.add(simpleName(type.asClassOrInterfaceType().getNameWithScope()));
            type.asClassOrInterfaceType().getTypeArguments()
                    .ifPresent(arguments -> arguments.forEach(argument -> extractTypeNames(argument, collector)));
            return;
        }
        collector.add(simpleName(type.asString()));
    }

    private void attemptConstructorRefresh(AgentConfig config,
                                           TestClassInfo classInfo,
                                           TestMethodInfo methodInfo,
                                           String type) {
        if (config == null || analyze == null) {
            return;
        }
        boolean refreshTriggered = signatureRegistry != null && signatureRegistry.refreshConstructors(type);
        if (logger != null) {
            String baseMessage = "Attempting constructor metadata refresh for type " + type
                    + " referenced in method signature before failing validation.";
            if (!refreshTriggered) {
                logger.warn(baseMessage + " Registry has no cached constructors yet.");
            } else {
                logger.warn(baseMessage);
            }
        }
        analyze.analyze(config, classInfo, methodInfo);
    }

    private void ensureNoInternalFieldAccess(String generatedCode,
                                             Analyze.AnalysisSummary analysisSummary) {
        if (analysisSummary == null) {
            return;
        }
        Set<String> internalFields = analysisSummary.internalFields();
        if (internalFields == null || internalFields.isEmpty()) {
            return;
        }
        String code = generatedCode == null ? "" : generatedCode;
        if (code.isBlank()) {
            return;
        }
        Pattern pattern = Pattern.compile("\\b(\\w+)\\.(\\w+)\\b");
        Matcher matcher = pattern.matcher(code);
        LinkedHashSet<String> violations = new LinkedHashSet<>();
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        String targetInstance = targetContext == null ? "" : normalise(targetContext.instanceName());
        Set<String> accessible = analysisSummary.accessibleFields();
        while (matcher.find()) {
            String instance = matcher.group(1);
            String field = matcher.group(2);
            if ("this".equals(instance) && field.equals(targetInstance)) {
                continue;
            }
            if (accessible != null && accessible.contains(field)) {
                continue;
            }
            if (internalFields.contains(field)) {
                violations.add(instance + '.' + field);
            }
        }
        if (!violations.isEmpty()) {
            String message = "E103: Internal field access " + String.join(", ", violations);
            logger.warn("⚠️  " + message);
            throw new InvalidLLMResponseException(message);
        }
    }

    private Map<String, String> collectVariableTypes(CompilationUnit compilationUnit,
                                                     Analyze.AnalysisSummary analysisSummary,
                                                     TestClassInfo classInfo) {
        Map<String, String> types = new LinkedHashMap<>();
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        if (targetContext != null && targetContext.instanceName() != null && !targetContext.instanceName().isBlank()) {
            types.put(targetContext.instanceName(), targetContext.className());
        }
        analysisSummary.availableMethods().keySet().forEach(className -> types.putIfAbsent(className, className));
        analysisSummary.availableConstructors().keySet().forEach(className -> types.putIfAbsent(className, className));
        compilationUnit.findAll(VariableDeclarator.class).forEach(declarator -> {
            String name = declarator.getNameAsString();
            if (name == null || name.isBlank()) {
                return;
            }
            String type = declarator.getType().asString();
            if ("var".equals(type)) {
                type = inferTypeFromInitializer(declarator.getInitializer());
            }
            types.putIfAbsent(name, type);
        });
        compilationUnit.findAll(MethodDeclaration.class).forEach(method ->
                method.getParameters().forEach(parameter -> types.putIfAbsent(parameter.getNameAsString(), parameter.getType().asString())));
        if (classInfo != null && classInfo.getClassMetadata() != null) {
            classInfo.getClassMetadata().getFields().forEach(field -> types.putIfAbsent(field.getName(), field.getTypeName()));
        }
        return types;
    }

    private String inferTypeFromInitializer(Optional<Expression> initializer) {
        if (initializer.isEmpty()) {
            return "";
        }
        Expression expression = initializer.get();
        if (expression instanceof ObjectCreationExpr creationExpr) {
            return creationExpr.getType().asString();
        }
        return "";
    }

    private String resolveExpressionType(Expression expression,
                                         Map<String, String> variableTypes,
                                         TestClassInfo classInfo) {
        if (expression instanceof ThisExpr) {
            return classInfo == null ? "" : classInfo.getClassName();
        }
        if (expression instanceof NameExpr nameExpr) {
            return variableTypes.getOrDefault(nameExpr.getNameAsString(), "");
        }
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            String fieldName = fieldAccessExpr.getNameAsString();
            String direct = variableTypes.get(fieldName);
            if (direct != null && !direct.isBlank()) {
                return direct;
            }
            return resolveExpressionType(fieldAccessExpr.getScope(), variableTypes, classInfo);
        }
        return "";
    }

    private String formatConstructorInvocation(String type, ObjectCreationExpr expr) {
        String arguments = describeArguments(new java.util.ArrayList<>(expr.getArguments()));
        return type + '(' + arguments + ')';
    }

    private String formatMethodInvocation(String type, MethodCallExpr expr) {
        String arguments = describeArguments(new java.util.ArrayList<>(expr.getArguments()));
        return type + '.' + expr.getNameAsString() + '(' + arguments + ')';
    }

    private String describeArguments(List<Expression> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        List<String> parts = new java.util.ArrayList<>(arguments.size());
        for (Expression argument : arguments) {
            String text = argument == null ? "" : argument.toString();
            if (text.length() > 40) {
                text = text.substring(0, 37) + "...";
            }
            parts.add(text);
        }
        return String.join(", ", parts);
    }

    private String simpleName(String type) {
        if (type == null) {
            return "";
        }
        String trimmed = type.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int genericStart = trimmed.indexOf('<');
        if (genericStart >= 0) {
            trimmed = trimmed.substring(0, genericStart);
        }
        int arrayIndex = trimmed.indexOf('[');
        if (arrayIndex >= 0) {
            trimmed = trimmed.substring(0, arrayIndex);
        }
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < trimmed.length()) {
            return trimmed.substring(lastDot + 1);
        }
        return trimmed;
    }

    private void ensureMockUsageIsValid(String fullSource,
                                        Analyze.AnalysisSummary analysisSummary,
                                        TestClassInfo classInfo) {
        MockPlan plan = analysisSummary.mockPlan();
        if (plan != null && plan.strategy() == MockStrategy.NONE && containsMockito(fullSource)) {
            String collaboratorField = collaboratorDetector.findFirstExternalCollaborator(
                            classInfo != null ? classInfo.getClassMetadata() : null)
                    .map(field -> field.getTypeName() + " " + field.getName())
                    .orElse(null);
            if (collaboratorField != null) {
                logger.warn("⚠️  E105: unexpected Mockito usage when mocks are disabled. External collaborator field detected: "
                        + collaboratorField + '.');
            } else {
                logger.warn("⚠️  E105: unexpected Mockito usage when mocks are disabled.");
            }
            throw new InvalidLLMResponseException("E105: unexpected Mockito usage when mocks are disabled.");
        }
    }

    private String normalise(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    private boolean containsMockito(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        if (source.contains("Mockito")) {
            return true;
        }
        return source.contains("org.mockito")
                || source.contains("import static org.mockito")
                || source.contains("@Mock");
    }

    private boolean isStandardLibraryType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        for (String standard : Analyze.STANDARD_TYPES) {
            if (matchesStandardLibraryType(type, standard)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesStandardLibraryType(String candidate, String standard) {
        if (candidate == null || standard == null) {
            return false;
        }
        String trimmedCandidate = candidate.trim();
        if (trimmedCandidate.isEmpty()) {
            return false;
        }
        if (trimmedCandidate.contains(standard)) {
            return true;
        }
        String standardSimple = simpleName(standard);
        String candidateSimple = simpleName(trimmedCandidate);
        if (!standardSimple.isEmpty()) {
            if (candidateSimple.equals(standardSimple)) {
                return true;
            }
            if (trimmedCandidate.startsWith(standardSimple + "<")) {
                return true;
            }
            if (trimmedCandidate.endsWith('.' + standardSimple)) {
                return true;
            }
            if (trimmedCandidate.equals(standardSimple)) {
                return true;
            }
        }
        return false;
    }
}
