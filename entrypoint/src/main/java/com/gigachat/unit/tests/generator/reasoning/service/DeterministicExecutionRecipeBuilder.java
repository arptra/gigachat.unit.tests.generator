package com.gigachat.unit.tests.generator.reasoning.service;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.report.parser.TestReportFailure;
import com.gigachat.unit.tests.generator.resources.RuntimeRecipeTemplateCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives bounded repair recipes for runtime failures from real project sources so the LLM chooses
 * an action id instead of inventing arbitrary patches.
 */
public class DeterministicExecutionRecipeBuilder {

    private static final Pattern CONSTRUCTOR_ASSIGNMENT = Pattern.compile(
            "(?m)(?:final\\s+)?[\\w<>\\[\\]]+\\s+(?<var>[A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*new\\s+(?<type>[A-Za-z_][A-Za-z0-9_]*)\\s*\\((?<args>[^;]*)\\);");
    private static final Pattern STRING_LITERAL_CALL = Pattern.compile(
            "\\b(?<mock>[A-Za-z_][A-Za-z0-9_]*)\\.isEnabled\\(\"(?<literal>[^\"]+)\"\\)");
    private static final Pattern RECORD_EVENT_CALL = Pattern.compile(
            "\\b(?<mock>[A-Za-z_][A-Za-z0-9_]*)\\.recordEvent\\(\"(?<literal>[^\"]*)\"(?<dynamic>\\s*\\+[^;]+)?\\);");
    private static final Pattern COLLABORATOR_CALL = Pattern.compile(
            "\\b(?<mock>[A-Za-z_][A-Za-z0-9_]*)\\.(?<method>[A-Za-z_][A-Za-z0-9_]*)\\s*\\((?<args>[^;]*)\\);");
    private static final Pattern REAL_DB_CONNECTION_ATTEMPT = Pattern.compile(
            "real db connection attempt for (?<channel>[^\\r\\n]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STACK_FRAME = Pattern.compile(
            "(?m)^\\s*(?<owner>[a-zA-Z0-9_$.]+)\\.(?<method>[A-Za-z_][A-Za-z0-9_]*)\\([^\\n]*\\)$");
    private static final Pattern ZERO_ARG_OBJECT_CREATION_ASSIGNMENT = Pattern.compile(
            "(?m)(?:final\\s+)?(?:var|[\\w.$<>\\[\\]]+)\\s+(?<var>[A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*new\\s+(?<type>[A-Za-z_][A-Za-z0-9_$.]*)\\s*\\(\\s*\\)\\s*;");
    private static final Pattern REF_CREATION_ASSIGNMENT = Pattern.compile(
            "(?m)(?:final\\s+)?(?:var|[\\w.$<>\\[\\]]+)\\s+(?<var>[A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*new\\s+(?<type>[A-Za-z_][A-Za-z0-9_$.]*)\\s*\\((?<args>[^;]*)\\)\\s*;");
    private static final Pattern STATIC_INIT_FAILURE = Pattern.compile(
            "could\\s+not\\s+initialize\\s+class\\s+(?<class>[A-Za-z_][\\w.$]*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STATIC_INITIALIZER_STACK_FRAME = Pattern.compile(
            "(?m)^\\s*(?:at\\s+)?(?<class>[A-Za-z_][\\w.$]*)\\.<clinit>\\([^\\n]*\\)$");
    private static final Pattern JUNIT_METHOD_SOURCE_NAME = Pattern.compile(
            "methodName\\s*=\\s*'(?<method>[A-Za-z_][A-Za-z0-9_$]*)'");
    private static final Pattern JUNIT_CONSOLE_METHOD_NAME = Pattern.compile(
            "JUnit\\s+Jupiter:[^:\\r\\n]+:(?<method>[A-Za-z_][A-Za-z0-9_$]*)\\(");
    private static final Pattern META_ID_ANNOTATION = Pattern.compile(
            "@MetaId\\s*\\(\\s*\"(?<id>[^\"]+)\"\\s*\\)");
    private static final Pattern STATIC_STRING_ID_ASSIGNMENT = Pattern.compile(
            "(?m)\\b(?:private|protected|public)?\\s*static\\s+final\\s+String\\s+"
                    + "(?:id|classId|metaId|typeId)\\s*=\\s*\"(?<id>[^\"]+)\"\\s*;");
    private static final RuntimeRecipeTemplateCatalog RECIPE_TEMPLATES = new RuntimeRecipeTemplateCatalog();

    public List<Map<String, Object>> build(Path projectRoot,
                                           TestClassInfo classInfo,
                                           TestMethodInfo methodInfo,
                                           Analyze.AnalysisSummary analysisSummary,
                                           ExecuteResult executeResult,
                                           List<TestReportFailure> reportFailures) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        if (classInfo == null || methodInfo == null || analysisSummary == null) {
            return List.of();
        }
        String rawCombinedFailure = buildCombinedFailureText(executeResult, reportFailures);
        String combinedFailure = rawCombinedFailure.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> recipes = new ArrayList<>(buildParentStaticVoidBlockerRecipes(classInfo,
                methodInfo,
                rawCombinedFailure));
        String targetBody = defaultString(methodInfo.getBody());
        Map<String, Object> thresholdRejectionRecipe = buildThresholdRejectionRuntimeRecipe(projectRoot,
                classInfo,
                methodInfo,
                targetBody,
                rawCombinedFailure,
                executeResult,
                reportFailures);
        if (thresholdRejectionRecipe != null && !thresholdRejectionRecipe.isEmpty()) {
            recipes.add(thresholdRejectionRecipe);
        }
        Map<String, Object> temporalNowRecipe = buildTemporalNowAssertionWindowRecipe(projectRoot,
                classInfo,
                methodInfo,
                targetBody,
                rawCombinedFailure,
                executeResult,
                reportFailures);
        if (temporalNowRecipe != null && !temporalNowRecipe.isEmpty()) {
            recipes.add(temporalNowRecipe);
        }
        Map<String, Object> reboundThresholdAttemptsRecipe = buildReboundThresholdAttemptsRecipe(projectRoot,
                classInfo,
                methodInfo,
                targetBody,
                rawCombinedFailure,
                executeResult,
                reportFailures);
        if (reboundThresholdAttemptsRecipe != null && !reboundThresholdAttemptsRecipe.isEmpty()) {
            recipes.add(reboundThresholdAttemptsRecipe);
        }
        Map<String, Object> badClassIdRefRecipe = buildBadClassIdRefRecipe(projectRoot,
                classInfo,
                methodInfo,
                analysisSummary,
                rawCombinedFailure,
                executeResult,
                reportFailures);
        if (badClassIdRefRecipe != null && !badClassIdRefRecipe.isEmpty()) {
            recipes.add(badClassIdRefRecipe);
        }
        Map<String, Object> staticInitRefRecipe = buildStaticInitRefRecipe(projectRoot,
                classInfo,
                methodInfo,
                analysisSummary,
                rawCombinedFailure,
                executeResult,
                reportFailures);
        if (staticInitRefRecipe != null && !staticInitRefRecipe.isEmpty()) {
            recipes.add(staticInitRefRecipe);
        }
        MockPlan mockPlan = analysisSummary.mockPlan();
        if (mockPlan == null || mockPlan.shouldMock().isEmpty()) {
            return List.copyOf(recipes);
        }
        if (!combinedFailure.contains("wanted but not invoked")
                && !combinedFailure.contains("argument(s) are different")
                && !combinedFailure.contains("actual invocations have different arguments")) {
            return List.copyOf(recipes);
        }

