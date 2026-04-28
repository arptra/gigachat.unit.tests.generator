package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockTarget;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.testagent.entrypoint.pipeline.helpers.analyze.MethodMetadata;

/**
 * Builds a minimal bootstrap test when the model repeatedly returns a placeholder
 * that does not invoke the target method. The generated snippet is intentionally
 * small: it only guarantees a real SUT call and compilable setup so the later
 * compile/execute/coverage loops have something concrete to refine.
 */
public class PlaceholderInvocationFallbackBuilder {

    public GeneratedTestSnippet buildFastPath(TestClassInfo classInfo,
                                              TestMethodInfo methodInfo,
                                              Analyze.AnalysisSummary analysisSummary,
                                              GeneratedTestSnippet invalidSnippet) {
        if (classInfo == null || methodInfo == null || analysisSummary == null || invalidSnippet == null) {
            return null;
        }
        MethodDeclaration declaration = resolveDeclaration(methodInfo);
        if (declaration == null || declaration.getBody().isEmpty()) {
            return null;
        }
        GeneratedTestSnippet staticCollectionSnippet = buildStaticMutableCollectionApiTest(classInfo,
                methodInfo,
                analysisSummary,
                invalidSnippet,
                declaration);
        if (staticCollectionSnippet != null) {
            return staticCollectionSnippet;
        }
        GeneratedTestSnippet caseInsensitiveLookupSnippet = buildCaseInsensitiveOptionalLookupTest(classInfo,
                methodInfo,
                analysisSummary,
                invalidSnippet,
                declaration);
        if (caseInsensitiveLookupSnippet != null) {
            return caseInsensitiveLookupSnippet;
        }
        GeneratedTestSnippet staticFactorySnippet = buildStaticFactoryAssertionTest(classInfo,
                methodInfo,
                analysisSummary,
                invalidSnippet,
                declaration);
        if (staticFactorySnippet != null) {
            return staticFactorySnippet;
        }
        GeneratedTestSnippet activeStateSnippet = buildBooleanStateTransitionTest(classInfo,
                methodInfo,
                analysisSummary,
                invalidSnippet,
                declaration);
        if (activeStateSnippet != null) {
            return activeStateSnippet;
        }
        GeneratedTestSnippet clockSnapshotSnippet = buildClockBackedSnapshotAccessorTest(classInfo,
                methodInfo,
                analysisSummary,
                invalidSnippet,
                declaration);
        if (clockSnapshotSnippet != null) {
            return clockSnapshotSnippet;
        }
        GeneratedTestSnippet instanceCountSnippet = buildInstanceMutableCollectionCountTest(classInfo,
                methodInfo,
                analysisSummary,
                invalidSnippet,
                declaration);
        if (instanceCountSnippet != null) {
            return instanceCountSnippet;
        }
        GeneratedTestSnippet delegationSnippet = buildDirectDelegationAssertionTest(classInfo,
                methodInfo,
                analysisSummary,
                invalidSnippet,
                declaration);
        if (delegationSnippet != null) {
            return delegationSnippet;
        }
        return buildBooleanBranchActivationTest(classInfo,
                methodInfo,
                analysisSummary,
                invalidSnippet,
                declaration);
    }

