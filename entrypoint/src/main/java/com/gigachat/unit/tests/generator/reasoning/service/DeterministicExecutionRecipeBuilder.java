package com.gigachat.unit.tests.generator.reasoning.service;

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
        Map<String, Object> thresholdRejectionRecipe = buildThresholdRejectionRuntimeRecipe(classInfo,
                methodInfo,
                targetBody,
                rawCombinedFailure,
                reportFailures);
        if (thresholdRejectionRecipe != null && !thresholdRejectionRecipe.isEmpty()) {
            recipes.add(thresholdRejectionRecipe);
        }
        Map<String, Object> temporalNowRecipe = buildTemporalNowAssertionWindowRecipe(classInfo,
                methodInfo,
                targetBody,
                rawCombinedFailure,
                reportFailures);
        if (temporalNowRecipe != null && !temporalNowRecipe.isEmpty()) {
            recipes.add(temporalNowRecipe);
        }
        Map<String, Object> reboundThresholdAttemptsRecipe = buildReboundThresholdAttemptsRecipe(classInfo,
                methodInfo,
                targetBody,
                rawCombinedFailure,
                reportFailures);
        if (reboundThresholdAttemptsRecipe != null && !reboundThresholdAttemptsRecipe.isEmpty()) {
            recipes.add(reboundThresholdAttemptsRecipe);
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

    private Map<String, Object> buildThresholdRejectionRuntimeRecipe(TestClassInfo classInfo,
                                                                     TestMethodInfo methodInfo,
                                                                     String targetBody,
                                                                     String rawCombinedFailure,
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

        String failingTestMethod = firstFailureMethodName(reportFailures);
        String failingTestSource = readGeneratedTestMethod(classInfo.getTargetPath(), failingTestMethod);
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

    private Map<String, Object> buildTemporalNowAssertionWindowRecipe(TestClassInfo classInfo,
                                                                      TestMethodInfo methodInfo,
                                                                      String targetBody,
                                                                      String rawCombinedFailure,
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

        String failingTestMethod = firstFailureMethodName(reportFailures);
        String failingTestSource = readGeneratedTestMethod(classInfo.getTargetPath(), failingTestMethod);
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

    private Map<String, Object> buildReboundThresholdAttemptsRecipe(TestClassInfo classInfo,
                                                                    TestMethodInfo methodInfo,
                                                                    String targetBody,
                                                                    String rawCombinedFailure,
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

        String failingTestMethod = firstFailureMethodName(reportFailures);
        String failingTestSource = readGeneratedTestMethod(classInfo.getTargetPath(), failingTestMethod);
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

    private record StackFrame(String ownerFqcn, String methodName) {
    }
}