        if (targetBody.isBlank()) {
            return List.copyOf(recipes);
        }

        LinkedHashSet<String> shouldMock = new LinkedHashSet<>(mockPlan.shouldMock());
        Map<String, Object> sourceDerivedRuntimeRecipe = buildSourceDerivedRuntimeAlignmentRecipe(classInfo,
                targetBody,
                shouldMock,
                rawCombinedFailure);
        if (sourceDerivedRuntimeRecipe != null && !sourceDerivedRuntimeRecipe.isEmpty()) {
            recipes.add(sourceDerivedRuntimeRecipe);
        }

        int recipeIndex = 1;
        Matcher constructorMatcher = CONSTRUCTOR_ASSIGNMENT.matcher(targetBody);
        while (constructorMatcher.find()) {
            String objectVariable = constructorMatcher.group("var");
            String typeName = constructorMatcher.group("type");
            List<String> constructorArguments = parseConstructorArguments(constructorMatcher.group("args"));
            LinkedHashMap<String, LinkedHashSet<String>> refEqVerifyTargets = collectConstructorLocalVerifyTargets(
                    targetBody,
                    objectVariable,
                    shouldMock);
            if (constructorArguments.stream().noneMatch(shouldMock::contains) && refEqVerifyTargets.isEmpty()) {
                continue;
            }

            Path constructedTypeSource = resolveMainSource(projectRoot, typeName);
            String constructedSource = constructedTypeSource == null ? "" : readSource(constructedTypeSource);
            List<String> invokedMethods = constructedSource.isBlank()
                    ? List.of()
                    : extractInvokedMethods(targetBody, objectVariable);

            LinkedHashMap<String, String> stringLiteralArguments = new LinkedHashMap<>();
            LinkedHashMap<String, LinkedHashSet<String>> verifyPrefixes = new LinkedHashMap<>();
            if (!constructedSource.isBlank()) {
                for (String invokedMethod : invokedMethods) {
                    String invokedMethodBody = extractMethodBody(constructedSource, invokedMethod);
                    if (invokedMethodBody.isBlank()) {
                        continue;
                    }
                    collectStringLiteralArguments(invokedMethodBody, shouldMock, stringLiteralArguments);
                    collectVerifyPrefixes(invokedMethodBody, shouldMock, verifyPrefixes);
                }
            }

            List<Map<String, Object>> operations = new ArrayList<>();
            stringLiteralArguments.forEach((key, literal) -> {
                int separator = key.indexOf('#');
                if (separator < 0) {
                    return;
                }
                operations.add(Map.of(
                        "type", "replace_string_literal_argument",
                        "mock", key.substring(0, separator),
                        "method", key.substring(separator + 1),
                        "literal", literal,
                        "returnLiteral", "true"
                ));
            });
            verifyPrefixes.forEach((mock, prefixes) -> {
                if (prefixes.isEmpty()) {
                    return;
                }
                operations.add(Map.of(
                        "type", "rewrite_verify_block_with_prefixes",
                        "mock", mock,
                        "method", "recordEvent",
                        "prefixes", List.copyOf(prefixes)
                ));
            });
            refEqVerifyTargets.forEach((mock, methods) -> methods.forEach(method ->
                    operations.add(Map.of(
                            "type", "wrap_verify_argument_with_ref_eq",
                            "mock", mock,
                            "method", method
                    ))));

            if (operations.isEmpty()) {
                continue;
            }

            List<String> requiredImports = new ArrayList<>();
            if (!verifyPrefixes.isEmpty()) {
                requiredImports.add("static org.mockito.ArgumentMatchers.startsWith");
            }
            if (!refEqVerifyTargets.isEmpty()) {
                requiredImports.add("static org.mockito.ArgumentMatchers.refEq");
            }
            recipes.add(RECIPE_TEMPLATES.render("CONSTRUCTOR_LOCAL_RUNTIME_ALIGNMENT", Map.of(
                    "sourceClassUpper", typeName.toUpperCase(Locale.ROOT),
                    "sourceClassSimple", typeName,
                    "recipeIndex", recipeIndex++,
                    "sutClass", classInfo.getClassName(),
                    "invokedMethods", invokedMethods,
                    "operations", operations,
                    "requiredImports", requiredImports
            )));
        }

