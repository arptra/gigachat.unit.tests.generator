package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ExternalCollaboratorDetector;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.AnalysisConfig;
import com.gigachat.unit.tests.generator.config.PipelineModuleConfig;
import com.gigachat.unit.tests.generator.dto.ClassMetadata;
import com.gigachat.unit.tests.generator.dto.FieldMetadata;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.testagent.entrypoint.pipeline.helpers.analyze.AnalysisFormatter;
import com.testagent.entrypoint.pipeline.helpers.analyze.DependencyInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalyzer;
import com.testagent.entrypoint.pipeline.helpers.analyze.InvocationInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.MockStrategyResolver;
import com.testagent.entrypoint.pipeline.helpers.analyze.MockType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Performs analysis of the target method to understand mocking needs and provide context for the LLM.
 */
public class Analyze {
    private final MethodAnalyzer methodAnalyzer;
    private final AnalysisFormatter analysisFormatter;
    private final MockStrategyResolver mockStrategyResolver;
    private final ExternalCollaboratorDetector collaboratorDetector;
    private final PipelineLogger logger;
    private final MethodSignatureRegistry signatureRegistry;

    public Analyze() {
        this(null, new MethodSignatureRegistry());
    }

    public Analyze(PipelineLogger logger) {
        this(logger, new MethodSignatureRegistry());
    }

    public Analyze(MethodSignatureRegistry signatureRegistry) {
        this(null, signatureRegistry);
    }

    public Analyze(PipelineLogger logger, MethodSignatureRegistry signatureRegistry) {
        this(new MethodAnalyzer(logger),
                new AnalysisFormatter(),
                new MockStrategyResolver(),
                logger,
                new ExternalCollaboratorDetector(),
                signatureRegistry);
    }

    public Analyze(MethodAnalyzer methodAnalyzer,
                   AnalysisFormatter analysisFormatter,
                   MockStrategyResolver mockStrategyResolver,
                   PipelineLogger logger,
                   ExternalCollaboratorDetector collaboratorDetector,
                   MethodSignatureRegistry signatureRegistry) {
        this.methodAnalyzer = Objects.requireNonNull(methodAnalyzer, "methodAnalyzer");
        this.analysisFormatter = Objects.requireNonNull(analysisFormatter, "analysisFormatter");
        this.mockStrategyResolver = Objects.requireNonNull(mockStrategyResolver, "mockStrategyResolver");
        this.logger = logger;
        this.collaboratorDetector = Objects.requireNonNull(collaboratorDetector, "collaboratorDetector");
        this.signatureRegistry = Objects.requireNonNull(signatureRegistry, "signatureRegistry");
    }