    public GeneratedTestSnippet build(TestClassInfo classInfo,
                                      TestMethodInfo methodInfo,
                                      Analyze.AnalysisSummary analysisSummary,
                                      GeneratedTestSnippet invalidSnippet) {
        if (classInfo == null || methodInfo == null || analysisSummary == null || invalidSnippet == null) {
            return null;
        }
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        if (targetContext == null) {
            return null;
        }
        String targetMethodName = resolveTargetMethodName(analysisSummary, methodInfo);
        if (targetMethodName.isBlank()) {
            return null;
        }
        GeneratedTestSnippet fastPathSnippet = buildFastPath(classInfo, methodInfo, analysisSummary, invalidSnippet);
        if (fastPathSnippet != null) {
            return fastPathSnippet;
        }
        Map<String, String> knownTypes = buildKnownTypeMap(classInfo, analysisSummary, targetContext);
        List<ParameterMetadata> methodParameters = extractMethodParameters(methodInfo);
        List<String> methodArguments = new ArrayList<>();
        for (ParameterMetadata parameter : methodParameters) {
            String expression = buildExpression(parameter.type(),
                    parameter.name(),
                    analysisSummary,
                    knownTypes,
                    0);
            if (expression == null || expression.isBlank()) {
                return null;
            }
            methodArguments.add(expression);
        }

        List<String> lines = new ArrayList<>();
        lines.add("@Test");
        lines.add("void " + resolveFallbackMethodName(invalidSnippet, methodInfo) + "() {");
        String invocationTarget = resolveInvocationTarget(targetContext, knownTypes);
        if (invocationTarget == null || invocationTarget.isBlank()) {
            return null;
        }
        if (targetContext.requiresInstance()) {
            TargetConstructionPlan constructionPlan = buildTargetConstructionPlan(analysisSummary, knownTypes);
            if (constructionPlan == null || constructionPlan.assignmentLine() == null || constructionPlan.assignmentLine().isBlank()) {
                return null;
            }
            constructionPlan.setupLines().forEach(line -> lines.add("    " + line));
            lines.add("    " + constructionPlan.assignmentLine());
        }
        lines.add("    " + invocationTarget + "." + targetMethodName + "(" + String.join(", ", methodArguments) + ");");
        lines.add("}");

        return new GeneratedTestSnippet(
                classInfo.getTestClassName(),
                resolveFallbackMethodName(invalidSnippet, methodInfo),
                String.join(System.lineSeparator(), lines),
                List.of("org.junit.jupiter.api.Test"),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
    }

    private GeneratedTestSnippet buildStaticMutableCollectionApiTest(TestClassInfo classInfo,
                                                                     TestMethodInfo methodInfo,
                                                                     Analyze.AnalysisSummary analysisSummary,
                                                                     GeneratedTestSnippet invalidSnippet,
                                                                     MethodDeclaration declaration) {
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        if (targetContext == null
                || !targetContext.isStatic()
                || !declaration.isStatic()) {
            return null;
        }
        StaticCollectionLifecycle lifecycle = resolveStaticCollectionLifecycle(targetContext.className(),
                analysisSummary.availableMethods(),
                classInfo,
                declaration);
        if (lifecycle == null || !isStaticCollectionLifecycleTarget(declaration, lifecycle)) {
            return null;
        }
        String targetType = simpleName(targetContext.className());
        String testMethodName = resolveFallbackMethodName(invalidSnippet, methodInfo);
        String currentMethod = declaration.getNameAsString();
        List<String> lines = new ArrayList<>();
        lines.add("@Test");
        lines.add("void " + testMethodName + "() {");
        lines.add("    " + targetType + "." + lifecycle.clearName() + "();");
        lines.add("    try {");
        if (currentMethod.equals(lifecycle.emitterName())) {
            lines.add("        " + renderStaticLifecycleEmitterInvocation(targetType, lifecycle) + ";");
            lines.add("        var result = " + targetType + "." + lifecycle.snapshotName() + "();");
            lines.add("        org.junit.jupiter.api.Assertions.assertEquals(1, result.size());");
            lifecycle.expectedElement().ifPresent(expected ->
                    lines.add("        org.junit.jupiter.api.Assertions.assertEquals(\"" + escapeJava(expected) + "\", result.get(0));"));
        } else if (currentMethod.equals(lifecycle.snapshotName())) {
            lines.add("        " + renderStaticLifecycleEmitterInvocation(targetType, lifecycle) + ";");
            lines.add("        var result = " + targetType + "." + lifecycle.snapshotName() + "();");
            lines.add("        org.junit.jupiter.api.Assertions.assertEquals(1, result.size());");
            lifecycle.expectedElement().ifPresent(expected ->
                    lines.add("        org.junit.jupiter.api.Assertions.assertEquals(\"" + escapeJava(expected) + "\", result.get(0));"));
            lines.add("        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, result::clear);");
        } else if (currentMethod.equals(lifecycle.clearName())) {
            lines.add("        " + renderStaticLifecycleEmitterInvocation(targetType, lifecycle) + ";");
            lines.add("        org.junit.jupiter.api.Assertions.assertFalse(" + targetType + "." + lifecycle.snapshotName() + "().isEmpty());");
            lines.add("        " + targetType + "." + lifecycle.clearName() + "();");
            lines.add("        org.junit.jupiter.api.Assertions.assertTrue(" + targetType + "." + lifecycle.snapshotName() + "().isEmpty());");
        } else {
            return null;
        }
        lines.add("    } finally {");
        lines.add("        " + targetType + "." + lifecycle.clearName() + "();");
        lines.add("    }");
        lines.add("}");
        return new GeneratedTestSnippet(
                classInfo.getTestClassName(),
                testMethodName,
                String.join(System.lineSeparator(), lines),
                List.of("org.junit.jupiter.api.Test"),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
    }

    private GeneratedTestSnippet buildCaseInsensitiveOptionalLookupTest(TestClassInfo classInfo,
                                                                        TestMethodInfo methodInfo,
                                                                        Analyze.AnalysisSummary analysisSummary,
                                                                        GeneratedTestSnippet invalidSnippet,
                                                                        MethodDeclaration declaration) {
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        if (targetContext == null
                || targetContext.isStatic()
                || declaration.getParameters().size() != 1
                || !declaration.getType().asString().startsWith("Optional")
                || !declaration.getBody().map(body -> body.toString().contains("equalsIgnoreCase")).orElse(false)
                || !hasMethod(targetContext.className(), analysisSummary.availableMethods(), "save", 1)
                || !hasUserStringConstructor(analysisSummary.availableConstructors())) {
            return null;
        }
        Map<String, String> knownTypes = buildKnownTypeMap(classInfo, analysisSummary, targetContext);
        String targetType = renderTypeReference(targetContext.className(), knownTypes);
        String userType = renderTypeReference("User", knownTypes);
        String targetMethodName = declaration.getNameAsString();
        String testMethodName = resolveFallbackMethodName(invalidSnippet, methodInfo);
        List<String> lines = new ArrayList<>();
        lines.add("@Test");
        lines.add("void " + testMethodName + "() {");
        lines.add("    " + targetType + " " + targetContext.instanceName() + " = new " + targetType + "();");
        lines.add("    " + userType + " savedUser = new " + userType + "(\"john.doe\", \"john@example.com\");");
        lines.add("    " + targetContext.instanceName() + ".save(savedUser);");
        lines.add("    var foundUser = " + targetContext.instanceName() + "." + targetMethodName + "(\"JOHN.DOE\");");
        lines.add("    assertThat(foundUser).isPresent();");
        lines.add("    assertThat(foundUser.get()).isSameAs(savedUser);");
        lines.add("    assertThat(" + targetContext.instanceName() + "." + targetMethodName + "(\"missing-user\")).isEmpty();");
        lines.add("}");
        return new GeneratedTestSnippet(
                classInfo.getTestClassName(),
                testMethodName,
                String.join(System.lineSeparator(), lines),
                List.of(
                        "org.junit.jupiter.api.Test",
                        "static org.assertj.core.api.Assertions.assertThat"),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
    }

    private GeneratedTestSnippet buildStaticFactoryAssertionTest(TestClassInfo classInfo,
                                                                 TestMethodInfo methodInfo,
                                                                 Analyze.AnalysisSummary analysisSummary,
                                                                 GeneratedTestSnippet invalidSnippet,
                                                                 MethodDeclaration declaration) {
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        if (targetContext == null
                || !targetContext.isStatic()
                || declaration.getParameters().isNonEmpty()
                || declaration.getType().isVoidType()
                || !returnsTargetType(classInfo, targetContext, declaration)
                || !bodyReturnsNewTargetInstance(declaration)) {
            return null;
        }
        Map<String, String> knownTypes = buildKnownTypeMap(classInfo, analysisSummary, targetContext);
        String targetType = renderTypeReference(targetContext.className(), knownTypes);
        String resultType = renderTypeReference(declaration.getType().asString(), knownTypes);
        List<String> lines = new ArrayList<>();
        lines.add("@Test");
        lines.add("void " + resolveFallbackMethodName(invalidSnippet, methodInfo) + "() {");
        lines.add("    " + resultType + " result = " + targetType + "." + declaration.getNameAsString() + "();");
        lines.add("    org.junit.jupiter.api.Assertions.assertNotNull(result);");
        lines.add("}");
        return new GeneratedTestSnippet(
                classInfo.getTestClassName(),
                resolveFallbackMethodName(invalidSnippet, methodInfo),
                String.join(System.lineSeparator(), lines),
                List.of("org.junit.jupiter.api.Test"),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
    }

    private GeneratedTestSnippet buildBooleanStateTransitionTest(TestClassInfo classInfo,
                                                                 TestMethodInfo methodInfo,
                                                                 Analyze.AnalysisSummary analysisSummary,
                                                                 GeneratedTestSnippet invalidSnippet,
                                                                 MethodDeclaration declaration) {
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        if (targetContext == null
                || targetContext.isStatic()
                || declaration.getParameters().isNonEmpty()
                || !declaration.getType().isVoidType()) {
            return null;
        }
        StateTransition transition = resolveBooleanStateTransition(targetContext.className(),
                declaration.getNameAsString(),
                analysisSummary.availableMethods());
        if (transition == null) {
            return null;
        }
        Map<String, String> knownTypes = buildKnownTypeMap(classInfo, analysisSummary, targetContext);
        TargetConstructionPlan constructionPlan = buildTargetConstructionPlan(analysisSummary, knownTypes);
        if (constructionPlan == null || constructionPlan.assignmentLine() == null || constructionPlan.assignmentLine().isBlank()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("@Test");
        lines.add("void " + resolveFallbackMethodName(invalidSnippet, methodInfo) + "() {");
        constructionPlan.setupLines().forEach(line -> lines.add("    " + line));
        lines.add("    " + constructionPlan.assignmentLine());
        lines.add("    " + targetContext.instanceName() + "." + transition.preconditionMutator() + "();");
        lines.add("    org.junit.jupiter.api.Assertions." + transition.preconditionAssertion()
                + "(" + targetContext.instanceName() + "." + transition.booleanGetter() + "());");
        lines.add("    " + targetContext.instanceName() + "." + declaration.getNameAsString() + "();");
        lines.add("    org.junit.jupiter.api.Assertions." + transition.finalAssertion()
                + "(" + targetContext.instanceName() + "." + transition.booleanGetter() + "());");
        lines.add("}");
        return new GeneratedTestSnippet(
                classInfo.getTestClassName(),
                resolveFallbackMethodName(invalidSnippet, methodInfo),
                String.join(System.lineSeparator(), lines),
                List.of("org.junit.jupiter.api.Test"),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
    }

    private GeneratedTestSnippet buildClockBackedSnapshotAccessorTest(TestClassInfo classInfo,
                                                                      TestMethodInfo methodInfo,
                                                                      Analyze.AnalysisSummary analysisSummary,
                                                                      GeneratedTestSnippet invalidSnippet,
                                                                      MethodDeclaration declaration) {
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        if (targetContext == null
                || targetContext.isStatic()
                || declaration.getParameters().isNonEmpty()
                || !returnsList(declaration)
                || !bodyReturnsUnmodifiableCollection(declaration)
                || !hasClockConstructor(targetContext.className(), analysisSummary.availableConstructors())
                || !hasMethod(targetContext.className(), analysisSummary.availableMethods(), "recordEvent", 1)) {
            return null;
        }
        Map<String, String> knownTypes = buildKnownTypeMap(classInfo, analysisSummary, targetContext);
        String targetType = renderTypeReference(targetContext.className(), knownTypes);
        String fixedInstant = "2023-09-14T12:00:00Z";
        String elementType = extractFirstGenericType(declaration.getType().asString());
        boolean hasMessageTimestampAccessors = hasMethod(elementType, analysisSummary.availableMethods(), "message", 0)
                && hasMethod(elementType, analysisSummary.availableMethods(), "timestamp", 0);
        List<String> lines = new ArrayList<>();
        lines.add("@Test");
        lines.add("void " + resolveFallbackMethodName(invalidSnippet, methodInfo) + "() {");
        lines.add("    java.time.Clock fixedClock = java.time.Clock.fixed(java.time.Instant.parse(\""
                + fixedInstant
                + "\"), java.time.ZoneOffset.UTC);");
        lines.add("    " + targetType + " " + targetContext.instanceName() + " = new " + targetType + "(fixedClock);");
        lines.add("    " + targetContext.instanceName() + ".recordEvent(\"coverage-event\");");
        lines.add("    var result = " + targetContext.instanceName() + "." + declaration.getNameAsString() + "();");
        lines.add("    org.junit.jupiter.api.Assertions.assertEquals(1, result.size());");
        if (hasMessageTimestampAccessors) {
            lines.add("    org.junit.jupiter.api.Assertions.assertEquals(\"coverage-event\", result.get(0).message());");
            lines.add("    org.junit.jupiter.api.Assertions.assertEquals(java.time.Instant.parse(\""
                    + fixedInstant
                    + "\"), result.get(0).timestamp());");
        }
        lines.add("    org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, result::clear);");
        lines.add("}");
        return new GeneratedTestSnippet(
                classInfo.getTestClassName(),
                resolveFallbackMethodName(invalidSnippet, methodInfo),
                String.join(System.lineSeparator(), lines),
                List.of("org.junit.jupiter.api.Test"),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
    }

    private GeneratedTestSnippet buildInstanceMutableCollectionCountTest(TestClassInfo classInfo,
                                                                         TestMethodInfo methodInfo,
                                                                         Analyze.AnalysisSummary analysisSummary,
                                                                         GeneratedTestSnippet invalidSnippet,
                                                                         MethodDeclaration declaration) {
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        if (targetContext == null
                || targetContext.isStatic()
                || declaration.getParameters().isNonEmpty()
                || !returnsInteger(declaration)
                || !bodyReturnsCollectionSize(declaration)) {
            return null;
        }
        InstanceCollectionLifecycle lifecycle = resolveInstanceCollectionLifecycle(targetContext.className(),
                analysisSummary.availableMethods(),
                classInfo,
                declaration);
        if (lifecycle == null || !declaration.getNameAsString().equals(lifecycle.countName())) {
            return null;
        }
        Map<String, String> knownTypes = buildKnownTypeMap(classInfo, analysisSummary, targetContext);
        String targetType = renderTypeReference(targetContext.className(), knownTypes);
        String construction = buildStableNoArgOrClockConstruction(targetType,
                targetContext.className(),
                analysisSummary.availableConstructors());
        if (construction == null || construction.isBlank()) {
            return null;
        }
        String targetName = targetContext.instanceName();
        List<String> lines = new ArrayList<>();
        lines.add("@Test");
        lines.add("void " + resolveFallbackMethodName(invalidSnippet, methodInfo) + "() {");
        lines.add("    " + targetType + " " + targetName + " = " + construction + ";");
        lines.add("    org.junit.jupiter.api.Assertions.assertEquals(0, " + targetName + "." + lifecycle.countName() + "());");
        lines.add("    " + renderInstanceLifecycleEmitterInvocation(targetName, lifecycle) + ";");
        lines.add("    org.junit.jupiter.api.Assertions.assertEquals(1, " + targetName + "." + lifecycle.countName() + "());");
        if (lifecycle.clearName().isPresent()) {
            lines.add("    " + targetName + "." + lifecycle.clearName().get() + "();");
            lines.add("    org.junit.jupiter.api.Assertions.assertEquals(0, " + targetName + "." + lifecycle.countName() + "());");
        }
        lines.add("}");
        return new GeneratedTestSnippet(
                classInfo.getTestClassName(),
                resolveFallbackMethodName(invalidSnippet, methodInfo),
                String.join(System.lineSeparator(), lines),
                List.of("org.junit.jupiter.api.Test"),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
    }

    private GeneratedTestSnippet buildDirectDelegationAssertionTest(TestClassInfo classInfo,
                                                                    TestMethodInfo methodInfo,
                                                                    Analyze.AnalysisSummary analysisSummary,
                                                                    GeneratedTestSnippet invalidSnippet,
                                                                    MethodDeclaration declaration) {
        if (declaration.getParameters().size() > 0 || declaration.getBody().isEmpty() || declaration.getType().isVoidType()) {
            return null;
        }
        List<Statement> statements = declaration.getBody().get().getStatements();
        if (statements.size() != 1 || !statements.get(0).isReturnStmt()) {
            return null;
        }
        ReturnStmt returnStmt = statements.get(0).asReturnStmt();
        if (returnStmt.getExpression().isEmpty() || !returnStmt.getExpression().get().isMethodCallExpr()) {
            return null;
        }
        MethodCallExpr delegatedCall = returnStmt.getExpression().get().asMethodCallExpr();
        if (delegatedCall.getScope().isEmpty() || !delegatedCall.getScope().get().isNameExpr()) {
            return null;
        }
        String delegatedTarget = delegatedCall.getScope().get().asNameExpr().getNameAsString();
        Map<String, String> knownTypes = buildKnownTypeMap(classInfo, analysisSummary, analysisSummary.testTargetContext());
        TargetConstructionPlan constructionPlan = buildTargetConstructionPlan(analysisSummary, knownTypes);
        if (constructionPlan == null
                || !constructionPlan.variableTypes().containsKey(delegatedTarget)
                || !shouldMock(delegatedTarget, constructionPlan.variableTypes().get(delegatedTarget), analysisSummary.mockPlan())) {
            return null;
        }
        String expectedExpression = buildExpectedValueExpression(declaration.getType().asString(), analysisSummary, knownTypes);
        if (expectedExpression == null || expectedExpression.isBlank()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("@Test");
        lines.add("void " + resolveFallbackMethodName(invalidSnippet, methodInfo) + "() {");
        constructionPlan.setupLines().forEach(line -> lines.add("    " + line));
        lines.add("    " + constructionPlan.assignmentLine());
        lines.add("    var expected = " + expectedExpression + ";");
        lines.add("    org.mockito.Mockito.when("
                + delegatedTarget
                + "."
                + delegatedCall.getNameAsString()
                + "("
                + renderInvocationArguments(delegatedCall)
                + ")).thenReturn(expected);");
        lines.add("    " + renderAssertionForReturn(declaration.getType().asString(),
                analysisSummary.testTargetContext().instanceName()
                        + "."
                        + declaration.getNameAsString()
                        + "()"));
        lines.add("}");
        return new GeneratedTestSnippet(
                classInfo.getTestClassName(),
                resolveFallbackMethodName(invalidSnippet, methodInfo),
                String.join(System.lineSeparator(), lines),
                List.of("org.junit.jupiter.api.Test"),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
    }

    private GeneratedTestSnippet buildBooleanBranchActivationTest(TestClassInfo classInfo,
                                                                 TestMethodInfo methodInfo,
                                                                 Analyze.AnalysisSummary analysisSummary,
                                                                 GeneratedTestSnippet invalidSnippet,
                                                                 MethodDeclaration declaration) {
        if (!declaration.getType().isVoidType() || declaration.getParameters().size() > 0 || declaration.getBody().isEmpty()) {
            return null;
        }
        List<Statement> statements = declaration.getBody().get().getStatements();
        if (statements.size() < 2 || !statements.get(0).isExpressionStmt() || !statements.get(1).isIfStmt()) {
            return null;
        }
        Expression expression = statements.get(0).asExpressionStmt().getExpression();
        if (!expression.isVariableDeclarationExpr()) {
            return null;
        }
        VariableDeclarationExpr variableDeclarationExpr = expression.asVariableDeclarationExpr();
        if (variableDeclarationExpr.getVariables().size() != 1) {
            return null;
        }
        VariableDeclarator variableDeclarator = variableDeclarationExpr.getVariable(0);
        if (variableDeclarator.getInitializer().isEmpty() || !variableDeclarator.getInitializer().get().isObjectCreationExpr()) {
            return null;
        }
        ObjectCreationExpr objectCreationExpr = variableDeclarator.getInitializer().get().asObjectCreationExpr();
        IfStmt ifStmt = statements.get(1).asIfStmt();
        if (!ifStmt.getCondition().isMethodCallExpr()) {
            return null;
        }
        MethodCallExpr conditionCall = ifStmt.getCondition().asMethodCallExpr();
        if (conditionCall.getScope().isEmpty() || !conditionCall.getScope().get().isNameExpr()) {
            return null;
        }
        String localObjectName = conditionCall.getScope().get().asNameExpr().getNameAsString();
        if (!localObjectName.equals(variableDeclarator.getNameAsString())) {
            return null;
        }
        Statement thenStatement = unwrapSingleStatement(ifStmt.getThenStmt());
        if (thenStatement == null || !thenStatement.isExpressionStmt()) {
            return null;
        }
        Expression thenExpression = thenStatement.asExpressionStmt().getExpression();
        if (!thenExpression.isMethodCallExpr()) {
            return null;
        }
        MethodCallExpr thenCall = thenExpression.asMethodCallExpr();
        if (thenCall.getScope().isEmpty()
                || !thenCall.getScope().get().isNameExpr()
                || !localObjectName.equals(thenCall.getScope().get().asNameExpr().getNameAsString())) {
            return null;
        }
        Map<String, String> knownTypes = buildKnownTypeMap(classInfo, analysisSummary, analysisSummary.testTargetContext());
        TargetConstructionPlan constructionPlan = buildTargetConstructionPlan(analysisSummary, knownTypes);
        if (constructionPlan == null) {
            return null;
        }
        String stubLine = buildBooleanGuardStub(objectCreationExpr, analysisSummary, constructionPlan);
        if (stubLine == null || stubLine.isBlank()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("@Test");
        lines.add("void " + resolveFallbackMethodName(invalidSnippet, methodInfo) + "() {");
        constructionPlan.setupLines().forEach(line -> lines.add("    " + line));
        lines.add("    " + constructionPlan.assignmentLine());
        lines.add("    " + stubLine);
        lines.add("    " + analysisSummary.testTargetContext().instanceName() + "." + declaration.getNameAsString() + "();");
        lines.add("}");
        return new GeneratedTestSnippet(
                classInfo.getTestClassName(),
                resolveFallbackMethodName(invalidSnippet, methodInfo),
                String.join(System.lineSeparator(), lines),
                List.of("org.junit.jupiter.api.Test"),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
    }

    private StaticCollectionLifecycle resolveStaticCollectionLifecycle(String targetType,
                                                                       Map<String, List<String>> availableMethods,
                                                                       TestClassInfo classInfo,
                                                                       MethodDeclaration currentDeclaration) {
        if (targetType == null || targetType.isBlank()) {
            return null;
        }
        List<MethodDeclaration> methods = new ArrayList<>();
        methods.add(currentDeclaration);
        classInfoMethodDeclarations(classInfo).stream()
                .filter(method -> !sameMethodShape(method, currentDeclaration))
                .forEach(methods::add);
        availableMethodDeclarations(targetType, availableMethods).stream()
                .filter(method -> !sameMethodShape(method, currentDeclaration))
                .forEach(methods::add);
        MethodDeclaration clearMethod = methods.stream()
                .filter(this::isStaticLifecycleClearMethod)
                .findFirst()
                .orElse(null);
        MethodDeclaration snapshotMethod = methods.stream()
                .filter(this::isStaticLifecycleSnapshotMethod)
                .findFirst()
                .orElse(null);
        MethodDeclaration emitterMethod = methods.stream()
                .filter(this::isStaticLifecycleEmitterMethod)
                .findFirst()
                .orElse(null);
        if (clearMethod == null || snapshotMethod == null || emitterMethod == null) {
            return null;
        }
        Optional<String> expectedElement = resolveStaticLifecycleExpectedElement(emitterMethod);
        return new StaticCollectionLifecycle(clearMethod.getNameAsString(),
                snapshotMethod.getNameAsString(),
                emitterMethod.getNameAsString(),
                buildStaticLifecycleEmitterArguments(emitterMethod),
                expectedElement);
    }

    private InstanceCollectionLifecycle resolveInstanceCollectionLifecycle(String targetType,
                                                                          Map<String, List<String>> availableMethods,
                                                                          TestClassInfo classInfo,
                                                                          MethodDeclaration currentDeclaration) {
        if (targetType == null || targetType.isBlank()) {
            return null;
        }
        List<MethodDeclaration> methods = new ArrayList<>();
        methods.add(currentDeclaration);
        classInfoMethodDeclarations(classInfo).stream()
                .filter(method -> !sameMethodShape(method, currentDeclaration))
                .forEach(methods::add);
        availableMethodDeclarations(targetType, availableMethods).stream()
                .filter(method -> !sameMethodShape(method, currentDeclaration))
                .forEach(methods::add);
        MethodDeclaration countMethod = methods.stream()
                .filter(method -> returnsInteger(method) && bodyReturnsCollectionSize(method))
                .findFirst()
                .orElse(null);
        MethodDeclaration emitterMethod = methods.stream()
                .filter(this::isInstanceLifecycleEmitterMethod)
                .findFirst()
                .orElse(null);
        Optional<MethodDeclaration> clearMethod = methods.stream()
                .filter(this::isInstanceLifecycleClearMethod)
                .findFirst();
        if (countMethod == null || emitterMethod == null) {
            return null;
        }
        return new InstanceCollectionLifecycle(countMethod.getNameAsString(),
                emitterMethod.getNameAsString(),
                buildInstanceLifecycleEmitterArguments(emitterMethod),
                clearMethod.map(MethodDeclaration::getNameAsString));
    }

    private List<MethodDeclaration> availableMethodDeclarations(String targetType,
                                                                Map<String, List<String>> availableMethods) {
        if (targetType == null || targetType.isBlank()
                || availableMethods == null || availableMethods.isEmpty()) {
            return List.of();
        }
        String targetSimpleName = simpleName(targetType);
        return availableMethods.entrySet().stream()
                .filter(entry -> targetSimpleName.equals(simpleName(entry.getKey())))
                .flatMap(entry -> entry.getValue().stream())
                .map(this::parseMethodSignature)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<MethodDeclaration> classInfoMethodDeclarations(TestClassInfo classInfo) {
        if (classInfo == null || classInfo.getMethods() == null || classInfo.getMethods().isEmpty()) {
            return List.of();
        }
        return classInfo.getMethods().stream()
                .map(TestMethodInfo::getDeclaration)
                .filter(Objects::nonNull)
                .toList();
    }

    private boolean sameMethodShape(MethodDeclaration left, MethodDeclaration right) {
        if (left == null || right == null) {
            return false;
        }
        return left.getNameAsString().equals(right.getNameAsString())
                && left.getParameters().size() == right.getParameters().size();
    }

    private boolean isStaticCollectionLifecycleTarget(MethodDeclaration declaration,
                                                      StaticCollectionLifecycle lifecycle) {
        if (declaration == null || lifecycle == null) {
            return false;
        }
        String methodName = declaration.getNameAsString();
        if (methodName.equals(lifecycle.clearName())) {
            return isStaticLifecycleClearMethod(declaration)
                    || (declaration.getParameters().isEmpty()
                    && declaration.getType().isVoidType()
                    && bodyCallsMethod(declaration, "clear"));
        }
        if (methodName.equals(lifecycle.snapshotName())) {
            return isStaticLifecycleSnapshotMethod(declaration)
                    || (declaration.getParameters().isEmpty()
                    && returnsList(declaration)
                    && bodyReturnsUnmodifiableCollection(declaration));
        }
        return methodName.equals(lifecycle.emitterName())
                && isStringParameterVoidMethod(declaration)
                && bodyCallsMethod(declaration, "add");
    }

    private boolean isStaticLifecycleClearMethod(MethodDeclaration method) {
        if (method == null || !method.getParameters().isEmpty() || !method.getType().isVoidType()) {
            return false;
        }
        String name = method.getNameAsString().toLowerCase(Locale.ROOT);
        return "clear".equals(name) || "reset".equals(name);
    }

    private boolean isStaticLifecycleSnapshotMethod(MethodDeclaration method) {
        if (method == null || !method.getParameters().isEmpty() || !returnsList(method)) {
            return false;
        }
        String name = method.getNameAsString().toLowerCase(Locale.ROOT);
        return name.contains("snapshot") || bodyReturnsUnmodifiableCollection(method);
    }

    private boolean isStaticLifecycleEmitterMethod(MethodDeclaration method) {
        if (method == null || !isStringParameterVoidMethod(method)) {
            return false;
        }
        String name = method.getNameAsString().toLowerCase(Locale.ROOT);
        return name.contains("emit")
                || name.contains("record")
                || name.contains("append")
                || name.startsWith("add")
                || bodyCallsMethod(method, "add");
    }

    private boolean isStringParameterVoidMethod(MethodDeclaration method) {
        if (method == null
                || !method.getType().isVoidType()
                || method.getParameters().isEmpty()
                || method.getParameters().size() > 2) {
            return false;
        }
        return method.getParameters().stream()
                .allMatch(parameter -> {
                    String type = stripDecorations(parameter.getType().asString());
                    return "String".equals(type) || "java.lang.String".equals(type);
                });
    }

    private boolean isInstanceLifecycleEmitterMethod(MethodDeclaration method) {
        if (method == null || method.isStatic()) {
            return false;
        }
        return isStaticLifecycleEmitterMethod(method);
    }

    private boolean isInstanceLifecycleClearMethod(MethodDeclaration method) {
        if (method == null || method.isStatic()) {
            return false;
        }
        return isStaticLifecycleClearMethod(method);
    }

    private boolean bodyCallsMethod(MethodDeclaration declaration, String methodName) {
        if (declaration == null || declaration.getBody().isEmpty() || methodName == null || methodName.isBlank()) {
            return false;
        }
        return declaration.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> methodName.equals(call.getNameAsString()));
    }

    private List<String> buildStaticLifecycleEmitterArguments(MethodDeclaration emitterMethod) {
        List<String> arguments = new ArrayList<>();
        for (int i = 0; i < emitterMethod.getParameters().size(); i++) {
            String parameterName = emitterMethod.getParameter(i).getNameAsString().toLowerCase(Locale.ROOT);
            if (parameterName.contains("stream") || parameterName.contains("topic") || parameterName.contains("channel")) {
                arguments.add("\"coverage-stream\"");
            } else if (parameterName.contains("event") || parameterName.contains("message") || parameterName.contains("payload")) {
                arguments.add("\"coverage-message\"");
            } else {
                arguments.add(i == 0 ? "\"coverage-stream\"" : "\"coverage-message\"");
            }
        }
        return arguments;
    }

    private List<String> buildInstanceLifecycleEmitterArguments(MethodDeclaration emitterMethod) {
        return buildStaticLifecycleEmitterArguments(emitterMethod);
    }

    private String renderStaticLifecycleEmitterInvocation(String targetType,
                                                          StaticCollectionLifecycle lifecycle) {
        return targetType
                + "."
                + lifecycle.emitterName()
                + "("
                + String.join(", ", lifecycle.emitterArguments())
                + ")";
    }

    private String renderInstanceLifecycleEmitterInvocation(String targetName,
                                                            InstanceCollectionLifecycle lifecycle) {
        return targetName
                + "."
                + lifecycle.emitterName()
                + "("
                + String.join(", ", lifecycle.emitterArguments())
                + ")";
    }

    private String buildStableNoArgOrClockConstruction(String targetType,
                                                       String targetClass,
                                                       Map<String, List<ConstructorMetadata>> constructorsByType) {
        List<ConstructorMetadata> constructors = constructorsByType == null
                ? List.of()
                : constructorsByType.entrySet().stream()
                .filter(entry -> simpleName(targetClass).equals(simpleName(entry.getKey())))
                .flatMap(entry -> entry.getValue().stream())
                .filter(Objects::nonNull)
                .toList();
        if (constructors.stream().anyMatch(constructor -> constructor.parameters().isEmpty())) {
            return "new " + targetType + "()";
        }
        for (ConstructorMetadata constructor : constructors) {
            if (constructor.parameters().size() == 1
                    && "Clock".equals(simpleName(constructor.parameters().get(0).type()))) {
                return "new " + targetType
                        + "(java.time.Clock.fixed(java.time.Instant.parse(\"2023-09-14T12:00:00Z\"), java.time.ZoneOffset.UTC))";
            }
        }
        return null;
    }

    private Optional<String> resolveStaticLifecycleExpectedElement(MethodDeclaration emitterMethod) {
        if (emitterMethod == null || emitterMethod.getBody().isEmpty()) {
            return Optional.empty();
        }
        List<String> arguments = buildStaticLifecycleEmitterArguments(emitterMethod);
        Map<String, String> parameterValues = new LinkedHashMap<>();
        for (int i = 0; i < emitterMethod.getParameters().size() && i < arguments.size(); i++) {
            parameterValues.put(emitterMethod.getParameter(i).getNameAsString(), unquoteJava(arguments.get(i)));
        }
        return emitterMethod.findAll(MethodCallExpr.class).stream()
                .filter(call -> "add".equals(call.getNameAsString()) && !call.getArguments().isEmpty())
                .findFirst()
                .flatMap(call -> evaluateStringConcatenation(call.getArgument(0), parameterValues));
    }

    private Optional<String> evaluateStringConcatenation(Expression expression,
                                                        Map<String, String> parameterValues) {
        if (expression == null) {
            return Optional.empty();
        }
        if (expression.isEnclosedExpr()) {
            return evaluateStringConcatenation(expression.asEnclosedExpr().getInner(), parameterValues);
        }
        if (expression.isStringLiteralExpr()) {
            return Optional.of(expression.asStringLiteralExpr().asString());
        }
        if (expression.isNameExpr()) {
            return Optional.ofNullable(parameterValues.get(expression.asNameExpr().getNameAsString()));
        }
        if (!expression.isBinaryExpr()) {
            return Optional.empty();
        }
        BinaryExpr binaryExpr = expression.asBinaryExpr();
        if (binaryExpr.getOperator() != BinaryExpr.Operator.PLUS) {
            return Optional.empty();
        }
        Optional<String> left = evaluateStringConcatenation(binaryExpr.getLeft(), parameterValues);
        Optional<String> right = evaluateStringConcatenation(binaryExpr.getRight(), parameterValues);
        if (left.isEmpty() || right.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(left.get() + right.get());
    }

    private String escapeJava(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String unquoteJava(String value) {
        if (value == null || value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) {
            return value == null ? "" : value;
        }
        return value.substring(1, value.length() - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private String resolveTargetMethodName(Analyze.AnalysisSummary analysisSummary, TestMethodInfo methodInfo) {
        if (analysisSummary.methodAnalysis() != null && analysisSummary.methodAnalysis().method() != null) {
            String name = analysisSummary.methodAnalysis().method().name();
            if (name != null && !name.isBlank()) {
                return name.trim();
            }
        }
        MethodDeclaration declaration = methodInfo.getDeclaration();
        if (declaration != null) {
            return declaration.getNameAsString();
        }
        String signature = methodInfo.getSignature();
        if (signature == null || signature.isBlank()) {
            return "";
        }
        int openParen = signature.indexOf('(');
        if (openParen < 0) {
            return "";
        }
        String beforeArgs = signature.substring(0, openParen).trim();
        int lastSpace = beforeArgs.lastIndexOf(' ');
        return lastSpace >= 0 ? beforeArgs.substring(lastSpace + 1).trim() : beforeArgs;
    }

    private List<ParameterMetadata> extractMethodParameters(TestMethodInfo methodInfo) {
        MethodDeclaration declaration = methodInfo.getDeclaration();
        if (declaration != null) {
            return declaration.getParameters().stream()
                    .map(parameter -> new ParameterMetadata(parameter.getNameAsString(),
                            parameter.getType().asString(),
                            List.of()))
                    .toList();
        }
        try {
            MethodDeclaration parsed = StaticJavaParser.parseMethodDeclaration(methodInfo.getSignature() + " {}");
            return parsed.getParameters().stream()
                    .map(parameter -> new ParameterMetadata(parameter.getNameAsString(),
                            parameter.getType().asString(),
                            List.of()))
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String resolveFallbackMethodName(GeneratedTestSnippet invalidSnippet, TestMethodInfo methodInfo) {
        if (invalidSnippet.methodName() != null && !invalidSnippet.methodName().isBlank()) {
            return invalidSnippet.methodName().trim();
        }
        String targetName = resolveTargetMethodName(new Analyze.AnalysisSummary(
                new MockPlan(List.of(), null, List.of(), List.of()),
                new com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult(
                        new MethodMetadata("", "", ""),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                "",
                Map.of(),
                new Analyze.TestTargetContext("", "", false, true),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Set.of()),
                methodInfo);
        if (targetName.isBlank()) {
            return "shouldInvokeTargetMethod";
        }
        return "shouldInvoke" + capitalise(targetName);
    }

    private MethodDeclaration resolveDeclaration(TestMethodInfo methodInfo) {
        if (methodInfo == null) {
            return null;
        }
        if (methodInfo.getDeclaration() != null) {
            return methodInfo.getDeclaration();
        }
        try {
            String body = methodInfo.getBody() == null ? "{}" : methodInfo.getBody().trim();
            String source = body.startsWith("{")
                    ? methodInfo.getSignature() + " " + body
                    : methodInfo.getSignature() + " { " + body + " }";
            return StaticJavaParser.parseMethodDeclaration(source);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, String> buildKnownTypeMap(TestClassInfo classInfo,
                                                  Analyze.AnalysisSummary analysisSummary,
                                                  Analyze.TestTargetContext targetContext) {
        LinkedHashMap<String, String> knownTypes = new LinkedHashMap<>();
        if (classInfo != null) {
            for (String importLine : classInfo.getImports()) {
                String fqcn = normaliseImport(importLine);
                if (!fqcn.isBlank()) {
                    knownTypes.putIfAbsent(simpleName(fqcn), fqcn);
                }
            }
            String targetPackage = resolvePackageName(classInfo.getTargetPath());
            if (!targetPackage.isBlank() && targetContext != null && targetContext.className() != null && !targetContext.className().isBlank()) {
                knownTypes.putIfAbsent(simpleName(targetContext.className()),
                        targetPackage + "." + simpleName(targetContext.className()));
            }
        }
        if (analysisSummary.availableConstructors() != null) {
            analysisSummary.availableConstructors().keySet().forEach(type ->
                    knownTypes.putIfAbsent(simpleName(type), type));
        }
        return knownTypes;
    }

    private String normaliseImport(String importLine) {
        if (importLine == null) {
            return "";
        }
        String trimmed = importLine.trim();
        if (trimmed.startsWith("import ")) {
            trimmed = trimmed.substring("import ".length());
        }
        if (trimmed.startsWith("static ")) {
            trimmed = trimmed.substring("static ".length());
        }
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.trim();
    }

    private TargetConstructionPlan buildTargetConstructionPlan(Analyze.AnalysisSummary analysisSummary,
                                                               Map<String, String> knownTypes) {
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        if (targetContext == null) {
            return null;
        }
        String targetSimpleName = simpleName(targetContext.className());
        List<Map.Entry<String, List<ConstructorMetadata>>> candidates = analysisSummary.availableConstructors().entrySet().stream()
                .filter(entry -> targetSimpleName.equals(simpleName(entry.getKey())))
                .toList();
        ConstructorMetadata constructor = candidates.stream()
                .flatMap(entry -> entry.getValue().stream())
                .filter(Objects::nonNull)
                .max(Comparator.comparingInt(candidate -> constructorScore(candidate, analysisSummary)))
                .orElse(null);
        if (constructor == null) {
            return null;
        }
        String typeReference = renderTypeReference(targetContext.className(), knownTypes);
        if (constructor.parameters().isEmpty()) {
            return new TargetConstructionPlan(List.of(),
                    typeReference + " " + targetContext.instanceName() + " = new " + typeReference + "();",
                    Map.of());
        }
        List<String> setupLines = new ArrayList<>();
        List<String> args = new ArrayList<>();
        LinkedHashMap<String, String> variableTypes = new LinkedHashMap<>();
        int index = 0;
        for (ParameterMetadata parameter : constructor.parameters()) {
            String expression = buildExpression(parameter.type(),
                    parameter.name(),
                    analysisSummary,
                    knownTypes,
                    0);
            if (expression == null || expression.isBlank()) {
                return null;
            }
            String variableName = parameterVariableName(parameter, index++);
            setupLines.add(renderTypeReference(parameter.type(), knownTypes) + " " + variableName + " = " + expression + ";");
            args.add(variableName);
            variableTypes.put(variableName, parameter.type());
        }
        return new TargetConstructionPlan(setupLines,
                typeReference + " " + targetContext.instanceName() + " = new " + typeReference + "(" + String.join(", ", args) + ");",
                Map.copyOf(variableTypes));
    }

    private int constructorScore(ConstructorMetadata constructor, Analyze.AnalysisSummary analysisSummary) {
        if (constructor == null) {
            return Integer.MIN_VALUE;
        }
        int score = 0;
        for (ParameterMetadata parameter : constructor.parameters()) {
            if (shouldMock(parameter.name(), parameter.type(), analysisSummary.mockPlan())) {
                score += 10;
            } else {
                score += 1;
            }
        }
        return score;
    }

    private String buildExpression(String type,
                                   String parameterName,
                                   Analyze.AnalysisSummary analysisSummary,
                                   Map<String, String> knownTypes,
                                   int depth) {
        if (depth > 2) {
            return null;
        }
        String normalizedType = stripDecorations(type);
        if (normalizedType.isBlank()) {
            return null;
        }
        if (shouldMock(parameterName, normalizedType, analysisSummary.mockPlan())) {
            return "org.mockito.Mockito.mock(" + renderTypeReference(normalizedType, knownTypes) + ".class)";
        }
        String literal = buildLiteral(normalizedType, knownTypes);
        if (literal != null) {
            return literal;
        }
        Optional<String> constructed = buildConstructedValue(normalizedType, analysisSummary, knownTypes, depth);
        if (constructed.isPresent()) {
            return constructed.get();
        }
        return "org.mockito.Mockito.mock(" + renderTypeReference(normalizedType, knownTypes) + ".class)";
    }

    private Optional<String> buildConstructedValue(String type,
                                                   Analyze.AnalysisSummary analysisSummary,
                                                   Map<String, String> knownTypes,
                                                   int depth) {
        String simpleType = simpleName(type);
        List<ConstructorMetadata> constructors = analysisSummary.availableConstructors().entrySet().stream()
                .filter(entry -> simpleType.equals(simpleName(entry.getKey())))
                .flatMap(entry -> entry.getValue().stream())
                .filter(Objects::nonNull)
                .toList();
        if (constructors.isEmpty()) {
            return Optional.empty();
        }
        ConstructorMetadata zeroArg = constructors.stream()
                .filter(constructor -> constructor.parameters().isEmpty())
                .findFirst()
                .orElse(null);
        String typeReference = renderTypeReference(type, knownTypes);
        if (zeroArg != null) {
            return Optional.of("new " + typeReference + "()");
        }
        for (ConstructorMetadata constructor : constructors) {
            List<String> args = new ArrayList<>();
            boolean resolvable = true;
            for (ParameterMetadata parameter : constructor.parameters()) {
                String expression = buildExpression(parameter.type(),
                        parameter.name(),
                        analysisSummary,
                        knownTypes,
                        depth + 1);
                if (expression == null || expression.isBlank()) {
                    resolvable = false;
                    break;
                }
                args.add(expression);
            }
            if (resolvable) {
                return Optional.of("new " + typeReference + "(" + String.join(", ", args) + ")");
            }
        }
        return Optional.empty();
    }

    private String buildLiteral(String type, Map<String, String> knownTypes) {
        String normalizedType = stripDecorations(type);
        return switch (normalizedType) {
            case "boolean", "Boolean" -> "true";
            case "byte", "Byte" -> "(byte) 1";
            case "short", "Short" -> "(short) 1";
            case "int", "Integer" -> "10";
            case "long", "Long" -> "10L";
            case "float", "Float" -> "1.0f";
            case "double", "Double" -> "1.0d";
            case "char", "Character" -> "'a'";
            case "String", "java.lang.String" -> "\"test\"";
            case "List", "java.util.List" -> "java.util.List.of()";
            case "Set", "java.util.Set" -> "java.util.Set.of()";
            case "Map", "java.util.Map" -> "java.util.Map.of()";
            case "Optional", "java.util.Optional" -> "java.util.Optional.empty()";
            case "Instant", "java.time.Instant" -> "java.time.Instant.now()";
            default -> normalizedType.endsWith("[]")
                    ? "new " + renderTypeReference(normalizedType.substring(0, normalizedType.length() - 2), knownTypes) + "[0]"
                    : null;
        };
    }

    private String buildExpectedValueExpression(String type,
                                                Analyze.AnalysisSummary analysisSummary,
                                                Map<String, String> knownTypes) {
        String normalizedType = stripDecorations(type);
        return switch (normalizedType) {
            case "boolean", "Boolean" -> "true";
            case "byte", "Byte" -> "(byte) 7";
            case "short", "Short" -> "(short) 7";
            case "int", "Integer" -> "42";
            case "long", "Long" -> "42L";
            case "float", "Float" -> "42.0f";
            case "double", "Double" -> "42.0d";
            case "char", "Character" -> "'z'";
            case "String", "java.lang.String" -> "\"expected\"";
            case "List", "java.util.List" -> buildCollectionExpectation("java.util.List.of",
                    extractFirstGenericType(type),
                    analysisSummary,
                    knownTypes,
                    "\"alpha\", \"beta\"");
            case "Set", "java.util.Set" -> buildCollectionExpectation("java.util.Set.of",
                    extractFirstGenericType(type),
                    analysisSummary,
                    knownTypes,
                    "\"alpha\"");
            case "Map", "java.util.Map" -> "java.util.Map.of(\"key\", \"value\")";
            case "Optional", "java.util.Optional" -> buildOptionalExpectation(type, analysisSummary, knownTypes);
            default -> buildExpression(type, "expectedResult", analysisSummary, knownTypes, 0);
        };
    }

    private String buildCollectionExpectation(String factory,
                                              String elementType,
                                              Analyze.AnalysisSummary analysisSummary,
                                              Map<String, String> knownTypes,
                                              String defaultElements) {
        String normalizedElementType = stripDecorations(elementType);
        if (elementType == null || elementType.isBlank()
                || "String".equals(normalizedElementType)
                || "java.lang.String".equals(normalizedElementType)) {
            return factory + "(" + defaultElements + ")";
        }
        String elementExpression = buildExpression(elementType, "expectedElement", analysisSummary, knownTypes, 0);
        return elementExpression == null || elementExpression.isBlank()
                ? factory + "(" + defaultElements + ")"
                : factory + "(" + elementExpression + ")";
    }

    private String buildOptionalExpectation(String type,
                                            Analyze.AnalysisSummary analysisSummary,
                                            Map<String, String> knownTypes) {
        String elementType = extractFirstGenericType(type);
        String normalizedElementType = stripDecorations(elementType);
        if (elementType == null || elementType.isBlank()
                || "String".equals(normalizedElementType)
                || "java.lang.String".equals(normalizedElementType)) {
            return "java.util.Optional.of(\"expected\")";
        }
        String elementExpression = buildExpression(elementType, "expectedElement", analysisSummary, knownTypes, 0);
        return elementExpression == null || elementExpression.isBlank()
                ? "java.util.Optional.of(\"expected\")"
                : "java.util.Optional.of(" + elementExpression + ")";
    }

    private String renderAssertionForReturn(String type, String actualExpression) {
        String normalizedType = stripDecorations(type);
        return switch (normalizedType) {
            case "boolean", "Boolean" -> "org.junit.jupiter.api.Assertions.assertTrue(" + actualExpression + ");";
            case "byte", "Byte", "short", "Short", "int", "Integer", "long", "Long",
                    "float", "Float", "double", "Double", "char", "Character",
                    "String", "java.lang.String", "List", "java.util.List",
                    "Set", "java.util.Set", "Map", "java.util.Map",
                    "Optional", "java.util.Optional" ->
                    "org.junit.jupiter.api.Assertions.assertEquals(expected, " + actualExpression + ");";
            default -> "org.junit.jupiter.api.Assertions.assertSame(expected, " + actualExpression + ");";
        };
    }

    private String renderInvocationArguments(MethodCallExpr methodCallExpr) {
        List<String> arguments = methodCallExpr.getArguments().stream()
                .map(Expression::toString)
                .toList();
        return String.join(", ", arguments);
    }

    private Statement unwrapSingleStatement(Statement statement) {
        if (statement == null) {
            return null;
        }
        if (!statement.isBlockStmt()) {
            return statement;
        }
        List<Statement> statements = statement.asBlockStmt().getStatements();
        return statements.size() == 1 ? statements.get(0) : null;
    }

    private String buildBooleanGuardStub(ObjectCreationExpr objectCreationExpr,
                                         Analyze.AnalysisSummary analysisSummary,
                                         TargetConstructionPlan constructionPlan) {
        for (Expression argument : objectCreationExpr.getArguments()) {
            if (!argument.isNameExpr()) {
                continue;
            }
            String variableName = argument.asNameExpr().getNameAsString();
            String type = constructionPlan.variableTypes().get(variableName);
            if (type == null || !shouldMock(variableName, type, analysisSummary.mockPlan())) {
                continue;
            }
            String booleanMethodSignature = resolveBooleanMethodSignature(type, analysisSummary.availableMethods());
            if (booleanMethodSignature == null || booleanMethodSignature.isBlank()) {
                continue;
            }
            MethodDeclaration booleanMethod = parseMethodSignature(booleanMethodSignature);
            if (booleanMethod == null) {
                continue;
            }
            return "org.mockito.Mockito.when("
                    + variableName
                    + "."
                    + booleanMethod.getNameAsString()
                    + "("
                    + renderMatcherArguments(booleanMethod)
                    + ")).thenReturn(true);";
        }
        return null;
    }

    private String resolveBooleanMethodSignature(String type, Map<String, List<String>> availableMethods) {
        String targetSimpleName = simpleName(type);
        return availableMethods.entrySet().stream()
                .filter(entry -> targetSimpleName.equals(simpleName(entry.getKey())))
                .flatMap(entry -> entry.getValue().stream())
                .filter(signature -> signature != null && signature.trim().startsWith("boolean "))
                .findFirst()
                .orElse(null);
    }

    private MethodDeclaration parseMethodSignature(String signature) {
        if (signature == null || signature.isBlank()) {
            return null;
        }
        try {
            String normalized = signature.trim();
            if (!normalized.matches("^(public|protected|private)\\b.*")) {
                normalized = "public " + normalized;
            }
            return StaticJavaParser.parseMethodDeclaration(normalized + " {}");
        } catch (Exception ignored) {
            return null;
        }
    }

    private String renderMatcherArguments(MethodDeclaration declaration) {
        List<String> matchers = new ArrayList<>();
        declaration.getParameters().forEach(parameter ->
                matchers.add(matcherForType(parameter.getType().asString())));
        return String.join(", ", matchers);
    }

    private String matcherForType(String type) {
        return switch (stripDecorations(type)) {
            case "String", "java.lang.String" -> "org.mockito.ArgumentMatchers.anyString()";
            case "int", "Integer" -> "org.mockito.ArgumentMatchers.anyInt()";
            case "long", "Long" -> "org.mockito.ArgumentMatchers.anyLong()";
            case "boolean", "Boolean" -> "org.mockito.ArgumentMatchers.anyBoolean()";
            case "double", "Double" -> "org.mockito.ArgumentMatchers.anyDouble()";
            case "float", "Float" -> "org.mockito.ArgumentMatchers.anyFloat()";
            case "byte", "Byte" -> "org.mockito.ArgumentMatchers.anyByte()";
            case "short", "Short" -> "org.mockito.ArgumentMatchers.anyShort()";
            case "char", "Character" -> "org.mockito.ArgumentMatchers.anyChar()";
            default -> "org.mockito.ArgumentMatchers.any()";
        };
    }

    private boolean shouldMock(String parameterName, String type, MockPlan mockPlan) {
        if (mockPlan == null) {
            return false;
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(normaliseIdentifier(parameterName));
        candidates.add(lowerCamel(simpleName(type)));
        mockPlan.shouldMock().stream().map(this::normaliseIdentifier).forEach(candidates::add);
        for (MockTarget target : mockPlan.targets()) {
            candidates.add(normaliseIdentifier(target.identifier()));
            candidates.add(lowerCamel(simpleName(target.qualifiedType())));
        }
        String parameterCandidate = normaliseIdentifier(parameterName);
        String typeCandidate = lowerCamel(simpleName(type));
        return mockPlan.shouldMock().stream()
                .map(this::normaliseIdentifier)
                .anyMatch(name -> name.equals(parameterCandidate) || name.equals(typeCandidate))
                || mockPlan.targets().stream().anyMatch(target -> {
                    String identifier = normaliseIdentifier(target.identifier());
                    String mockType = lowerCamel(simpleName(target.qualifiedType()));
                    return identifier.equals(parameterCandidate)
                            || identifier.equals(typeCandidate)
                            || mockType.equals(typeCandidate);
                });
    }

    private boolean returnsTargetType(TestClassInfo classInfo,
                                      Analyze.TestTargetContext targetContext,
                                      MethodDeclaration declaration) {
        String returnSimpleName = simpleName(declaration.getType().asString());
        String targetSimpleName = targetContext == null ? "" : simpleName(targetContext.className());
        if (targetSimpleName.isBlank() && classInfo != null) {
            targetSimpleName = simpleName(classInfo.getClassName());
        }
        return !returnSimpleName.isBlank() && returnSimpleName.equals(targetSimpleName);
    }

    private boolean bodyReturnsNewTargetInstance(MethodDeclaration declaration) {
        if (declaration == null || declaration.getBody().isEmpty()) {
            return false;
        }
        List<Statement> statements = declaration.getBody().get().getStatements();
        if (statements.size() != 1 || !statements.get(0).isReturnStmt()) {
            return false;
        }
        ReturnStmt returnStmt = statements.get(0).asReturnStmt();
        if (returnStmt.getExpression().isEmpty() || !returnStmt.getExpression().get().isObjectCreationExpr()) {
            return false;
        }
        ObjectCreationExpr creationExpr = returnStmt.getExpression().get().asObjectCreationExpr();
        return simpleName(declaration.getType().asString()).equals(simpleName(creationExpr.getType().asString()));
    }

    private StateTransition resolveBooleanStateTransition(String targetType,
                                                          String targetMethod,
                                                          Map<String, List<String>> availableMethods) {
        String normalizedTarget = targetMethod == null ? "" : targetMethod.trim();
        String complement;
        String preconditionAssertion;
        String finalAssertion;
        if ("activate".equals(normalizedTarget)) {
            complement = "deactivate";
            preconditionAssertion = "assertFalse";
            finalAssertion = "assertTrue";
        } else if ("deactivate".equals(normalizedTarget)) {
            complement = "activate";
            preconditionAssertion = "assertTrue";
            finalAssertion = "assertFalse";
        } else {
            return null;
        }
        String booleanGetter = resolveBooleanGetter(targetType, availableMethods, "active");
        if (booleanGetter == null
                || !hasMethod(targetType, availableMethods, normalizedTarget, 0)
                || !hasMethod(targetType, availableMethods, complement, 0)) {
            return null;
        }
        return new StateTransition(complement, booleanGetter, preconditionAssertion, finalAssertion);
    }

    private String resolveBooleanGetter(String targetType,
                                        Map<String, List<String>> availableMethods,
                                        String stateToken) {
        if (availableMethods == null || availableMethods.isEmpty()) {
            return null;
        }
        String targetSimpleName = simpleName(targetType);
        String normalizedToken = stateToken == null ? "" : stateToken.toLowerCase(Locale.ROOT);
        return availableMethods.entrySet().stream()
                .filter(entry -> targetSimpleName.equals(simpleName(entry.getKey())))
                .flatMap(entry -> entry.getValue().stream())
                .map(this::parseMethodSignature)
                .filter(Objects::nonNull)
                .filter(method -> method.getParameters().isEmpty())
                .filter(method -> "boolean".equals(method.getType().asString())
                        || "Boolean".equals(method.getType().asString()))
                .map(MethodDeclaration::getNameAsString)
                .filter(name -> normalizedToken.isBlank() || name.toLowerCase(Locale.ROOT).contains(normalizedToken))
                .findFirst()
                .orElse(null);
    }

    private boolean hasMethod(String targetType,
                              Map<String, List<String>> availableMethods,
                              String methodName,
                              int arity) {
        if (targetType == null || targetType.isBlank()
                || availableMethods == null || availableMethods.isEmpty()
                || methodName == null || methodName.isBlank()) {
            return false;
        }
        String targetSimpleName = simpleName(targetType);
        return availableMethods.entrySet().stream()
                .filter(entry -> targetSimpleName.equals(simpleName(entry.getKey())))
                .flatMap(entry -> entry.getValue().stream())
                .map(this::parseMethodSignature)
                .filter(Objects::nonNull)
                .anyMatch(method -> methodName.equals(method.getNameAsString())
                        && method.getParameters().size() == arity);
    }

    private boolean returnsList(MethodDeclaration declaration) {
        if (declaration == null) {
            return false;
        }
        String type = stripDecorations(declaration.getType().asString());
        return "List".equals(type) || "java.util.List".equals(type);
    }

    private boolean returnsInteger(MethodDeclaration declaration) {
        if (declaration == null) {
            return false;
        }
        String type = stripDecorations(declaration.getType().asString());
        return "int".equals(type) || "Integer".equals(type) || "java.lang.Integer".equals(type);
    }

    private boolean bodyReturnsCollectionSize(MethodDeclaration declaration) {
        if (declaration == null || declaration.getBody().isEmpty()) {
            return false;
        }
        List<Statement> statements = declaration.getBody().get().getStatements();
        if (statements.size() != 1 || !statements.get(0).isReturnStmt()) {
            return false;
        }
        Optional<Expression> expression = statements.get(0).asReturnStmt().getExpression();
        if (expression.isEmpty() || !expression.get().isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = expression.get().asMethodCallExpr();
        return "size".equals(call.getNameAsString()) && call.getArguments().isEmpty();
    }

    private boolean bodyReturnsUnmodifiableCollection(MethodDeclaration declaration) {
        if (declaration == null || declaration.getBody().isEmpty()) {
            return false;
        }
        return declaration.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> "unmodifiableList".equals(call.getNameAsString())
                        || "unmodifiableSet".equals(call.getNameAsString())
                        || "unmodifiableMap".equals(call.getNameAsString())
                        || "copyOf".equals(call.getNameAsString()));
    }

    private boolean hasClockConstructor(String targetType,
                                        Map<String, List<ConstructorMetadata>> availableConstructors) {
        if (targetType == null || targetType.isBlank()
                || availableConstructors == null || availableConstructors.isEmpty()) {
            return false;
        }
        String targetSimpleName = simpleName(targetType);
        return availableConstructors.entrySet().stream()
                .filter(entry -> targetSimpleName.equals(simpleName(entry.getKey())))
                .flatMap(entry -> entry.getValue().stream())
                .filter(Objects::nonNull)
                .anyMatch(constructor -> constructor.parameters().size() == 1
                        && "Clock".equals(simpleName(constructor.parameters().get(0).type())));
    }

    private boolean hasUserStringConstructor(Map<String, List<ConstructorMetadata>> availableConstructors) {
        if (availableConstructors == null || availableConstructors.isEmpty()) {
            return false;
        }
        return availableConstructors.entrySet().stream()
                .filter(entry -> "User".equals(simpleName(entry.getKey())))
                .flatMap(entry -> entry.getValue().stream())
                .filter(Objects::nonNull)
                .anyMatch(constructor -> constructor.parameters().size() == 2
                        && constructor.parameters().stream()
                        .allMatch(parameter -> "String".equals(simpleName(parameter.type()))));
    }

    private String renderTypeReference(String type, Map<String, String> knownTypes) {
        String normalized = stripDecorations(type);
        if (normalized.isBlank()) {
            return type;
        }
        if (normalized.contains(".")) {
            return normalized;
        }
        return knownTypes.getOrDefault(simpleName(normalized), normalized);
    }

    private String stripDecorations(String type) {
        if (type == null) {
            return "";
        }
        String normalized = type.trim();
        int genericStart = normalized.indexOf('<');
        if (genericStart >= 0) {
            normalized = normalized.substring(0, genericStart);
        }
        return normalized.replace("...", "[]").trim();
    }

    private String extractFirstGenericType(String type) {
        if (type == null) {
            return "";
        }
        int start = type.indexOf('<');
        int end = type.lastIndexOf('>');
        if (start < 0 || end <= start) {
            return "";
        }
        String content = type.substring(start + 1, end).trim();
        int comma = content.indexOf(',');
        return comma >= 0 ? content.substring(0, comma).trim() : content;
    }

    private String resolveInvocationTarget(Analyze.TestTargetContext targetContext, Map<String, String> knownTypes) {
        if (targetContext.isStatic()) {
            return renderTypeReference(targetContext.className(), knownTypes);
        }
        return targetContext.instanceName();
    }

    private String resolvePackageName(java.nio.file.Path targetPath) {
        if (targetPath == null) {
            return "";
        }
        String normalized = targetPath.toString().replace('\\', '/');
        String marker = "/src/test/java/";
        int markerIndex = normalized.indexOf(marker);
        if (markerIndex < 0) {
            return "";
        }
        String relative = normalized.substring(markerIndex + marker.length());
        int slashIndex = relative.lastIndexOf('/');
        if (slashIndex < 0) {
            return "";
        }
        return relative.substring(0, slashIndex).replace('/', '.');
    }

    private String simpleName(String type) {
        String normalized = stripDecorations(type);
        int lastDot = normalized.lastIndexOf('.');
        return lastDot >= 0 ? normalized.substring(lastDot + 1) : normalized;
    }

    private String lowerCamel(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = simpleName(value);
        return normalized.substring(0, 1).toLowerCase(Locale.ROOT) + normalized.substring(1);
    }

    private String normaliseIdentifier(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String parameterVariableName(ParameterMetadata parameter, int index) {
        String candidate = parameter == null ? "" : parameter.name();
        if (candidate != null && !candidate.isBlank()) {
            return candidate.trim();
        }
        return "arg" + index;
    }

    private String capitalise(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private record StateTransition(String preconditionMutator,
                                   String booleanGetter,
                                   String preconditionAssertion,
                                   String finalAssertion) {
    }

    private record StaticCollectionLifecycle(String clearName,
                                             String snapshotName,
                                             String emitterName,
                                             List<String> emitterArguments,
                                             Optional<String> expectedElement) {
    }

    private record InstanceCollectionLifecycle(String countName,
                                               String emitterName,
                                               List<String> emitterArguments,
                                               Optional<String> clearName) {
    }

    private record TargetConstructionPlan(List<String> setupLines,
                                          String assignmentLine,
                                          Map<String, String> variableTypes) {
    }
}