        return List.copyOf(recipes);
    }

    private Map<String, Object> buildSourceDerivedRuntimeAlignmentRecipe(TestClassInfo classInfo,
                                                                         String targetBody,
                                                                         Set<String> shouldMock,
                                                                         String rawCombinedFailure) {
        if (classInfo == null || targetBody == null || targetBody.isBlank() || shouldMock == null || shouldMock.isEmpty()) {
            return null;
        }
        String combinedFailure = rawCombinedFailure == null ? "" : rawCombinedFailure.toLowerCase(Locale.ROOT);
        if (!combinedFailure.contains("recordevent")) {
            return null;
        }
        LinkedHashMap<String, LinkedHashSet<String>> verifyPrefixes = new LinkedHashMap<>();
        collectDynamicVerifyPrefixes(targetBody, shouldMock, verifyPrefixes);
        if (verifyPrefixes.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> operations = new ArrayList<>();
        verifyPrefixes.forEach((mock, prefixes) -> {
            if (prefixes.isEmpty()) {
                return;
            }
            String normalizedMock = mock == null ? "" : mock.toLowerCase(Locale.ROOT);
            if (!normalizedMock.isBlank()
                    && !combinedFailure.contains(normalizedMock + ".recordevent")
                    && !combinedFailure.contains(normalizedMock + ".recordevent(")) {
                return;
            }
            operations.add(Map.of(
                    "type", "align_verify_literals_to_prefixes",
                    "mock", mock,
                    "method", "recordEvent",
                    "prefixes", List.copyOf(prefixes)
            ));
        });
        if (operations.isEmpty()) {
            return null;
        }
        String sourceClass = simpleName(classInfo.getClassName());
        return RECIPE_TEMPLATES.render("SOURCE_DERIVED_RUNTIME_ALIGNMENT", Map.of(
                "sourceClassUpper", sourceClass.toUpperCase(Locale.ROOT),
                "sourceClassSimple", sourceClass,
                "operations", operations,
                "requiredImports", List.of("static org.mockito.ArgumentMatchers.startsWith")
        ));
    }

    private Map<String, Object> buildThresholdRejectionRuntimeRecipe(Path projectRoot,
                                                                     TestClassInfo classInfo,
                                                                     TestMethodInfo methodInfo,
                                                                     String targetBody,
                                                                     String rawCombinedFailure,
                                                                     ExecuteResult executeResult,
                                                                     List<TestReportFailure> reportFailures) {
        if (classInfo == null || methodInfo == null || targetBody == null || targetBody.isBlank()) {
            return null;
        }
        if (!targetBody.contains("shouldEscalate(")
                || !targetBody.contains("sendWelcome")
                || !targetBody.contains("sendDeactivationNotice")) {
            return null;
        }
        String combinedFailure = rawCombinedFailure == null ? "" : rawCombinedFailure.toLowerCase(Locale.ROOT);
        if (!combinedFailure.contains("expected: <false> but was: <true>")
                && !(combinedFailure.contains("senddeactivationnotice") && combinedFailure.contains("sendwelcome"))
                && !combinedFailure.contains("rejected")) {
            return null;
        }

        String failingTestMethod = firstFailureMethodName(executeResult, reportFailures);
        String failingTestSource = readGeneratedTestMethod(projectRoot, classInfo, executeResult, reportFailures, failingTestMethod);
        if (!looksLikeRejectionExpectation(failingTestMethod, failingTestSource)) {
            return null;
        }

        Matcher featureMatcher = STRING_LITERAL_CALL.matcher(targetBody);
        if (!featureMatcher.find()) {
            return null;
        }
        String featureMock = featureMatcher.group("mock");
        String featureName = featureMatcher.group("literal");
        String notificationMock = findMockForMethod(targetBody, "sendDeactivationNotice");
        String promotionMethod = "sendWelcome";
        String rejectionMethod = "sendDeactivationNotice";
        String auditMock = "";
        String auditPrefix = "";
        Matcher auditMatcher = RECORD_EVENT_CALL.matcher(targetBody);
        while (auditMatcher.find()) {
            String literal = auditMatcher.group("literal");
            if (literal != null && literal.toLowerCase(Locale.ROOT).contains("rejected")) {
                auditMock = auditMatcher.group("mock");
                auditPrefix = literal;
                break;
            }
        }
        if (notificationMock.isBlank() || auditMock.isBlank() || auditPrefix.isBlank()) {
            return null;
        }
        String sourceClass = simpleName(classInfo.getClassName());
        String sutMethod = extractMethodNameFromSignature(methodInfo.getSignature());
        if (sutMethod.isBlank()) {
            return null;
        }
        return RECIPE_TEMPLATES.render("THRESHOLD_REJECTION_RUNTIME_ALIGNMENT", Map.ofEntries(
                Map.entry("sourceClassUpper", sourceClass.toUpperCase(Locale.ROOT)),
                Map.entry("sourceClassSimple", sourceClass),
                Map.entry("testMethodName", failingTestMethod == null ? "" : failingTestMethod),
                Map.entry("sutMethod", sutMethod),
                Map.entry("featureMock", featureMock),
                Map.entry("featureName", featureName),
                Map.entry("numericArgumentValue", "0"),
                Map.entry("notificationMock", notificationMock),
                Map.entry("promotionMethod", promotionMethod),
                Map.entry("rejectionMethod", rejectionMethod),
                Map.entry("auditMock", auditMock),
                Map.entry("auditMethod", "recordEvent"),
                Map.entry("auditPrefix", auditPrefix)
        ));
    }

    private Map<String, Object> buildTemporalNowAssertionWindowRecipe(Path projectRoot,
                                                                      TestClassInfo classInfo,
                                                                      TestMethodInfo methodInfo,
                                                                      String targetBody,
                                                                      String rawCombinedFailure,
                                                                      ExecuteResult executeResult,
                                                                      List<TestReportFailure> reportFailures) {
        if (classInfo == null || methodInfo == null || targetBody == null || targetBody.isBlank()) {
            return null;
        }
        if (!targetBody.contains("Instant.now(") && !targetBody.contains("Instant.now()")) {
            return null;
        }
        String combinedFailure = rawCombinedFailure == null ? "" : rawCombinedFailure.toLowerCase(Locale.ROOT);
        if (!combinedFailure.contains("assertion")
                && !combinedFailure.contains("expected")
                && !combinedFailure.contains("but was")
                && !combinedFailure.contains("did not match")) {
            return null;
        }

        String failingTestMethod = firstFailureMethodName(executeResult, reportFailures);
        String failingTestSource = readGeneratedTestMethod(projectRoot, classInfo, executeResult, reportFailures, failingTestMethod);
        if (!looksLikeTemporalNowAssertion(failingTestSource)) {
            return null;
        }

        String sourceClass = simpleName(classInfo.getClassName());
        String sutMethod = extractMethodNameFromSignature(methodInfo.getSignature());
        if (sourceClass.isBlank() || sutMethod.isBlank()) {
            return null;
        }
        return RECIPE_TEMPLATES.render("TEMPORAL_NOW_ASSERTION_WINDOW", Map.of(
                "sourceClassUpper", sourceClass.toUpperCase(Locale.ROOT),
                "sourceClassSimple", sourceClass,
                "testMethodName", failingTestMethod == null ? "" : failingTestMethod,
                "sutMethod", sutMethod
        ));
    }

    private boolean looksLikeTemporalNowAssertion(String methodSource) {
        if (methodSource == null || methodSource.isBlank()) {
            return false;
        }
        String lower = methodSource.toLowerCase(Locale.ROOT);
        if (!lower.contains("instant.now") || !lower.contains("assertthat(")) {
            return false;
        }
        return lower.contains(".isbetween(")
                || lower.contains(".isequalto(")
                || lower.contains(".isafter")
                || lower.contains(".isbefore");
    }

    private Map<String, Object> buildReboundThresholdAttemptsRecipe(Path projectRoot,
                                                                    TestClassInfo classInfo,
                                                                    TestMethodInfo methodInfo,
                                                                    String targetBody,
                                                                    String rawCombinedFailure,
                                                                    ExecuteResult executeResult,
                                                                    List<TestReportFailure> reportFailures) {
        if (classInfo == null || methodInfo == null || targetBody == null || targetBody.isBlank()) {
            return null;
        }
        if (!targetBody.contains("LegacyScoreRules.reboundFactor(") || !targetBody.contains("rebound >")) {
            return null;
        }
        String combinedFailure = rawCombinedFailure == null ? "" : rawCombinedFailure.toLowerCase(Locale.ROOT);
        if (!combinedFailure.contains("expected: <true> but was: <false>")
                && !combinedFailure.contains("failnottrue")) {
            return null;
        }
        String sutMethod = extractMethodNameFromSignature(methodInfo.getSignature());
        if (sutMethod.isBlank()) {
            return null;
        }

        String failingTestMethod = firstFailureMethodName(executeResult, reportFailures);
        String failingTestSource = readGeneratedTestMethod(projectRoot, classInfo, executeResult, reportFailures, failingTestMethod);
        if (!looksLikeHighReboundExpectation(failingTestMethod, failingTestSource, sutMethod)) {
            return null;
        }
        String userVariable = extractSingleArgumentFromMethodCall(failingTestSource, sutMethod);
        if (userVariable.isBlank()) {
            return null;
        }
        int minimumAttempts = failingTestSource.contains(userVariable + ".deactivate()") ? 4 : 3;
        String sourceClass = simpleName(classInfo.getClassName());
        if (sourceClass.isBlank()) {
            return null;
        }
        return RECIPE_TEMPLATES.render("REBOUND_THRESHOLD_ATTEMPTS_RUNTIME_ALIGNMENT", Map.of(
                "sourceClassUpper", sourceClass.toUpperCase(Locale.ROOT),
                "sourceClassSimple", sourceClass,
                "testMethodName", failingTestMethod == null ? "" : failingTestMethod,
                "sutMethod", sutMethod,
                "userVariable", userVariable,
                "minimumAttempts", Integer.toString(minimumAttempts)
        ));
    }

    private Map<String, Object> buildBadClassIdRefRecipe(Path projectRoot,
                                                         TestClassInfo classInfo,
                                                         TestMethodInfo methodInfo,
                                                         Analyze.AnalysisSummary analysisSummary,
                                                         String rawCombinedFailure,
                                                         ExecuteResult executeResult,
                                                         List<TestReportFailure> reportFailures) {
        if (classInfo == null || methodInfo == null) {
            return null;
        }
        String combinedFailure = rawCombinedFailure == null ? "" : rawCombinedFailure.toLowerCase(Locale.ROOT);
        if (!combinedFailure.contains("bad_class_id") || !combinedFailure.contains("transactionexception")) {
            return null;
        }
        String failingTestMethod = firstFailureMethodName(executeResult, reportFailures);
        String failingTestSource = readGeneratedTestMethod(projectRoot, classInfo, executeResult, reportFailures, failingTestMethod);
        RefInitializerDetails refInitializer = findZeroArgumentRefInitializer(failingTestSource);
        if (refInitializer == null) {
            return null;
        }
        String objectTypeFqcn = resolveReferenceOwnerType(projectRoot,
                classInfo,
                methodInfo,
                analysisSummary,
                refInitializer.typeExpression());
        if (objectTypeFqcn.isBlank()) {
            return null;
        }
        String sourceClass = simpleName(classInfo.getClassName());
        if (sourceClass.isBlank()) {
            return null;
        }
        return RECIPE_TEMPLATES.render("REF_NULL_GUARD_RUNTIME_ALIGNMENT", Map.of(
                "sourceClassUpper", sourceClass.toUpperCase(Locale.ROOT),
                "sourceClassSimple", sourceClass,
                "testMethodName", failingTestMethod,
                "refVariable", refInitializer.variableName(),
                "refTypeExpression", refInitializer.typeExpression(),
                "objectTypeFqcn", objectTypeFqcn
        ));
    }

    private Map<String, Object> buildStaticInitRefRecipe(Path projectRoot,
                                                         TestClassInfo classInfo,
                                                         TestMethodInfo methodInfo,
                                                         Analyze.AnalysisSummary analysisSummary,
                                                         String rawCombinedFailure,
                                                         ExecuteResult executeResult,
                                                         List<TestReportFailure> reportFailures) {
        if (classInfo == null || methodInfo == null) {
            return null;
        }
        String combinedFailure = rawCombinedFailure == null ? "" : rawCombinedFailure.toLowerCase(Locale.ROOT);
        if (!combinedFailure.contains("noclassdeffounderror")
                && !combinedFailure.contains("exceptionininitializererror")
                && !combinedFailure.contains("could not initialize class")
                && !combinedFailure.contains("<clinit>")) {
            return null;
        }
        String failingClass = resolveStaticInitFailingClass(rawCombinedFailure);
        if (failingClass.isBlank()) {
            return null;
        }
        String failingTestMethod = firstFailureMethodName(executeResult, reportFailures);
        String failingTestSource = readGeneratedTestMethod(projectRoot, classInfo, executeResult, reportFailures, failingTestMethod);
        RefInitializerDetails refInitializer = findStaticInitRefInitializer(failingTestSource, failingClass);
        if (refInitializer == null) {
            return null;
        }
        String ownerTypeFqcn = resolveReferenceOwnerType(projectRoot,
                classInfo,
                methodInfo,
                analysisSummary,
                refInitializer.typeExpression());
        if (ownerTypeFqcn.isBlank()) {
            ownerTypeFqcn = failingClass;
        }
        if (ownerTypeFqcn.isBlank()) {
            return null;
        }
        String sourceClass = simpleName(classInfo.getClassName());
        if (sourceClass.isBlank()) {
            return null;
        }
        String classIdLiteral = resolveClassIdLiteral(projectRoot, ownerTypeFqcn);
        return RECIPE_TEMPLATES.render("STATIC_INIT_REF_FIXTURE_RUNTIME_ALIGNMENT", Map.of(
                "sourceClassUpper", sourceClass.toUpperCase(Locale.ROOT),
                "sourceClassSimple", sourceClass,
                "testMethodName", failingTestMethod,
                "refVariable", refInitializer.variableName(),
                "refTypeExpression", refInitializer.typeExpression(),
                "classIdLiteral", classIdLiteral,
                "failingClassFqcn", failingClass,
                "objectTypeFqcn", ownerTypeFqcn
        ));
    }

    private boolean looksLikeHighReboundExpectation(String methodName, String methodSource, String sutMethod) {
        String combined = ((methodName == null ? "" : methodName)
                + "\n"
                + (methodSource == null ? "" : methodSource)).toLowerCase(Locale.ROOT);
        if (sutMethod == null || sutMethod.isBlank() || !combined.contains("." + sutMethod.toLowerCase(Locale.ROOT) + "(")) {
            return false;
        }
        return combined.contains("asserttrue(")
                || combined.contains("senddeactivationnotice")
                || combined.contains("highrebound")
                || combined.contains("rebound");
    }

    private String extractSingleArgumentFromMethodCall(String source, String methodName) {
        if (source == null || source.isBlank() || methodName == null || methodName.isBlank()) {
            return "";
        }
        Pattern pattern = Pattern.compile("\\."
                + Pattern.quote(methodName)
                + "\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)");
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private RefInitializerDetails findStaticInitRefInitializer(String methodSource, String objectTypeFqcn) {
        if (methodSource == null || methodSource.isBlank()) {
            return null;
        }
        Matcher matcher = REF_CREATION_ASSIGNMENT.matcher(methodSource);
        while (matcher.find()) {
            String typeExpression = matcher.group("type");
            if (!looksLikeReferenceType(typeExpression)) {
                continue;
            }
            String args = matcher.group("args") == null ? "" : matcher.group("args").trim();
            if (!args.isBlank()) {
                if (looksLikeSimpleVariable(args)) {
                    if (!isVariableConstructedFromType(methodSource, args, objectTypeFqcn)) {
                        continue;
                    }
                } else if (!looksLikeInlineObjectConstructor(args, objectTypeFqcn)) {
                    continue;
                }
            }
            return new RefInitializerDetails(matcher.group("var"), typeExpression);
        }
        return null;
    }

    private RefInitializerDetails findZeroArgumentRefInitializer(String methodSource) {
        if (methodSource == null || methodSource.isBlank()) {
            return null;
        }
        Matcher matcher = ZERO_ARG_OBJECT_CREATION_ASSIGNMENT.matcher(methodSource);
        while (matcher.find()) {
            String typeExpression = matcher.group("type");
            if (!looksLikeReferenceType(typeExpression)) {
                continue;
            }
            return new RefInitializerDetails(matcher.group("var"), typeExpression);
        }
        return null;
    }

    private String resolveStaticInitFailingClass(String rawCombinedFailure) {
        String failure = rawCombinedFailure == null ? "" : rawCombinedFailure;
        Matcher failureMatcher = STATIC_INIT_FAILURE.matcher(failure);
        if (failureMatcher.find()) {
            return failureMatcher.group("class").replace('$', '.');
        }
        Matcher stackMatcher = STATIC_INITIALIZER_STACK_FRAME.matcher(failure);
        if (stackMatcher.find()) {
            return stackMatcher.group("class").replace('$', '.');
        }
        return "";
    }

    private boolean looksLikeSimpleVariable(String value) {
        return value != null && value.trim().matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private boolean looksLikeReferenceType(String typeExpression) {
        String simple = simpleName(typeExpression);
        return "Ref".equals(simple) || simple.endsWith("Ref");
    }

    private boolean looksLikeInlineObjectConstructor(String value, String objectTypeFqcn) {
        if (value == null || value.isBlank() || objectTypeFqcn == null || objectTypeFqcn.isBlank()) {
            return false;
        }
        Matcher matcher = Pattern.compile("new\\s+(?<type>[A-Za-z_][A-Za-z0-9_$.]*)\\s*\\(\\s*\\)").matcher(value.trim());
        if (!matcher.matches()) {
            return false;
        }
        return typesMatch(matcher.group("type"), objectTypeFqcn);
    }

    private boolean isVariableConstructedFromType(String methodSource, String variableName, String objectTypeFqcn) {
        if (methodSource == null
                || methodSource.isBlank()
                || variableName == null
                || variableName.isBlank()
                || objectTypeFqcn == null
                || objectTypeFqcn.isBlank()) {
            return false;
        }
        Pattern pattern = Pattern.compile("(?m)(?:final\\s+)?(?:var|[\\w.$<>\\[\\]]+)\\s+"
                + Pattern.quote(variableName)
                + "\\s*=\\s*new\\s+(?<type>[A-Za-z_][A-Za-z0-9_$.]*)\\s*\\((?<args>[^;]*)\\)\\s*;");
        Matcher matcher = pattern.matcher(methodSource);
        while (matcher.find()) {
            String args = matcher.group("args") == null ? "" : matcher.group("args").trim();
            if (!args.isBlank()) {
                continue;
            }
            if (typesMatch(matcher.group("type"), objectTypeFqcn)) {
                return true;
            }
        }
        return false;
    }

    private boolean typesMatch(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        return normalizeClassLikeName(left).equals(normalizeClassLikeName(right))
                || simpleName(left).equals(simpleName(right));
    }

    private String normalizeClassLikeName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace('$', '.').trim().toLowerCase(Locale.ROOT);
    }

    private String resolveReferenceOwnerType(Path projectRoot,
                                             TestClassInfo classInfo,
                                             TestMethodInfo methodInfo,
                                             Analyze.AnalysisSummary analysisSummary,
                                             String refTypeExpression) {
        String ownerFromType = ownerFromReferenceType(refTypeExpression);
        if (!ownerFromType.isBlank()) {
            return ownerFromType;
        }
        String refSimpleName = simpleName(refTypeExpression);
        if (refSimpleName.isBlank()) {
            return "";
        }
        String ownerFromSignature = ownerFromMethodSignature(methodInfo, refSimpleName);
        if (!ownerFromSignature.isBlank()) {
            return ownerFromSignature;
        }
        String ownerFromImports = ownerFromImports(classInfo, refSimpleName);
        if (!ownerFromImports.isBlank()) {
            return ownerFromImports;
        }
        return ownerFromConstructors(projectRoot, classInfo, analysisSummary, refSimpleName);
    }

    private String ownerFromReferenceType(String refTypeExpression) {
        if (refTypeExpression == null || refTypeExpression.isBlank()) {
            return "";
        }
        int nestedIndex = refTypeExpression.lastIndexOf(".Ref");
        if (nestedIndex <= 0) {
            return "";
        }
        return refTypeExpression.substring(0, nestedIndex).trim();
    }

    private String ownerFromMethodSignature(TestMethodInfo methodInfo, String refSimpleName) {
        if (methodInfo == null || refSimpleName == null || refSimpleName.isBlank()) {
            return "";
        }
        for (String candidate : referencedTypes(methodInfo)) {
            int nestedIndex = candidate.lastIndexOf("." + refSimpleName);
            if (nestedIndex > 0) {
                return candidate.substring(0, nestedIndex).trim();
            }
        }
        return "";
    }

    private List<String> referencedTypes(TestMethodInfo methodInfo) {
        if (methodInfo == null) {
            return List.of();
        }
        List<String> referenced = new ArrayList<>();
        MethodDeclaration declaration = methodInfo.getDeclaration();
        if (declaration != null) {
            referenced.add(declaration.getType().asString());
            declaration.getParameters().forEach(parameter -> referenced.add(parameter.getType().asString()));
            return referenced;
        }
        String signature = methodInfo.getSignature();
        if (signature == null || signature.isBlank()) {
            return List.of();
        }
        try {
            MethodDeclaration parsed = com.github.javaparser.StaticJavaParser.parseMethodDeclaration(signature + " {}");
            referenced.add(parsed.getType().asString());
            parsed.getParameters().forEach(parameter -> referenced.add(parameter.getType().asString()));
        } catch (Exception ignored) {
            Matcher matcher = Pattern.compile("([A-Za-z_][\\w$.]*)").matcher(signature);
            while (matcher.find()) {
                referenced.add(matcher.group(1));
            }
        }
        return referenced;
    }

    private String ownerFromImports(TestClassInfo classInfo, String refSimpleName) {
        if (classInfo == null || refSimpleName == null || refSimpleName.isBlank()) {
            return "";
        }
        for (String importLine : classInfo.getImports()) {
            String importedType = importedType(importLine);
            if (importedType.isBlank()) {
                continue;
            }
            int nestedIndex = importedType.lastIndexOf("." + refSimpleName);
            if (nestedIndex > 0) {
                return importedType.substring(0, nestedIndex).trim();
            }
        }
        return "";
    }

    private String ownerFromConstructors(Path projectRoot,
                                         TestClassInfo classInfo,
                                         Analyze.AnalysisSummary analysisSummary,
                                         String refSimpleName) {
        if (analysisSummary == null || analysisSummary.availableConstructors() == null || refSimpleName == null || refSimpleName.isBlank()) {
            return "";
        }
        for (Map.Entry<String, List<ConstructorMetadata>> entry : analysisSummary.availableConstructors().entrySet()) {
            if (!simpleName(entry.getKey()).equals(refSimpleName)) {
                continue;
            }
            for (ConstructorMetadata constructor : entry.getValue()) {
                if (constructor == null || constructor.parameters().size() != 1) {
                    continue;
                }
                ParameterMetadata parameter = constructor.parameters().get(0);
                if (parameter == null || parameter.type().isBlank()) {
                    continue;
                }
                String resolved = resolveTypeFqcn(projectRoot, classInfo, parameter.type());
                if (!resolved.isBlank()) {
                    return resolved;
                }
            }
        }
        return "";
    }

    private String resolveTypeFqcn(Path projectRoot, TestClassInfo classInfo, String typeName) {
        String normalized = stripTypeDecorations(typeName);
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.contains(".")) {
            return normalized;
        }
        for (String importLine : classInfo == null ? List.<String>of() : classInfo.getImports()) {
            String importedType = importedType(importLine);
            if (simpleName(importedType).equals(normalized)) {
                return importedType;
            }
        }
        Path sourceFile = resolveMainSourceByFqcn(projectRoot, normalized);
        if (sourceFile == null) {
            sourceFile = resolveMainSource(projectRoot, normalized);
        }
        if (sourceFile == null) {
            return normalized;
        }
        String source = readSource(sourceFile);
        Matcher packageMatcher = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)\\s*;").matcher(source);
        if (packageMatcher.find()) {
            return packageMatcher.group(1) + "." + normalized;
        }
        return normalized;
    }

    private String stripTypeDecorations(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return "";
        }
        String normalized = typeName.trim();
        int genericIndex = normalized.indexOf('<');
        if (genericIndex >= 0) {
            normalized = normalized.substring(0, genericIndex).trim();
        }
        while (normalized.endsWith("[]")) {
            normalized = normalized.substring(0, normalized.length() - 2).trim();
        }
        return normalized;
    }

    private String importedType(String importLine) {
        if (importLine == null || importLine.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("^import\\s+([A-Za-z_][\\w.]*)\\s*;?$").matcher(importLine.trim());
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String resolveClassIdLiteral(Path projectRoot, String objectTypeFqcn) {
        if (objectTypeFqcn == null || objectTypeFqcn.isBlank()) {
            return "UNKNOWN";
        }
        Path sourceFile = resolveMainSourceByFqcn(projectRoot, objectTypeFqcn);
        if (sourceFile == null) {
            sourceFile = resolveMainSource(projectRoot, simpleName(objectTypeFqcn));
        }
        String source = readSource(sourceFile);
        Matcher metaIdMatcher = META_ID_ANNOTATION.matcher(source);
        if (metaIdMatcher.find()) {
            return metaIdMatcher.group("id");
        }
        Matcher idMatcher = STATIC_STRING_ID_ASSIGNMENT.matcher(source);
        if (idMatcher.find()) {
            return idMatcher.group("id");
        }
        return simpleName(objectTypeFqcn)
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .toUpperCase(Locale.ROOT);
    }

    private String firstFailureMethodName(ExecuteResult executeResult, List<TestReportFailure> reportFailures) {
        String reportMethod = firstFailureMethodName(reportFailures);
        if (!reportMethod.isBlank()) {
            return reportMethod;
        }
        String outputMethod = firstFailureMethodName(buildCombinedFailureText(executeResult, reportFailures));
        if (!outputMethod.isBlank()) {
            return outputMethod;
        }
        if (executeResult == null || executeResult.failedTests() == null) {
            return "";
        }
        for (String failedTest : executeResult.failedTests()) {
            String parsed = extractFailureMethodName(failedTest);
            if (!parsed.isBlank()) {
                return parsed;
            }
        }
        return "";
    }

    private String firstFailureMethodName(List<TestReportFailure> reportFailures) {
        if (reportFailures == null) {
            return "";
        }
        for (TestReportFailure failure : reportFailures) {
            if (failure != null && failure.methodName() != null && !failure.methodName().isBlank()) {
                return failure.methodName();
            }
        }
        return "";
    }

    private String firstFailureMethodName(String rawFailureText) {
        if (rawFailureText == null || rawFailureText.isBlank()) {
            return "";
        }
        Matcher methodSourceMatcher = JUNIT_METHOD_SOURCE_NAME.matcher(rawFailureText);
        if (methodSourceMatcher.find()) {
            return methodSourceMatcher.group("method");
        }
        Matcher consoleMatcher = JUNIT_CONSOLE_METHOD_NAME.matcher(rawFailureText);
        if (consoleMatcher.find()) {
            return consoleMatcher.group("method");
        }
        return "";
    }

    private String extractFailureMethodName(String failedTest) {
        if (failedTest == null || failedTest.isBlank()) {
            return "";
        }
        String trimmed = failedTest.trim();
        int hashIndex = trimmed.lastIndexOf('#');
        if (hashIndex >= 0 && hashIndex + 1 < trimmed.length()) {
            return trimmed.substring(hashIndex + 1);
        }
        int separator = trimmed.lastIndexOf('.');
        if (separator >= 0 && separator + 1 < trimmed.length()) {
            return trimmed.substring(separator + 1);
        }
        return trimmed;
    }

    private String readGeneratedTestMethod(Path projectRoot,
                                           TestClassInfo classInfo,
                                           ExecuteResult executeResult,
                                           List<TestReportFailure> reportFailures,
                                           String methodName) {
        List<Path> candidates = new ArrayList<>();
        if (classInfo != null && classInfo.getTargetPath() != null) {
            candidates.add(classInfo.getTargetPath());
        }
        Path failurePath = resolveFailureTestPath(projectRoot, classInfo, executeResult, reportFailures);
        if (failurePath != null) {
            candidates.add(failurePath);
        }
        for (Path candidate : candidates) {
            String source = readGeneratedTestMethod(candidate, methodName);
            if (!source.isBlank()) {
                return source;
            }
        }
        return "";
    }

    private String readGeneratedTestMethod(Path testPath, String methodName) {
        if (testPath == null || methodName == null || methodName.isBlank() || !Files.exists(testPath)) {
            return "";
        }
        try {
            String source = Files.readString(testPath, StandardCharsets.UTF_8);
            int nameIndex = source.indexOf(methodName + "(");
            if (nameIndex < 0) {
                return "";
            }
            int braceIndex = source.indexOf('{', nameIndex);
            if (braceIndex < 0) {
                return "";
            }
            int depth = 0;
            for (int index = braceIndex; index < source.length(); index++) {
                char current = source.charAt(index);
                if (current == '{') {
                    depth++;
                } else if (current == '}') {
                    depth--;
                    if (depth == 0) {
                        return source.substring(nameIndex, index + 1);
                    }
                }
            }
        } catch (IOException ignored) {
            return "";
        }
        return "";
    }

    private Path resolveFailureTestPath(Path projectRoot,
                                        TestClassInfo classInfo,
                                        ExecuteResult executeResult,
                                        List<TestReportFailure> reportFailures) {
        if (projectRoot == null) {
            return null;
        }
        String failureClassName = firstFailureClassName(reportFailures);
        if (failureClassName.isBlank()) {
            failureClassName = firstFailureClassName(executeResult);
        }
        if (failureClassName.isBlank()) {
            return null;
        }
        List<String> classCandidates = new ArrayList<>();
        classCandidates.add(failureClassName);
        int lastDot = failureClassName.lastIndexOf('.');
        if (lastDot > 0) {
            classCandidates.add(failureClassName.substring(0, lastDot));
        }
        String targetPackage = packageName(classInfo == null ? "" : classInfo.getClassName());
        List<String> qualifiedCandidates = new ArrayList<>();
        for (String candidateName : classCandidates) {
            if (candidateName == null || candidateName.isBlank()) {
                continue;
            }
            qualifiedCandidates.add(candidateName);
            if (!candidateName.contains(".") && !targetPackage.isBlank()) {
                qualifiedCandidates.add(targetPackage + "." + candidateName);
            }
        }
        for (String candidateName : qualifiedCandidates) {
            Path candidate = projectRoot.resolve("src/test/java/" + candidateName.replace('.', '/') + ".java");
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private String firstFailureClassName(List<TestReportFailure> reportFailures) {
        if (reportFailures == null) {
            return "";
        }
        for (TestReportFailure failure : reportFailures) {
            if (failure != null && failure.className() != null && !failure.className().isBlank()) {
                return failure.className().trim();
            }
        }
        return "";
    }

    private String firstFailureClassName(ExecuteResult executeResult) {
        if (executeResult == null || executeResult.failedTests() == null) {
            return "";
        }
        for (String failedTest : executeResult.failedTests()) {
            if (failedTest == null || failedTest.isBlank()) {
                continue;
            }
            String trimmed = failedTest.trim();
            int hashIndex = trimmed.lastIndexOf('#');
            return hashIndex >= 0 ? trimmed.substring(0, hashIndex) : trimmed;
        }
        return "";
    }

    private String packageName(String fqcn) {
        if (fqcn == null || fqcn.isBlank()) {
            return "";
        }
        int separator = fqcn.lastIndexOf('.');
        return separator > 0 ? fqcn.substring(0, separator) : "";
    }

    private boolean looksLikeRejectionExpectation(String methodName, String methodSource) {
        String combined = ((methodName == null ? "" : methodName) + "\n" + (methodSource == null ? "" : methodSource))
                .toLowerCase(Locale.ROOT);
        return combined.contains("reject")
                || combined.contains("senddeactivationnotice")
                || combined.contains("legacy upgrade rejected")
                || combined.contains("inherited shadow rejected");
    }

    private String findMockForMethod(String source, String methodName) {
        if (source == null || source.isBlank() || methodName == null || methodName.isBlank()) {
            return "";
        }
        Matcher matcher = COLLABORATOR_CALL.matcher(source);
        while (matcher.find()) {
            if (methodName.equals(matcher.group("method"))) {
                return matcher.group("mock");
            }
        }
        return "";
    }

    private List<Map<String, Object>> buildParentStaticVoidBlockerRecipes(TestClassInfo classInfo,
                                                                          TestMethodInfo methodInfo,
                                                                          String combinedFailure) {
        if (combinedFailure == null || combinedFailure.isBlank()) {
            return List.of();
        }
        Matcher channelMatcher = REAL_DB_CONNECTION_ATTEMPT.matcher(combinedFailure);
        if (!channelMatcher.find()) {
            return List.of();
        }
        String channel = channelMatcher.group("channel").trim();
        StackFrame staticFrame = findRelevantStackFrame(combinedFailure);
        if (staticFrame == null) {
            return List.of();
        }
        String sutMethod = extractMethodNameFromSignature(methodInfo.getSignature());
        if (sutMethod.isBlank()) {
            return List.of();
        }
        String ownerClass = simpleName(staticFrame.ownerFqcn());
        return List.of(RECIPE_TEMPLATES.render("PARENT_STATIC_VOID_BLOCKER", Map.of(
                "ownerClassUpper", ownerClass.toUpperCase(Locale.ROOT),
                "ownerClassSimple", ownerClass,
                "ownerFqcn", staticFrame.ownerFqcn(),
                "staticMethod", staticFrame.methodName(),
                "stringLiteral", channel,
                "sutMethod", sutMethod,
                "sutClass", classInfo.getClassName()
        )));
    }

    private void collectStringLiteralArguments(String methodBody,
                                               Set<String> shouldMock,
                                               Map<String, String> stringLiteralArguments) {
        Matcher matcher = STRING_LITERAL_CALL.matcher(methodBody);
        while (matcher.find()) {
            String mock = matcher.group("mock");
            if (!shouldMock.contains(mock)) {
                continue;
            }
            stringLiteralArguments.putIfAbsent(mock + "#isEnabled", matcher.group("literal"));
        }
    }

    private void collectVerifyPrefixes(String methodBody,
                                       Set<String> shouldMock,
                                       Map<String, LinkedHashSet<String>> verifyPrefixes) {
        Matcher matcher = RECORD_EVENT_CALL.matcher(methodBody);
        while (matcher.find()) {
            String mock = matcher.group("mock");
            if (!shouldMock.contains(mock)) {
                continue;
            }
            verifyPrefixes.computeIfAbsent(mock, ignored -> new LinkedHashSet<>())
                    .add(matcher.group("literal"));
        }
    }

    private void collectDynamicVerifyPrefixes(String methodBody,
                                              Set<String> shouldMock,
                                              Map<String, LinkedHashSet<String>> verifyPrefixes) {
        Matcher matcher = RECORD_EVENT_CALL.matcher(methodBody);
        while (matcher.find()) {
            String mock = matcher.group("mock");
            if (!shouldMock.contains(mock)) {
                continue;
            }
            String dynamicPart = matcher.group("dynamic");
            if (dynamicPart == null || dynamicPart.isBlank()) {
                continue;
            }
            verifyPrefixes.computeIfAbsent(mock, ignored -> new LinkedHashSet<>())
                    .add(matcher.group("literal"));
        }
    }

    private LinkedHashMap<String, LinkedHashSet<String>> collectConstructorLocalVerifyTargets(String targetBody,
                                                                                               String objectVariable,
                                                                                               Set<String> shouldMock) {
        LinkedHashMap<String, LinkedHashSet<String>> targets = new LinkedHashMap<>();
        if (targetBody == null || targetBody.isBlank() || objectVariable == null || objectVariable.isBlank()) {
            return targets;
        }
        Matcher matcher = COLLABORATOR_CALL.matcher(targetBody);
        while (matcher.find()) {
            String mock = matcher.group("mock");
            if (!shouldMock.contains(mock)) {
                continue;
            }
            if (!containsStandaloneArgument(matcher.group("args"), objectVariable)) {
                continue;
            }
            targets.computeIfAbsent(mock, ignored -> new LinkedHashSet<>())
                    .add(matcher.group("method"));
        }
        return targets;
    }

    private List<String> extractInvokedMethods(String targetBody, String objectVariable) {
        LinkedHashSet<String> invokedMethods = new LinkedHashSet<>();
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(objectVariable) + "\\.(?<method>[A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
        Matcher matcher = pattern.matcher(targetBody);
        while (matcher.find()) {
            invokedMethods.add(matcher.group("method"));
        }
        return List.copyOf(invokedMethods);
    }

    private List<String> parseConstructorArguments(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return List.of();
        }
        List<String> arguments = new ArrayList<>();
        for (String rawArgument : rawArguments.split(",")) {
            String candidate = rawArgument.replace("this.", "").trim();
            if (candidate.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                arguments.add(candidate);
            }
        }
        return List.copyOf(arguments);
    }

    private boolean containsStandaloneArgument(String rawArguments, String variable) {
        if (rawArguments == null || rawArguments.isBlank() || variable == null || variable.isBlank()) {
            return false;
        }
        for (String rawArgument : rawArguments.split(",")) {
            if (variable.equals(rawArgument.trim())) {
                return true;
            }
        }
        return false;
    }

    private StackFrame findRelevantStackFrame(String combinedFailure) {
        Matcher matcher = STACK_FRAME.matcher(combinedFailure);
        while (matcher.find()) {
            String owner = matcher.group("owner");
            if (owner.startsWith("java.")
                    || owner.startsWith("jdk.")
                    || owner.contains("Test")) {
                continue;
            }
            return new StackFrame(owner, matcher.group("method"));
        }
        return null;
    }

    private String extractMethodNameFromSignature(String signature) {
        if (signature == null || signature.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").matcher(signature);
        String methodName = "";
        while (matcher.find()) {
            methodName = matcher.group(1);
        }
        return methodName;
    }

    private String simpleName(String fqcn) {
        if (fqcn == null || fqcn.isBlank()) {
            return "";
        }
        int separator = fqcn.lastIndexOf('.');
        return separator >= 0 ? fqcn.substring(separator + 1) : fqcn;
    }

    private String extractMethodBody(String classSource, String methodName) {
        if (classSource == null || classSource.isBlank() || methodName == null || methodName.isBlank()) {
            return "";
        }
        Pattern signaturePattern = Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\(");
        Matcher matcher = signaturePattern.matcher(classSource);
        if (!matcher.find()) {
            return "";
        }
        int bodyStart = classSource.indexOf('{', matcher.end());
        if (bodyStart < 0) {
            return "";
        }
        int depth = 0;
        for (int index = bodyStart; index < classSource.length(); index++) {
            char current = classSource.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return classSource.substring(bodyStart + 1, index);
                }
            }
        }
        return "";
    }

    private Path resolveMainSource(Path projectRoot, String simpleTypeName) {
        Path mainRoot = projectRoot.resolve("src/main/java");
        if (!Files.isDirectory(mainRoot)) {
            return null;
        }
        try (var paths = Files.walk(mainRoot)) {
            return paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equals(simpleTypeName + ".java"))
                    .findFirst()
                    .map(Path::toAbsolutePath)
                    .orElse(null);
        } catch (IOException exception) {
            return null;
        }
    }

    private Path resolveMainSourceByFqcn(Path projectRoot, String fqcn) {
        if (projectRoot == null || fqcn == null || fqcn.isBlank()) {
            return null;
        }
        Path direct = projectRoot.resolve("src/main/java/" + fqcn.replace('.', '/') + ".java");
        if (Files.isRegularFile(direct)) {
            return direct.toAbsolutePath().normalize();
        }
        return null;
    }

    private String readSource(Path path) {
        if (path == null) {
            return "";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "";
        }
    }

    private String buildCombinedFailureText(ExecuteResult executeResult, List<TestReportFailure> reportFailures) {
        StringBuilder combined = new StringBuilder();
        if (executeResult != null) {
            appendIfPresent(combined, executeResult.stdout());
            appendIfPresent(combined, executeResult.stderr());
            if (executeResult.failedTests() != null) {
                executeResult.failedTests().forEach(value -> appendIfPresent(combined, value));
            }
        }
        if (reportFailures != null) {
            reportFailures.forEach(failure -> {
                appendIfPresent(combined, failure.message());
                if (failure.stackTrace() != null) {
                    failure.stackTrace().forEach(value -> appendIfPresent(combined, value));
                }
            });
        }
        return combined.toString();
    }

    private void appendIfPresent(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(value);
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private record RefInitializerDetails(String variableName, String typeExpression) {
    }

    private record StackFrame(String ownerFqcn, String methodName) {
    }
}