    public AnalysisSummary analyze(AgentConfig config, TestClassInfo classInfo, TestMethodInfo methodInfo) {
        AnalysisConfig analysisConfig = config == null ? AnalysisConfig.from(Map.of()) : config.getAnalysisConfig();
        PipelineModuleConfig pipelineConfig = config == null
                ? PipelineModuleConfig.from(Map.of())
                : config.getPipelineModuleConfig();
        MethodAnalysisResult result = methodAnalyzer.analyze(classInfo, methodInfo, config, pipelineConfig);
        ClassMetadata metadata = classInfo == null ? null : classInfo.getClassMetadata();
        boolean hasExternalCollaborators = collaboratorDetector.hasExternalCollaborators(metadata);
        Set<String> accessibleFields = extractAccessibleFields(metadata);
        Set<String> internalFields = extractInternalFields(metadata);
        FilteredAnalysis filteredAnalysis = filterInvalidCalls(result, classInfo, methodInfo);
        MethodAnalysisResult filteredResult = filteredAnalysis.filteredResult();
        boolean invokesCollaborator = collaboratorDetector.methodInvokesExternalCollaborator(filteredResult, metadata);
        MockPlan plan = mockStrategyResolver.createPlan(filteredResult,
                analysisConfig,
                pipelineConfig.autoMockDetectionEnabled(),
                pipelineConfig.excludeInternalCollections());
        if ((hasExternalCollaborators || invokesCollaborator) && plan.strategy() == MockStrategy.NONE) {
            plan = new MockPlan(plan.targets(), MockStrategy.MOCKITO, plan.shouldMock(), plan.shouldNotMock());
        }
        MethodAnalysisResult sanitisedResult = sanitizeInvocations(filteredResult, internalFields);
        Map<String, String> verificationPolicy = analysisConfig.includeVerificationPolicy()
                ? buildVerificationPolicy(sanitisedResult.invocations())
                : Map.of();
        if (plan.strategy() == MockStrategy.NONE) {
            verificationPolicy = Map.of();
        }
        String contextJson = analysisFormatter.format(sanitisedResult);
        TestTargetContext targetContext = extractTestTargetContext(classInfo, methodInfo);
        Map<String, List<ConstructorMetadata>> availableConstructors = collectAvailableConstructors(filteredAnalysis.relevantClasses());
        Map<String, List<String>> availableMethods = collectAvailableMethods(filteredAnalysis.relevantClasses());
        if (logger != null) {
            logger.info("Analysis JSON context prepared for method " + filteredResult.method().name());
        }
        Set<String> accessibleSnapshot = Set.copyOf(accessibleFields);
        Set<String> internalSnapshot = Set.copyOf(internalFields);
        return new AnalysisSummary(plan,
                sanitisedResult,
                contextJson,
                verificationPolicy,
                targetContext,
                hasExternalCollaborators,
                filteredAnalysis.invalidCalls(),
                accessibleSnapshot,
                internalSnapshot,
                availableConstructors,
                availableMethods);
    }

    private Set<String> extractAccessibleFields(ClassMetadata metadata) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (metadata == null) {
            return fields;
        }
        for (FieldMetadata field : metadata.getFields()) {
            if (field == null) {
                continue;
            }
            String name = defaultString(field.getName());
            if (name.isBlank()) {
                continue;
            }
            if (!field.isPrivate()) {
                fields.add(name);
            }
        }
        return fields;
    }

    private Set<String> extractInternalFields(ClassMetadata metadata) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (metadata == null) {
            return fields;
        }
        for (FieldMetadata field : metadata.getFields()) {
            if (field == null) {
                continue;
            }
            String name = defaultString(field.getName());
            if (name.isBlank()) {
                continue;
            }
            if (field.isPrivate()) {
                fields.add(name);
            }
        }
        return fields;
    }

    private MethodAnalysisResult sanitizeInvocations(MethodAnalysisResult analysis,
                                                     Set<String> internalFields) {
        if (analysis == null || internalFields == null || internalFields.isEmpty()) {
            return analysis;
        }
        List<InvocationInfo> sanitisedInvocations = new ArrayList<>();
        for (InvocationInfo invocation : analysis.invocations()) {
            if (invocation == null) {
                continue;
            }
            String target = sanitizeInvocationTarget(invocation.target(), internalFields);
            sanitisedInvocations.add(new InvocationInfo(target, invocation.methodName(), invocation.argTypes()));
        }
        return new MethodAnalysisResult(analysis.method(),
                analysis.dependencies(),
                sanitisedInvocations,
                analysis.staticUsages(),
                analysis.unresolved());
    }

    private String sanitizeInvocationTarget(String target, Set<String> internalFields) {
        String text = defaultString(target);
        if (text.isEmpty()) {
            return text;
        }
        if (containsInternalFieldReference(text, internalFields)) {
            return "internal_state";
        }
        return text;
    }

    private boolean containsInternalFieldReference(String target, Set<String> internalFields) {
        if (internalFields == null || internalFields.isEmpty()) {
            return false;
        }
        String normalised = defaultString(target);
        if (normalised.isEmpty()) {
            return false;
        }
        for (String field : internalFields) {
            String candidate = defaultString(field);
            if (candidate.isEmpty()) {
                continue;
            }
            if (normalised.equals(candidate)
                    || normalised.equals("this." + candidate)
                    || normalised.startsWith(candidate + '.')
                    || normalised.startsWith("this." + candidate + '.')
                    || normalised.endsWith('.' + candidate)
                    || normalised.contains('.' + candidate + '.')) {
                return true;
            }
        }
        return false;
    }

    private FilteredAnalysis filterInvalidCalls(MethodAnalysisResult analysis,
                                                TestClassInfo classInfo,
                                                TestMethodInfo methodInfo) {
        if (analysis == null) {
            return new FilteredAnalysis(new MethodAnalysisResult(null, List.of(), List.of(), List.of(), List.of()),
                    List.of(),
                    Set.of());
        }
        Map<String, String> variableTypes = new HashMap<>();
        LinkedHashSet<String> relevantClasses = new LinkedHashSet<>();
        if (classInfo != null) {
            String owner = defaultString(classInfo.getClassName());
            if (!owner.isBlank()) {
                variableTypes.put("this", owner);
                addRelevantType(relevantClasses, owner);
            }
            ClassMetadata metadata = classInfo.getClassMetadata();
            if (metadata != null) {
                for (FieldMetadata field : metadata.getFields()) {
                    String fieldName = defaultString(field.getName());
                    if (!fieldName.isBlank()) {
                        variableTypes.putIfAbsent(fieldName, field.getTypeName());
                    }
                    addRelevantType(relevantClasses, field.getTypeName());
                }
            }
        }
        if (methodInfo != null) {
            if (methodInfo.getDeclaration() != null) {
                methodInfo.getDeclaration().getParameters().forEach(parameter -> {
                    String type = parameter.getType().asString();
                    variableTypes.putIfAbsent(parameter.getNameAsString(), type);
                    addRelevantType(relevantClasses, type);
                });
            }
            addRelevantType(relevantClasses, methodInfo.getReturnType());
        }
        if (analysis != null) {
            analysis.staticUsages().forEach(usage -> addRelevantType(relevantClasses, usage));
        }
        for (DependencyInfo dependency : analysis.dependencies()) {
            if (dependency == null) {
                continue;
            }
            addRelevantType(relevantClasses, dependency.className());
            String variableName = defaultString(dependency.variableName());
            if (!variableName.isBlank()) {
                variableTypes.putIfAbsent(variableName, dependency.className());
            }
        }
        List<DependencyInfo> filteredDependencies = new ArrayList<>();
        List<InvocationInfo> filteredInvocations = new ArrayList<>();
        Set<String> invalidCalls = new LinkedHashSet<>();
        for (DependencyInfo dependency : analysis.dependencies()) {
            if (dependency == null) {
                continue;
            }
            boolean constructorCall = dependency.mockType() == MockType.CONSTRUCTOR;
            if (constructorCall) {
                String simpleClass = simpleName(dependency.className());
                if (signatureRegistry.hasClass(simpleClass)) {
                    int argumentCount = Math.max(dependency.argumentCount(), 0);
                    if (!signatureRegistry.constructorExists(simpleClass, argumentCount)) {
                        invalidCalls.add(formatInvalidConstructor(simpleClass, dependency));
                        continue;
                    }
                }
            }
            filteredDependencies.add(dependency);
        }
        for (InvocationInfo invocation : analysis.invocations()) {
            if (invocation == null) {
                continue;
            }
            invocation.argTypes().forEach(arg -> addRelevantType(relevantClasses, arg));
            String resolvedType = resolveInvocationType(invocation, variableTypes, classInfo);
            addRelevantType(relevantClasses, resolvedType);
            String simpleResolved = simpleName(resolvedType);
            if (!simpleResolved.isEmpty() && signatureRegistry.hasClass(simpleResolved)) {
                int argCount = invocation.argTypes() == null ? 0 : invocation.argTypes().size();
                if (!signatureRegistry.methodExists(simpleResolved, invocation.methodName(), argCount)) {
                    invalidCalls.add(formatInvalidMethodCall(simpleResolved, invocation));
                    continue;
                }
            }
            filteredInvocations.add(invocation);
        }
        MethodAnalysisResult filtered = new MethodAnalysisResult(analysis.method(),
                filteredDependencies,
                filteredInvocations,
                analysis.staticUsages(),
                analysis.unresolved());
        for (String type : variableTypes.values()) {
            addRelevantType(relevantClasses, type);
        }
        return new FilteredAnalysis(filtered, List.copyOf(invalidCalls), Set.copyOf(relevantClasses));
    }

    private void addRelevantType(Set<String> collector, String type) {
        if (collector == null) {
            return;
        }
        if (!isMeaningfulType(type)) {
            return;
        }
        collector.add(type.trim());
    }

    private boolean isMeaningfulType(String type) {
        if (type == null) {
            return false;
        }
        String trimmed = type.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if ("unknown".equalsIgnoreCase(trimmed) || "unknowndependency".equalsIgnoreCase(trimmed)) {
            return false;
        }
        String simple = simpleName(trimmed);
        if (simple.isEmpty()) {
            return false;
        }
        if (isPrimitiveType(simple) || "void".equalsIgnoreCase(simple)) {
            return false;
        }
        return true;
    }

    private Map<String, List<ConstructorMetadata>> collectAvailableConstructors(Set<String> classNames) {
        LinkedHashMap<String, List<ConstructorMetadata>> map = new LinkedHashMap<>();
        Map<String, List<ConstructorMetadata>> detailed = signatureRegistry.getConstructorsDetailed();
        for (String className : classNames) {
            String simple = simpleName(className);
            List<ConstructorMetadata> constructors = detailed.get(simple);
            if (constructors == null || constructors.isEmpty()) {
                continue;
            }
            map.put(simple, List.copyOf(constructors));
        }
        return map;
    }
    private boolean isPrimitiveType(String type) {
        if (type == null) {
            return false;
        }
        return switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "byte", "short", "int", "long", "float", "double", "boolean", "char" -> true;
            default -> false;
        };
    }

    private Map<String, List<String>> collectAvailableMethods(Set<String> classNames) {
        LinkedHashMap<String, List<String>> map = new LinkedHashMap<>();
        for (String className : classNames) {
            String simple = simpleName(className);
            if (!signatureRegistry.hasClass(simple)) {
                continue;
            }
            Set<String> methods = signatureRegistry.methodsFor(simple);
            if (methods.isEmpty()) {
                continue;
            }
            map.put(simple, List.copyOf(methods));
        }
        return map;
    }

    private String resolveInvocationType(InvocationInfo invocation,
                                         Map<String, String> variableTypes,
                                         TestClassInfo classInfo) {
        String target = defaultString(invocation.target());
        if (target.isBlank()) {
            return "";
        }
        String normalised = normaliseTarget(target);
        if ("this".equals(normalised)) {
            return classInfo == null ? "" : classInfo.getClassName();
        }
        String resolved = variableTypes.get(normalised);
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        if (classInfo != null) {
            String className = defaultString(classInfo.getClassName());
            if (!className.isBlank()) {
                String simple = simpleName(className);
                if (normalised.equals(simple)) {
                    return className;
                }
            }
        }
        return resolved == null ? "" : resolved;
    }

    private String normaliseTarget(String target) {
        String text = defaultString(target);
        if (text.startsWith("this.")) {
            text = text.substring(5);
        }
        int dotIndex = text.indexOf('.');
        if (dotIndex > 0) {
            text = text.substring(0, dotIndex);
        }
        return text;
    }

    private String formatInvalidConstructor(String className, DependencyInfo dependency) {
        String context = dependency.context();
        if (context != null && context.contains("(")) {
            int start = context.indexOf('(');
            int end = context.lastIndexOf(')');
            if (start >= 0 && end > start) {
                String args = context.substring(start + 1, end).trim();
                return className + '(' + args + ')';
            }
        }
        return className + '(' + dependency.argumentCount() + ')';
    }

    private String formatInvalidMethodCall(String resolvedClass, InvocationInfo invocation) {
        String base = simpleName(resolvedClass);
        if (base.isEmpty()) {
            base = normaliseTarget(invocation.target());
        }
        if (base.isEmpty()) {
            base = defaultString(invocation.target());
        }
        String args = invocation.argTypes().isEmpty() ? "" : String.join(", ", invocation.argTypes());
        return base + '.' + invocation.methodName() + '(' + args + ')';
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

    private record FilteredAnalysis(MethodAnalysisResult filteredResult,
                                    List<String> invalidCalls,
                                    Set<String> relevantClasses) {
    }

    public MockPlan analyze(TestClassInfo classInfo, TestMethodInfo methodInfo) {
        AnalysisSummary summary = analyze(null, classInfo, methodInfo);
        return summary.mockPlan();
    }

    public Map<String, String> buildVerificationPolicy(List<InvocationInfo> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return Map.of();
        }
        Map<String, String> policy = new LinkedHashMap<>();
        for (InvocationInfo invocation : invocations) {
            if (invocation == null) {
                continue;
            }
            String key = invocation.target() + "." + invocation.methodName();
            policy.putIfAbsent(key, toVerificationExpression(invocation));
        }
        return policy;
    }

    private String toVerificationExpression(InvocationInfo invocation) {
        List<String> args = invocation.argTypes();
        if (args == null || args.isEmpty()) {
            return "verify(" + invocation.target() + ")." + invocation.methodName() + "()";
        }
        List<String> normalised = new ArrayList<>(args.size());
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                normalised.add("...");
                continue;
            }
            String trimmed = arg.trim();
            if (trimmed.matches("[A-Za-z0-9_]+")) {
                normalised.add(trimmed);
            } else {
                normalised.add("...");
            }
        }
        return "verify(" + invocation.target() + ")." + invocation.methodName() + "(" + String.join(", ", normalised) + ")";
    }

    private TestTargetContext extractTestTargetContext(TestClassInfo classInfo, TestMethodInfo methodInfo) {
        String className = classInfo == null ? "" : defaultString(classInfo.getClassName());
        String instanceName = deriveInstanceName(className);
        MethodDeclaration declaration = methodInfo == null ? null : methodInfo.getDeclaration();
        boolean isStatic = determineStatic(declaration, methodInfo);
        boolean requiresInstance = !isStatic;
        return new TestTargetContext(className, instanceName, requiresInstance, isStatic);
    }

    private boolean determineStatic(MethodDeclaration declaration, TestMethodInfo methodInfo) {
        if (declaration != null) {
            return declaration.isStatic();
        }
        String signature = methodInfo == null ? null : methodInfo.getSignature();
        if (signature == null || signature.isBlank()) {
            return false;
        }
        return STATIC_KEYWORD.matcher(signature).find();
    }

    private String deriveInstanceName(String className) {
        String base = defaultString(className);
        if (base.isEmpty()) {
            return "instance";
        }
        String[] parts = CAMEL_CASE_SPLIT.split(base);
        String candidate = parts.length == 0 ? base : parts[parts.length - 1];
        if (candidate.isBlank()) {
            candidate = base;
        }
        if (candidate.length() == 1) {
            return candidate.toLowerCase(Locale.ROOT);
        }
        return candidate.substring(0, 1).toLowerCase(Locale.ROOT) + candidate.substring(1);
    }

    private String defaultString(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private static final Pattern CAMEL_CASE_SPLIT = Pattern.compile("(?<!^)(?=[A-Z])");
    private static final Pattern STATIC_KEYWORD = Pattern.compile("\\bstatic\\b");

    public record AnalysisSummary(MockPlan mockPlan,
                                  MethodAnalysisResult methodAnalysis,
                                  String jsonContext,
                                  Map<String, String> verificationPolicy,
                                  TestTargetContext testTargetContext,
                                  boolean hasExternalCollaborators,
                                  List<String> invalidCalls,
                                  Set<String> accessibleFields,
                                  Set<String> internalFields,
                                  Map<String, List<ConstructorMetadata>> availableConstructors,
                                  Map<String, List<String>> availableMethods) {
    }

    public record TestTargetContext(String className,
                                    String instanceName,
                                    boolean requiresInstance,
                                    boolean isStatic) {
    }
}
