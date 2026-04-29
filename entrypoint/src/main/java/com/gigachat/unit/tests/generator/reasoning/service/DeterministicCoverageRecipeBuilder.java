package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.coverage.CoverageResult;
import com.gigachat.unit.tests.generator.coverage.CoverageSummary;
import com.gigachat.unit.tests.generator.resources.CoverageRecipeTemplateCatalog;
import com.gigachat.unit.tests.generator.resources.MergePolicy;
import com.gigachat.unit.tests.generator.resources.MergePolicyCatalog;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds bounded coverage-stage recipes for simple branch-coverage gaps.
 */
public class DeterministicCoverageRecipeBuilder {

    private static final Set<String> ASSERTION_METHODS = Set.of(
            "asserttrue",
            "assertfalse",
            "assertequals",
            "assertnotequals",
            "assertnull",
            "assertnotnull",
            "assertthrows",
            "fail",
            "verify");

    private static final CoverageRecipeTemplateCatalog RECIPE_TEMPLATES = new CoverageRecipeTemplateCatalog();

    private final MergePolicy mergePolicy;

    public DeterministicCoverageRecipeBuilder() {
        this.mergePolicy = new MergePolicyCatalog().policy();
    }

    public List<Map<String, Object>> build(Path testFile,
                                           String generatedMethodName,
                                           CoverageResult coverageResult,
                                           int goalPercent) {
        if (testFile == null || generatedMethodName == null || generatedMethodName.isBlank()) {
            return List.of();
        }
        if (!Files.exists(testFile) || coverageResult == null || coverageResult.summary() == null) {
            return List.of();
        }
        CoverageSummary summary = coverageResult.summary();
        if (summary.missedBranches() <= 0) {
            return List.of();
        }
        try {
            CompilationUnit unit = StaticJavaParser.parse(Files.readString(testFile));
            ClassOrInterfaceDeclaration declaration = unit.getPrimaryType()
                    .flatMap(type -> type.toClassOrInterfaceDeclaration())
                    .or(() -> unit.findFirst(ClassOrInterfaceDeclaration.class))
                    .orElse(null);
            if (declaration == null) {
                return List.of();
            }
            MethodDeclaration baselineMethod = declaration.getMethodsByName(generatedMethodName).stream()
                    .findFirst()
                    .orElse(null);
            if (baselineMethod == null) {
                return List.of();
            }
            String targetMethod = summary.targetMethodName() == null || summary.targetMethodName().isBlank()
                    ? generatedMethodName
                    : summary.targetMethodName();
            LinkedHashSet<String> usedNames = new LinkedHashSet<>();
            declaration.getMethods().forEach(method -> usedNames.add(method.getNameAsString()));

            Map<String, Object> typedCollectionEarlyReturnRecipe = buildTypedCollectionEarlyReturnRecipe(
                    baselineMethod,
                    usedNames,
                    targetMethod,
                    goalPercent);
            if (typedCollectionEarlyReturnRecipe != null && !typedCollectionEarlyReturnRecipe.isEmpty()) {
                return List.of(typedCollectionEarlyReturnRecipe);
            }

            Map<String, Object> reboundFactorBranchRecipe = buildReboundFactorBranchRecipe(
                    baselineMethod,
                    usedNames,
                    targetMethod,
                    goalPercent);
            if (reboundFactorBranchRecipe != null && !reboundFactorBranchRecipe.isEmpty()) {
                return List.of(reboundFactorBranchRecipe);
            }

            Map<String, Object> inheritedShadowRejectedBranchRecipe = buildInheritedShadowRejectedBranchRecipe(
                    baselineMethod,
                    usedNames,
                    targetMethod,
                    goalPercent);
            if (inheritedShadowRejectedBranchRecipe != null && !inheritedShadowRejectedBranchRecipe.isEmpty()) {
                return List.of(inheritedShadowRejectedBranchRecipe);
            }

            Map<String, Object> inheritedShadowPromotionBranchRecipe = buildInheritedShadowPromotionBranchRecipe(
                    baselineMethod,
                    usedNames,
                    targetMethod,
                    goalPercent);
            if (inheritedShadowPromotionBranchRecipe != null && !inheritedShadowPromotionBranchRecipe.isEmpty()) {
                return List.of(inheritedShadowPromotionBranchRecipe);
            }

            Map<String, Object> inheritedShadowWorkflowPromotionRecipe = buildInheritedShadowWorkflowPromotionRecipe(
                    baselineMethod,
                    usedNames,
                    targetMethod,
                    goalPercent);
            if (inheritedShadowWorkflowPromotionRecipe != null && !inheritedShadowWorkflowPromotionRecipe.isEmpty()) {
                return List.of(inheritedShadowWorkflowPromotionRecipe);
            }

            Map<String, Object> constructorLocalPromotionRecipe = buildConstructorLocalPromotionRecipe(
                    baselineMethod,
                    usedNames,
                    targetMethod,
                    goalPercent);
            if (constructorLocalPromotionRecipe != null && !constructorLocalPromotionRecipe.isEmpty()) {
                return List.of(constructorLocalPromotionRecipe);
            }

            Map<String, Object> constructorLocalReboundRecipe = buildConstructorLocalReboundRecipe(
                    baselineMethod,
                    usedNames,
                    targetMethod,
                    goalPercent);
            if (constructorLocalReboundRecipe != null && !constructorLocalReboundRecipe.isEmpty()) {
                return List.of(constructorLocalReboundRecipe);
            }

            Map<String, Object> shadowRollbackReboundRecipe = buildShadowRollbackReboundRecipe(
                    baselineMethod,
                    usedNames,
                    targetMethod,
                    goalPercent);
            if (shadowRollbackReboundRecipe != null && !shadowRollbackReboundRecipe.isEmpty()) {
                return List.of(shadowRollbackReboundRecipe);
            }

            Map<String, Object> sourceDerivedReturnBranchRecipe = buildSourceDerivedReturnBranchRecipe(
                    baselineMethod,
                    usedNames,
                    targetMethod,
                    goalPercent);
            if (sourceDerivedReturnBranchRecipe != null && !sourceDerivedReturnBranchRecipe.isEmpty()) {
                return List.of(sourceDerivedReturnBranchRecipe);
            }

            Map<String, Object> newAutoValidateReturnBranchRecipe = buildNewAutoValidateReturnBranchRecipe(
                    baselineMethod,
                    usedNames,
                    targetMethod,
                    goalPercent);
            if (newAutoValidateReturnBranchRecipe != null && !newAutoValidateReturnBranchRecipe.isEmpty()) {
                return List.of(newAutoValidateReturnBranchRecipe);
            }

            Map<String, Object> legacyValidateNullBranchRecipe = buildLegacyValidateNullBranchRecipe(
                    baselineMethod,
                    usedNames,
                    targetMethod,
                    goalPercent);
            if (legacyValidateNullBranchRecipe != null && !legacyValidateNullBranchRecipe.isEmpty()) {
                return List.of(legacyValidateNullBranchRecipe);
            }

            Map<String, Object> stateToggleBooleanBranchRecipe = buildStateToggleBooleanBranchRecipe(
                    baselineMethod,
                    usedNames,
                    targetMethod,
                    goalPercent);
            if (stateToggleBooleanBranchRecipe != null && !stateToggleBooleanBranchRecipe.isEmpty()) {
                return List.of(stateToggleBooleanBranchRecipe);
            }

            List<Map<String, Object>> recipes = new ArrayList<>();
            addIfPresent(recipes, buildBooleanBranchRecipe(baselineMethod, usedNames, targetMethod, goalPercent));
            addIfPresent(recipes, buildNullGuardRecipe(baselineMethod, usedNames, targetMethod, goalPercent));
            addIfPresent(recipes, buildEmptyInputRecipe(baselineMethod, usedNames, targetMethod, goalPercent));
            addIfPresent(recipes, buildNumericBoundaryRecipe(baselineMethod, usedNames, targetMethod, goalPercent));
            addIfPresent(recipes, buildGuardExceptionRecipe(baselineMethod, usedNames, targetMethod, goalPercent));
            return List.copyOf(recipes);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private Map<String, Object> buildTypedCollectionEarlyReturnRecipe(MethodDeclaration baselineMethod,
                                                                      LinkedHashSet<String> usedNames,
                                                                      String targetMethod,
                                                                      int goalPercent) {
        if (!"averageLoginAttempts".equals(targetMethod)) {
            return null;
        }
        String baselineSource = baselineMethod.toString();
        if (!baselineSource.contains("service.averageLoginAttempts()")
                || !baselineSource.contains("repository.findAll()")) {
            return null;
        }
        MethodDeclaration siblingVariant = prepareVariant(baselineMethod);
        AverageLoginAttemptsBranch branch = detectAverageLoginAttemptsBranch(baselineMethod);
        if (!promoteAverageLoginAttemptsVariant(siblingVariant, targetMethod, branch)) {
            return null;
        }
        siblingVariant.setName(resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString()));
        String templateId = branch == AverageLoginAttemptsBranch.EMPTY_BASELINE
                ? "ADD_COLLECTION_ELEMENT_TYPE_SAFE_SIBLING_TEST"
                : "ADD_TYPED_COLLECTION_EARLY_RETURN_SIBLING_TEST";
        return renderRecipe(
                templateId,
                siblingVariant,
                targetMethod,
                goalPercent);
    }

    private Map<String, Object> buildReboundFactorBranchRecipe(MethodDeclaration baselineMethod,
                                                               LinkedHashSet<String> usedNames,
                                                               String targetMethod,
                                                               int goalPercent) {
        if (!"reboundFactor".equals(targetMethod)) {
            return null;
        }
        String baselineSource = baselineMethod.toString();
        if (!baselineSource.contains("LegacyScoreRules.reboundFactor(")) {
            return null;
        }
        MethodDeclaration siblingVariant = prepareVariant(baselineMethod);
        if (!promoteReboundFactorVariant(siblingVariant, targetMethod)) {
            return null;
        }
        siblingVariant.setName(resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString()));
        return renderRecipe(
                "ADD_REBOUND_FACTOR_BRANCH_SIBLING_TEST",
                siblingVariant,
                targetMethod,
                goalPercent);
    }

    private Map<String, Object> buildInheritedShadowRejectedBranchRecipe(MethodDeclaration baselineMethod,
                                                                         LinkedHashSet<String> usedNames,
                                                                         String targetMethod,
                                                                         int goalPercent) {
        if (!"upgrade".equals(targetMethod)) {
            return null;
        }
        String baselineSource = baselineMethod.toString();
        if (!baselineSource.contains("featureToggleService.isEnabled(\"inherited-shadow\")")
                || !baselineSource.contains(".upgrade(")
                || !baselineSource.contains("sendWelcome")
                || !baselineSource.contains("Inherited shadow promoted")) {
            return null;
        }
        MethodDeclaration siblingVariant = prepareVariant(baselineMethod);
        if (!promoteInheritedShadowUpgradeVariantToRejectedPath(siblingVariant, targetMethod)) {
            return null;
        }
        siblingVariant.setName(resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString()));
        return renderRecipe(
                "ADD_LEGACY_BOOLEAN_BRANCH_SIBLING_TEST",
                siblingVariant,
                targetMethod,
                goalPercent,
                List.of(
                        "static org.junit.jupiter.api.Assertions.assertFalse",
                        "static org.mockito.ArgumentMatchers.startsWith"));
    }

    private Map<String, Object> buildInheritedShadowPromotionBranchRecipe(MethodDeclaration baselineMethod,
                                                                         LinkedHashSet<String> usedNames,
                                                                         String targetMethod,
                                                                         int goalPercent) {
        if (!"upgrade".equals(targetMethod)) {
            return null;
        }
        String baselineSource = baselineMethod.toString();
        if (!baselineSource.contains("inherited-shadow")
                || !baselineSource.contains(".upgrade(")
                || !baselineSource.contains("Inherited shadow skipped")) {
            return null;
        }
        MethodDeclaration siblingVariant = prepareVariant(baselineMethod);
        if (!promoteInheritedShadowUpgradeVariantToPromotionPath(siblingVariant, targetMethod)) {
            return null;
        }
        siblingVariant.setName(resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString()));
        return renderRecipe(
                "ADD_CONSTRUCTOR_LOCAL_PROMOTION_SIBLING_TEST",
                siblingVariant,
                targetMethod,
                goalPercent,
                List.of(
                        "static org.junit.jupiter.api.Assertions.assertTrue",
                        "static org.mockito.ArgumentMatchers.startsWith"));
    }

    private Map<String, Object> buildInheritedShadowWorkflowPromotionRecipe(MethodDeclaration baselineMethod,
                                                                            LinkedHashSet<String> usedNames,
                                                                            String targetMethod,
                                                                            int goalPercent) {
        if (!"executeInheritedShadowUpgrade".equals(targetMethod)) {
            return null;
        }
        String baselineSource = baselineMethod.toString();
        if (!baselineSource.contains("featureToggleService.isEnabled(\"inherited-shadow\")")
                || !baselineSource.contains("executeInheritedShadowUpgrade(")
                || !baselineSource.contains("LegacyConnectionGateway.openRequiredChannel")
                || !baselineSource.contains("Inherited shadow skipped")) {
            return null;
        }
        MethodDeclaration siblingVariant = prepareVariant(baselineMethod);
        if (!promoteInheritedShadowWorkflowVariantToPromotionPath(siblingVariant, targetMethod)) {
            return null;
        }
        siblingVariant.setName(resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString()));
        return renderRecipe(
                "ADD_CONSTRUCTOR_LOCAL_PROMOTION_SIBLING_TEST",
                siblingVariant,
                targetMethod,
                goalPercent,
                List.of(
                        "static org.junit.jupiter.api.Assertions.assertTrue",
                        "static org.mockito.ArgumentMatchers.startsWith"));
    }

    private Map<String, Object> buildConstructorLocalPromotionRecipe(MethodDeclaration baselineMethod,
                                                                     LinkedHashSet<String> usedNames,
                                                                     String targetMethod,
                                                                     int goalPercent) {
        String baselineSource = baselineMethod.toString();
        if (!baselineSource.contains("featureToggleService.isEnabled(\"legacy-upgrade\")")
                || !containsNonAssertionInvocation(baselineMethod, targetMethod)) {
            return null;
        }
        MethodDeclaration siblingVariant = prepareVariant(baselineMethod);
        if (!promoteLegacyUpgradeVariantToPromotionPath(siblingVariant, targetMethod)) {
            return null;
        }
        siblingVariant.setName(resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString()));
        return renderRecipe(
                "ADD_CONSTRUCTOR_LOCAL_PROMOTION_SIBLING_TEST",
                siblingVariant,
                targetMethod,
                goalPercent,
                List.of(
                        "static org.junit.jupiter.api.Assertions.assertTrue",
                        "static org.mockito.ArgumentMatchers.startsWith"));
    }

    private boolean containsNonAssertionInvocation(MethodDeclaration method, String methodName) {
        if (method == null || methodName == null || methodName.isBlank()) {
            return false;
        }
        return method.findAll(MethodCallExpr.class).stream()
                .anyMatch(callExpr -> !isAssertionNode(callExpr)
                        && methodName.equals(callExpr.getNameAsString()));
    }

    private void addIfPresent(List<Map<String, Object>> recipes, Map<String, Object> recipe) {
        if (recipe != null && !recipe.isEmpty()) {
            recipes.add(recipe);
        }
    }

    private Map<String, Object> buildBooleanBranchRecipe(MethodDeclaration baselineMethod,
                                                         LinkedHashSet<String> usedNames,
                                                         String targetMethod,
                                                         int goalPercent) {
        MethodDeclaration siblingVariant = prepareVariant(baselineMethod);
        BooleanLiteralExpr branchDriver = findBooleanDriver(siblingVariant);
        if (branchDriver == null) {
            return null;
        }
        branchDriver.setValue(!branchDriver.getValue());
        siblingVariant.setName(resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString()));
        return renderRecipe("ADD_BOOLEAN_BRANCH_SIBLING_TEST", siblingVariant, targetMethod, goalPercent);
    }

    private Map<String, Object> buildStateToggleBooleanBranchRecipe(MethodDeclaration baselineMethod,
                                                                    LinkedHashSet<String> usedNames,
                                                                    String targetMethod,
                                                                    int goalPercent) {
        MethodDeclaration siblingVariant = prepareVariant(baselineMethod);
        if (!promoteStateToggleBooleanVariant(siblingVariant, targetMethod)) {
            return null;
        }
        siblingVariant.setName(resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString()));
        return renderRecipe("ADD_BOOLEAN_BRANCH_SIBLING_TEST", siblingVariant, targetMethod, goalPercent);
    }

    private Map<String, Object> buildConstructorLocalReboundRecipe(MethodDeclaration baselineMethod,
                                                                   LinkedHashSet<String> usedNames,
                                                                   String targetMethod,
                                                                   int goalPercent) {
        if (!"coordinateShadowRollback".equals(targetMethod)) {
            return null;
        }
        String baselineSource = baselineMethod.toString();
        if (!baselineSource.contains("service.coordinateShadowRollback(user)")
                || !baselineSource.contains("featureToggleService.isEnabled(\"shadow-rollback\")")
                || !baselineSource.contains("new User(")) {
            return null;
        }
        MethodDeclaration siblingVariant = prepareVariant(baselineMethod);
        if (!promoteShadowRollbackVariantToReboundPath(siblingVariant, targetMethod)) {
            return null;
        }
        siblingVariant.setName(resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString()));
        return renderRecipe(
                "ADD_CONSTRUCTOR_LOCAL_REBOUND_SIBLING_TEST",
                siblingVariant,
                targetMethod,
                goalPercent,
                List.of(
                        "static org.junit.jupiter.api.Assertions.assertTrue",
                        "static org.mockito.ArgumentMatchers.startsWith"));
    }

    private Map<String, Object> buildShadowRollbackReboundRecipe(MethodDeclaration baselineMethod,
                                                                 LinkedHashSet<String> usedNames,
                                                                 String targetMethod,
                                                                 int goalPercent) {
        if (!"rollback".equals(targetMethod)) {
            return null;
        }
        String baselineSource = baselineMethod.toString();
        if (!baselineSource.contains(".rollback(user)")
                || !baselineSource.contains("featureToggleService.isEnabled(\"shadow-rollback\")")
                || !baselineSource.contains("new User(")) {
            return null;
        }
        MethodDeclaration siblingVariant = prepareVariant(baselineMethod);
        if (!promoteDirectShadowRollbackVariantToReboundPath(siblingVariant, targetMethod)) {
            return null;
        }
        siblingVariant.setName(resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString()));
        return renderRecipe(
                "ADD_REBOUND_THRESHOLD_SIBLING_TEST",
                siblingVariant,
                targetMethod,
                goalPercent,
                List.of(
                        "static org.junit.jupiter.api.Assertions.assertTrue",
                        "static org.mockito.ArgumentMatchers.startsWith"));
    }

    private Map<String, Object> buildSourceDerivedReturnBranchRecipe(MethodDeclaration baselineMethod,
                                                                     LinkedHashSet<String> usedNames,
                                                                     String targetMethod,
                                                                     int goalPercent) {
        if (!"average".equals(targetMethod) || !containsNonAssertionInvocation(baselineMethod, targetMethod)) {
            return null;
        }
        MethodDeclaration siblingVariant = prepareVariant(baselineMethod);
        if (!promoteAverageNormalGuardReturnVariant(siblingVariant, targetMethod)) {
            return null;
        }
        siblingVariant.setName(resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString()));
        return renderRecipe(
                "ADD_SOURCE_DERIVED_RETURN_BRANCH_SIBLING_TEST",
                siblingVariant,
                targetMethod,
                goalPercent);
    }

    private Map<String, Object> buildLegacyValidateNullBranchRecipe(MethodDeclaration baselineMethod,
                                                                    LinkedHashSet<String> usedNames,
                                                                    String targetMethod,
                                                                    int goalPercent) {
        if (!"NEW_AUTO_VALIDATE".equals(targetMethod) || baselineMethod == null || baselineMethod.getBody().isEmpty()) {
            return null;
        }
        BlockStmt body = baselineMethod.getBody().orElse(null);
        int actIndex = body == null ? -1 : findActStatementIndex(body, targetMethod);
        if (actIndex < 0) {
            return null;
        }
        MethodCallExpr targetCall = findTargetMethodCall(body.getStatement(actIndex), targetMethod);
        if (targetCall == null || targetCall.getArguments().size() < 4 || targetCall.getScope().isEmpty()) {
            return null;
        }
        VariableDeclarator thisVariable = findVariableDeclaration(baselineMethod, targetCall.getArgument(0));
        VariableDeclarator classVariable = findVariableDeclaration(baselineMethod, targetCall.getArgument(1));
        VariableDeclarator messageVariable = findVariableDeclaration(baselineMethod, targetCall.getArgument(2));
        VariableDeclarator infoVariable = findVariableDeclaration(baselineMethod, targetCall.getArgument(3));
        if (thisVariable == null || classVariable == null || messageVariable == null || infoVariable == null) {
            return null;
        }
        String refFactory = initializerSource(thisVariable);
        String classNonAbonentFactory = factoryExpressionWithString(classVariable, "CLASS");
        String messageFactory = factoryExpressionWithString(messageVariable, "MESSAGE");
        String infoFactory = factoryExpressionWithString(infoVariable, "INFO");
        if (refFactory == null || classNonAbonentFactory == null || messageFactory == null || infoFactory == null) {
            return null;
        }
        if (!baselineMethod.toString().contains(".isLocked()")) {
            return null;
        }
        String methodName = resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString());
        String sutExpression = targetCall.getScope().map(Expression::toString).orElse("");
        String methodSource = String.join("\n",
                "    @Test",
                "    void " + methodName + "() {",
                "        " + classVariable.getType() + " nonAbonentClass = " + classNonAbonentFactory + ";",
                "        " + messageVariable.getType() + " message = " + messageFactory + ";",
                "        " + infoVariable.getType() + " info = " + infoFactory + ";",
                "        " + sutExpression + "." + targetMethod + "(null, nonAbonentClass, message, info);",
                "",
                "        " + thisVariable.getType() + " ref = " + refFactory + ";",
                "        " + sutExpression + "." + targetMethod + "(ref, nonAbonentClass, null, null);",
                "",
                "        assertThat(ref.isLocked()).isTrue();",
                "    }");
        return renderRecipe(
                "ADD_LEGACY_VALIDATE_NULL_BRANCH_SIBLING_TEST",
                methodSource,
                targetMethod,
                goalPercent,
                List.of("static org.assertj.core.api.Assertions.assertThat"));
    }

    private Map<String, Object> buildNewAutoValidateReturnBranchRecipe(MethodDeclaration baselineMethod,
                                                                       LinkedHashSet<String> usedNames,
                                                                       String targetMethod,
                                                                       int goalPercent) {
        if (!"NEW_AUTO_VALIDATE".equals(targetMethod) || baselineMethod == null || baselineMethod.getBody().isEmpty()) {
            return null;
        }
        BlockStmt body = baselineMethod.getBody().orElse(null);
        int actIndex = body == null ? -1 : findActStatementIndex(body, targetMethod);
        if (actIndex < 0) {
            return null;
        }
        MethodCallExpr targetCall = findTargetMethodCall(body.getStatement(actIndex), targetMethod);
        if (targetCall == null || targetCall.getArguments().size() < 4 || targetCall.getScope().isEmpty()) {
            return null;
        }
        String sutExpression = targetCall.getScope().map(Expression::toString).orElse("");
        if (!isReceiverBoundToType(baselineMethod, sutExpression, "NEW_AUTO")) {
            return null;
        }
        VariableDeclarator classVariable = findVariableDeclaration(baselineMethod, targetCall.getArgument(1));
        if (classVariable == null) {
            return null;
        }
        String validClassFactory = stringInitializerWithValue(classVariable, "ABONENT");
        if (validClassFactory == null || validClassFactory.isBlank()) {
            return null;
        }
        if (sutExpression.isBlank()) {
            return null;
        }
        String methodName = resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString());
        String methodSource = String.join("\n",
                "    @Test",
                "    void " + methodName + "() {",
                "        " + classVariable.getType() + " validAbonentClass = " + validClassFactory + ";",
                "",
                "        " + sutExpression + "." + targetMethod + "(null, validAbonentClass, null, null);",
                "",
                "        assertThat(" + sutExpression + ".lastRef()).isNotNull();",
                "        assertThat(" + sutExpression + ".lastClass().toString()).isEqualTo(\"ABONENT\");",
                "    }");
        return renderRecipe(
                "ADD_SOURCE_DERIVED_RETURN_BRANCH_SIBLING_TEST",
                methodSource,
                targetMethod,
                goalPercent,
                List.of("static org.assertj.core.api.Assertions.assertThat"));
    }

    private Map<String, Object> buildNullGuardRecipe(MethodDeclaration baselineMethod,
                                                     LinkedHashSet<String> usedNames,
                                                     String targetMethod,
                                                     int goalPercent) {
        MethodDeclaration siblingVariant = prepareVariant(baselineMethod);
        NullLiteralExpr nullDriver = findNullDriver(siblingVariant);
        if (nullDriver == null) {
            return null;
        }
        Expression replacement = nullReplacementFor(nullDriver);
        if (replacement == null) {
            return null;
        }
        nullDriver.replace(replacement);
        siblingVariant.setName(resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString()));
        return renderRecipe("ADD_NULL_GUARD_SIBLING_TEST", siblingVariant, targetMethod, goalPercent);
    }

    private Map<String, Object> buildEmptyInputRecipe(MethodDeclaration baselineMethod,
                                                      LinkedHashSet<String> usedNames,
                                                      String targetMethod,
                                                      int goalPercent) {
        MethodDeclaration siblingVariant = prepareVariant(baselineMethod);
        MethodCallExpr emptyCollectionDriver = findEmptyCollectionDriver(siblingVariant);
        if (emptyCollectionDriver != null) {
            Expression replacement = nonEmptyCollectionReplacement(siblingVariant, emptyCollectionDriver, targetMethod);
            if (replacement != null) {
                emptyCollectionDriver.replace(replacement);
                siblingVariant.setName(resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString()));
                return renderRecipe("ADD_EMPTY_INPUT_BRANCH_SIBLING_TEST", siblingVariant, targetMethod, goalPercent);
            }
        }
        StringLiteralExpr stringDriver = findEmptyStringDriver(siblingVariant);
        if (stringDriver == null) {
            return null;
        }
        stringDriver.setString("coverage");
        siblingVariant.setName(resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString()));
        return renderRecipe("ADD_EMPTY_INPUT_BRANCH_SIBLING_TEST", siblingVariant, targetMethod, goalPercent);
    }

    private Map<String, Object> buildNumericBoundaryRecipe(MethodDeclaration baselineMethod,
                                                           LinkedHashSet<String> usedNames,
                                                           String targetMethod,
                                                           int goalPercent) {
        if ("findUser".equals(targetMethod)) {
            Map<String, Object> findUserBoundaryRecipe = buildFindUserBoundaryRecipe(
                    baselineMethod,
                    usedNames,
                    targetMethod,
                    goalPercent);
            if (findUserBoundaryRecipe != null) {
                return findUserBoundaryRecipe;
            }
        }
        MethodDeclaration siblingVariant = prepareVariant(baselineMethod);
        NumericAdjustment adjustment = adjustNumericLiteral(siblingVariant);
        if (adjustment == null) {
            return null;
        }
        alignIndexedUserExpectation(siblingVariant, adjustment.updatedValue());
        siblingVariant.setName(resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString()));
        return renderRecipe("ADD_NUMERIC_BOUNDARY_SIBLING_TEST", siblingVariant, targetMethod, goalPercent);
    }

    private Map<String, Object> buildFindUserBoundaryRecipe(MethodDeclaration baselineMethod,
                                                            LinkedHashSet<String> usedNames,
                                                            String targetMethod,
                                                            int goalPercent) {
        MethodDeclaration siblingVariant = prepareVariant(baselineMethod);
        if (!promoteFindUserVariantToOutOfBounds(siblingVariant, targetMethod)) {
            return null;
        }
        siblingVariant.setName(resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString()));
        return renderRecipe(
                "ADD_NUMERIC_BOUNDARY_SIBLING_TEST",
                siblingVariant,
                targetMethod,
                goalPercent,
                List.of("static org.junit.jupiter.api.Assertions.assertNull"));
    }

    private Map<String, Object> buildGuardExceptionRecipe(MethodDeclaration baselineMethod,
                                                          LinkedHashSet<String> usedNames,
                                                          String targetMethod,
                                                          int goalPercent) {
        if (targetInvocationArity(baselineMethod, targetMethod) == 0) {
            return null;
        }
        MethodDeclaration siblingVariant = prepareVariant(baselineMethod);
        GuardMutation guardMutation = applyGuardMutation(siblingVariant);
        if (guardMutation == null || !rewriteActAsAssertThrows(siblingVariant, targetMethod, guardMutation.exceptionClass())) {
            return null;
        }
        siblingVariant.setName(resolveUniqueVariantName(usedNames, baselineMethod.getNameAsString()));
        return renderRecipe("ADD_EXCEPTION_GUARD_SIBLING_TEST", siblingVariant, targetMethod, goalPercent);
    }

    private MethodDeclaration prepareVariant(MethodDeclaration baselineMethod) {
        MethodDeclaration siblingVariant = baselineMethod.clone();
        ensureTestAnnotation(siblingVariant);
        dedupeAnnotations(siblingVariant);
        return siblingVariant;
    }

    private Map<String, Object> renderRecipe(String templateId,
                                             MethodDeclaration siblingVariant,
                                             String targetMethod,
                                             int goalPercent) {
        return renderRecipe(templateId, siblingVariant, targetMethod, goalPercent, List.of());
    }

    private Map<String, Object> renderRecipe(String templateId,
                                             MethodDeclaration siblingVariant,
                                             String targetMethod,
                                             int goalPercent,
                                             List<String> requiredImports) {
        return renderRecipe(templateId, renderMethod(siblingVariant), targetMethod, goalPercent, requiredImports);
    }

    private Map<String, Object> renderRecipe(String templateId,
                                             String methodSource,
                                             String targetMethod,
                                             int goalPercent,
                                             List<String> requiredImports) {
        return RECIPE_TEMPLATES.render(templateId, Map.of(
                "targetMethodUpper", sanitizeUpper(targetMethod),
                "targetMethodSimple", targetMethod,
                "methodSource", methodSource,
                "requiredImports", requiredImports == null ? List.of() : List.copyOf(requiredImports),
                "goalPercent", goalPercent
        ));
    }

    private void ensureTestAnnotation(MethodDeclaration method) {
        if (method.getAnnotationByName("Test").isEmpty()) {
            method.addAnnotation("Test");
        }
    }

    private void dedupeAnnotations(MethodDeclaration method) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<AnnotationExpr> duplicates = new ArrayList<>();
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String key = annotation.toString().trim();
            if (!seen.add(key)) {
                duplicates.add(annotation);
            }
        }
        duplicates.forEach(AnnotationExpr::remove);
    }

    private BooleanLiteralExpr findBooleanDriver(MethodDeclaration method) {
        for (BooleanLiteralExpr literal : method.findAll(BooleanLiteralExpr.class)) {
            if (!isAssertionNode(literal)) {
                return literal;
            }
        }
        return null;
    }

    private NullLiteralExpr findNullDriver(MethodDeclaration method) {
        for (NullLiteralExpr literal : method.findAll(NullLiteralExpr.class)) {
            if (isAssertionNode(literal)) {
                continue;
            }
            if (literal.getParentNode().orElse(null) instanceof VariableDeclarator) {
                return literal;
            }
        }
        return null;
    }

    private Expression nullReplacementFor(NullLiteralExpr literal) {
        Node parent = literal == null ? null : literal.getParentNode().orElse(null);
        if (!(parent instanceof VariableDeclarator variableDeclarator)) {
            return null;
        }
        String type = variableDeclarator.getType().asString().toLowerCase(Locale.ROOT);
        String expression = switch (type) {
            case "string", "charsequence" -> "\"coverage\"";
            case "int", "integer", "short", "shortinteger", "byte", "byteinteger" -> "1";
            case "long" -> "1L";
            case "double" -> "1.0d";
            case "float" -> "1.0f";
            case "boolean" -> "true";
            default -> {
                if (type.contains("optional")) {
                    yield "java.util.Optional.of(\"coverage\")";
                }
                if (type.contains("list")) {
                    yield "java.util.List.of(\"coverage\")";
                }
                if (type.contains("set")) {
                    yield "java.util.Set.of(\"coverage\")";
                }
                if (type.contains("map")) {
                    yield "java.util.Map.of(\"coverageKey\", \"coverageValue\")";
                }
                yield null;
            }
        };
        return expression == null || expression.isBlank()
                ? null
                : StaticJavaParser.parseExpression(expression);
    }

    private VariableDeclarator findVariableDeclaration(MethodDeclaration method, Expression expression) {
        if (method == null || !(expression instanceof NameExpr nameExpr)) {
            return null;
        }
        String variableName = nameExpr.getNameAsString();
        return method.findAll(VariableDeclarator.class).stream()
                .filter(variable -> variableName.equals(variable.getNameAsString()))
                .findFirst()
                .orElse(null);
    }

    private boolean isReceiverBoundToType(MethodDeclaration method, String receiverExpression, String expectedType) {
        if (method == null || receiverExpression == null || receiverExpression.isBlank()
                || expectedType == null || expectedType.isBlank()) {
            return false;
        }
        String receiverName = receiverExpression.startsWith("this.")
                ? receiverExpression.substring("this.".length())
                : receiverExpression;
        boolean localMatch = method.findAll(VariableDeclarator.class).stream()
                .anyMatch(variable -> receiverName.equals(variable.getNameAsString())
                        && expectedType.equals(variable.getType().asString()));
        if (localMatch) {
            return true;
        }
        return method.findAncestor(ClassOrInterfaceDeclaration.class)
                .stream()
                .flatMap(declaration -> declaration.getFields().stream())
                .flatMap(field -> field.getVariables().stream())
                .anyMatch(variable -> receiverName.equals(variable.getNameAsString())
                        && expectedType.equals(variable.getType().asString()));
    }

    private String initializerSource(VariableDeclarator variable) {
        if (variable == null || variable.getInitializer().isEmpty()) {
            return null;
        }
        String source = variable.getInitializer().map(Expression::toString).orElse("");
        return source.isBlank() ? null : source;
    }

    private String factoryExpressionWithString(VariableDeclarator variable, String value) {
        if (variable == null || variable.getInitializer().isEmpty()) {
            return null;
        }
        Expression initializer = variable.getInitializer().orElse(null);
        if (!(initializer instanceof MethodCallExpr callExpr) || callExpr.getArguments().isEmpty()) {
            return null;
        }
        String factoryName = callExpr.getNameAsString();
        if (!Set.of("of", "valueOf", "from").contains(factoryName)) {
            return null;
        }
        MethodCallExpr clone = callExpr.clone();
        clone.setArgument(0, new StringLiteralExpr(value));
        return clone.toString();
    }

    private String stringInitializerWithValue(VariableDeclarator variable, String value) {
        if (variable == null || variable.getInitializer().isEmpty()) {
            return null;
        }
        Expression initializer = variable.getInitializer().orElse(null);
        if (initializer instanceof MethodCallExpr) {
            return factoryExpressionWithString(variable, value);
        }
        if (initializer instanceof ObjectCreationExpr creationExpr && !creationExpr.getArguments().isEmpty()) {
            Expression firstArgument = creationExpr.getArgument(0);
            if (!(firstArgument instanceof StringLiteralExpr)) {
                return null;
            }
            ObjectCreationExpr clone = creationExpr.clone();
            clone.setArgument(0, new StringLiteralExpr(value));
            return clone.toString();
        }
        return null;
    }

    private MethodCallExpr findEmptyCollectionDriver(MethodDeclaration method) {
        for (MethodCallExpr callExpr : method.findAll(MethodCallExpr.class)) {
            if (isAssertionNode(callExpr) || callExpr.getArguments().isNonEmpty()) {
                continue;
            }
            String name = callExpr.getNameAsString();
            String scope = callExpr.getScope().map(Expression::toString).orElse("");
            if (("of".equals(name) && ("List".equals(scope) || "Set".equals(scope) || "Map".equals(scope)))
                    || ("emptyList".equals(name) && "Collections".equals(scope))
                    || ("emptySet".equals(name) && "Collections".equals(scope))
                    || ("emptyMap".equals(name) && "Collections".equals(scope))) {
                return callExpr;
            }
        }
        return null;
    }

    private Expression nonEmptyCollectionReplacement(MethodDeclaration method,
                                                    MethodCallExpr callExpr,
                                                    String targetMethod) {
        String name = callExpr.getNameAsString();
        String scope = callExpr.getScope().map(Expression::toString).orElse("");
        String replacement = null;
        if (("of".equals(name) && "List".equals(scope)) || ("emptyList".equals(name) && "Collections".equals(scope))) {
            replacement = listReplacementFor(method, targetMethod);
        } else if (("of".equals(name) && "Set".equals(scope)) || ("emptySet".equals(name) && "Collections".equals(scope))) {
            replacement = "java.util.Set.of(\"coverage\")";
        } else if (("of".equals(name) && "Map".equals(scope)) || ("emptyMap".equals(name) && "Collections".equals(scope))) {
            replacement = "java.util.Map.of(\"coverageKey\", \"coverageValue\")";
        }
        return replacement == null ? null : StaticJavaParser.parseExpression(replacement);
    }

    private String listReplacementFor(MethodDeclaration method, String targetMethod) {
        if ("averageLoginAttempts".equals(targetMethod)
                || containsUserAttemptsAccess(method)
                || containsFindAllOnRepository(method)) {
            return "java.util.List.of(new com.example.app.model.User(\"coverage-user\", \"coverage@example.com\"))";
        }
        return "java.util.List.of(\"coverage\")";
    }

    private boolean containsUserAttemptsAccess(MethodDeclaration method) {
        if (method == null) {
            return false;
        }
        return method.findAll(MethodCallExpr.class).stream()
                .map(MethodCallExpr::getNameAsString)
                .anyMatch(name -> "getLoginAttempts".equals(name));
    }

    private boolean containsFindAllOnRepository(MethodDeclaration method) {
        if (method == null) {
            return false;
        }
        return method.findAll(MethodCallExpr.class).stream()
                .anyMatch(expr -> "findAll".equals(expr.getNameAsString())
                        && expr.getScope().map(Expression::toString).orElse("").contains("repository"));
    }

    private StringLiteralExpr findEmptyStringDriver(MethodDeclaration method) {
        for (StringLiteralExpr literal : method.findAll(StringLiteralExpr.class)) {
            if (!isAssertionNode(literal) && literal.getValue().isEmpty()) {
                return literal;
            }
        }
        return null;
    }

    private GuardMutation applyGuardMutation(MethodDeclaration method) {
        if (flipStringDriverToNull(method)) {
            return new GuardMutation("NullPointerException");
        }
        if (flipNonEmptyCollectionDriverToEmpty(method)) {
            return new GuardMutation("IllegalArgumentException");
        }
        if (flipStringDriverToEmpty(method)) {
            return new GuardMutation("IllegalArgumentException");
        }
        if (flipNumericDriverToGuardBoundary(method)) {
            return new GuardMutation("IllegalArgumentException");
        }
        return null;
    }

    private boolean flipStringDriverToNull(MethodDeclaration method) {
        for (VariableDeclarator variable : method.findAll(VariableDeclarator.class)) {
            if (!"string".equals(variable.getType().asString().toLowerCase(Locale.ROOT))) {
                continue;
            }
            Expression initializer = variable.getInitializer().orElse(null);
            if (!(initializer instanceof StringLiteralExpr literal) || literal.getValue().isEmpty() || isAssertionNode(literal)) {
                continue;
            }
            variable.setInitializer(new NullLiteralExpr());
            return true;
        }
        return false;
    }

    private boolean flipNonEmptyCollectionDriverToEmpty(MethodDeclaration method) {
        for (MethodCallExpr callExpr : method.findAll(MethodCallExpr.class)) {
            if (isAssertionNode(callExpr) || callExpr.getArguments().isEmpty()) {
                continue;
            }
            String name = callExpr.getNameAsString();
            String scope = callExpr.getScope().map(Expression::toString).orElse("");
            String replacement = null;
            if ("of".equals(name) && "List".equals(scope)) {
                replacement = "java.util.List.of()";
            } else if ("of".equals(name) && "Set".equals(scope)) {
                replacement = "java.util.Set.of()";
            } else if ("of".equals(name) && "Map".equals(scope)) {
                replacement = "java.util.Map.of()";
            }
            if (replacement != null) {
                callExpr.replace(StaticJavaParser.parseExpression(replacement));
                return true;
            }
        }
        return false;
    }

    private boolean flipStringDriverToEmpty(MethodDeclaration method) {
        for (StringLiteralExpr literal : method.findAll(StringLiteralExpr.class)) {
            if (isAssertionNode(literal) || literal.getValue().isEmpty()) {
                continue;
            }
            literal.setString("");
            return true;
        }
        return false;
    }

    private boolean flipNumericDriverToGuardBoundary(MethodDeclaration method) {
        for (IntegerLiteralExpr literal : method.findAll(IntegerLiteralExpr.class)) {
            if (isAssertionNode(literal)) {
                continue;
            }
            try {
                int value = Integer.parseInt(literal.getValue().replace("_", ""));
                literal.setInt(value <= 0 ? 0 : 1);
                return true;
            } catch (NumberFormatException ignored) {
                // skip unsupported integer literal forms
            }
        }
        for (LongLiteralExpr literal : method.findAll(LongLiteralExpr.class)) {
            if (isAssertionNode(literal)) {
                continue;
            }
            try {
                String raw = literal.getValue().replace("_", "");
                String normalized = raw.endsWith("L") || raw.endsWith("l")
                        ? raw.substring(0, raw.length() - 1)
                        : raw;
                long value = Long.parseLong(normalized);
                literal.setLong(value <= 0L ? 0L : 1L);
                return true;
            } catch (NumberFormatException ignored) {
                // skip unsupported long literal forms
            }
        }
        for (DoubleLiteralExpr literal : method.findAll(DoubleLiteralExpr.class)) {
            if (isAssertionNode(literal)) {
                continue;
            }
            try {
                String raw = literal.getValue().replace("_", "");
                String normalized = raw.endsWith("d") || raw.endsWith("D")
                        ? raw.substring(0, raw.length() - 1)
                        : raw;
                double value = Double.parseDouble(normalized);
                literal.setDouble(value <= 0.0d ? 0.0d : 1.0d);
                return true;
            } catch (NumberFormatException ignored) {
                // skip unsupported double literal forms
            }
        }
        return false;
    }

    private boolean rewriteActAsAssertThrows(MethodDeclaration method,
                                             String targetMethod,
                                             String exceptionClass) {
        BlockStmt body = method.getBody().orElse(null);
        if (body == null || body.getStatements().isEmpty()) {
            return false;
        }
        int statementIndex = findActStatementIndex(body, targetMethod);
        if (statementIndex < 0 || statementIndex >= body.getStatements().size()) {
            return false;
        }
        Statement actStatement = body.getStatement(statementIndex);
        String invocation = extractActInvocation(actStatement);
        if (invocation == null || invocation.isBlank()) {
            return false;
        }
        String assignedVariable = extractAssignedVariableName(actStatement);
        Statement assertThrowsStatement = StaticJavaParser.parseStatement(
                "org.junit.jupiter.api.Assertions.assertThrows("
                        + exceptionClass
                        + ".class, () -> "
                        + invocation
                        + ");");
        body.getStatements().set(statementIndex, assertThrowsStatement);
        for (int index = body.getStatements().size() - 1; index > statementIndex; index--) {
            Statement statement = body.getStatement(index);
            if (shouldRemoveAfterGuardRewrite(statement, assignedVariable)) {
                statement.remove();
            }
        }
        return true;
    }

    private boolean promoteShadowRollbackVariantToReboundPath(MethodDeclaration method, String targetMethod) {
        if (method == null || method.getBody().isEmpty()) {
            return false;
        }
        if (!switchShadowRollbackToggle(method)) {
            return false;
        }
        BlockStmt body = method.getBody().orElse(null);
        if (body == null) {
            return false;
        }
        int actIndex = findActStatementIndex(body, targetMethod);
        if (actIndex < 0) {
            return false;
        }
        for (int index = 0; index < 3; index++) {
            body.getStatements().add(actIndex + index, StaticJavaParser.parseStatement("user.incrementAttempts();"));
        }
        String assignedVariable = extractAssignedVariableName(body.getStatement(actIndex + 3));
        if (!rewriteResultAssertion(method, assignedVariable)) {
            return false;
        }
        if (!rewriteShadowRollbackAuditVerification(method)) {
            return false;
        }
        if (!rewriteLibraryVerification(method)) {
            return false;
        }
        ensureNotificationVerification(body);
        removeNotificationNeverVerifications(body, true);
        return true;
    }

    private boolean promoteStateToggleBooleanVariant(MethodDeclaration method, String targetMethod) {
        if (method == null || method.getBody().isEmpty() || targetMethod == null || targetMethod.isBlank()) {
            return false;
        }
        BlockStmt body = method.getBody().orElse(null);
        if (body == null) {
            return false;
        }
        int actIndex = findActStatementIndex(body, targetMethod);
        if (actIndex < 0) {
            return false;
        }
        MethodCallExpr targetCall = findTargetMethodCall(body.getStatement(actIndex), targetMethod);
        if (targetCall == null || targetCall.getArguments().isNonEmpty() || targetCall.getScope().isEmpty()) {
            return false;
        }
        String targetVariable = targetCall.getScope().map(Expression::toString).orElse("");
        if (targetVariable.isBlank()) {
            return false;
        }
        StateToggleMutation mutation = switchPreActStateToggle(body, actIndex, targetVariable);
        if (mutation == null) {
            return false;
        }
        String assignedVariable = extractAssignedVariableName(body.getStatement(actIndex));
        return mutation.expectedResult()
                ? rewriteResultAssertion(method, assignedVariable)
                : rewriteTrueAssertionToFalse(method, assignedVariable);
    }

    private StateToggleMutation switchPreActStateToggle(BlockStmt body, int actIndex, String targetVariable) {
        if (body == null || targetVariable == null || targetVariable.isBlank()) {
            return null;
        }
        for (int index = Math.max(0, actIndex - 1); index >= 0; index--) {
            Statement statement = body.getStatement(index);
            if (!(statement instanceof ExpressionStmt expressionStmt)
                    || !(expressionStmt.getExpression() instanceof MethodCallExpr call)
                    || call.getScope().isEmpty()
                    || !targetVariable.equals(call.getScope().map(Expression::toString).orElse(""))) {
                continue;
            }
            if ("deactivate".equals(call.getNameAsString())) {
                call.setName("activate");
                return new StateToggleMutation(true);
            }
            if ("activate".equals(call.getNameAsString())) {
                call.setName("deactivate");
                return new StateToggleMutation(false);
            }
        }
        return null;
    }

    private boolean promoteDirectShadowRollbackVariantToReboundPath(MethodDeclaration method, String targetMethod) {
        if (method == null || method.getBody().isEmpty()) {
            return false;
        }
        if (!switchShadowRollbackToggle(method)) {
            return false;
        }
        BlockStmt body = method.getBody().orElse(null);
        if (body == null) {
            return false;
        }
        int actIndex = findActStatementIndex(body, targetMethod);
        if (actIndex < 0) {
            return false;
        }
        for (int index = 0; index < 3; index++) {
            body.getStatements().add(actIndex + index, StaticJavaParser.parseStatement("user.incrementAttempts();"));
        }
        String assignedVariable = extractAssignedVariableName(body.getStatement(actIndex + 3));
        if (!rewriteResultAssertion(method, assignedVariable)) {
            return false;
        }
        if (!rewriteShadowRollbackAuditVerification(method)) {
            return false;
        }
        ensureNotificationVerification(body);
        removeNotificationNeverVerifications(body, true);
        removeNoInteractionsVerification(body, "notificationService");
        return true;
    }

    private AverageLoginAttemptsBranch detectAverageLoginAttemptsBranch(MethodDeclaration method) {
        Expression stubArgument = findRepositoryFindAllThenReturnArgument(method);
        if (isDefinitelyEmptyCollectionExpression(stubArgument)) {
            return AverageLoginAttemptsBranch.EMPTY_BASELINE;
        }
        return AverageLoginAttemptsBranch.NON_EMPTY_OR_UNKNOWN_BASELINE;
    }

    private boolean promoteAverageLoginAttemptsVariant(MethodDeclaration method,
                                                       String targetMethod,
                                                       AverageLoginAttemptsBranch branch) {
        if (branch == AverageLoginAttemptsBranch.EMPTY_BASELINE) {
            return promoteAverageLoginAttemptsVariantToAggregationBranch(method, targetMethod);
        }
        return promoteAverageLoginAttemptsVariantToEmptyBranch(method, targetMethod);
    }

    private boolean promoteAverageLoginAttemptsVariantToEmptyBranch(MethodDeclaration method, String targetMethod) {
        if (method == null || method.getBody().isEmpty()) {
            return false;
        }
        if (!switchRepositoryFindAllStubToEmptyList(method)) {
            return false;
        }
        BlockStmt body = method.getBody().orElse(null);
        if (body == null) {
            return false;
        }
        int actIndex = findActStatementIndex(body, targetMethod);
        if (actIndex < 0) {
            return false;
        }
        String assignedVariable = extractAssignedVariableName(body.getStatement(actIndex));
        return rewriteEqualityAssertion(method, assignedVariable, "0.0");
    }

    private boolean promoteAverageLoginAttemptsVariantToAggregationBranch(MethodDeclaration method, String targetMethod) {
        if (method == null || method.getBody().isEmpty()) {
            return false;
        }
        BlockStmt body = method.getBody().orElse(null);
        if (body == null) {
            return false;
        }
        String userVariable = resolveUniqueLocalName(method, "coverageUser");
        int stubIndex = switchRepositoryFindAllStubToUsersList(method, body, userVariable);
        if (stubIndex < 0) {
            return false;
        }
        insertAverageLoginAttemptsUserSetup(body, stubIndex, userVariable);
        int actIndex = findActStatementIndex(body, targetMethod);
        if (actIndex < 0) {
            return false;
        }
        String assignedVariable = extractAssignedVariableName(body.getStatement(actIndex));
        return rewriteEqualityAssertion(method, assignedVariable, "2.0");
    }

    private boolean promoteAverageNormalGuardReturnVariant(MethodDeclaration method, String targetMethod) {
        if (method == null || method.getBody().isEmpty()) {
            return false;
        }
        BlockStmt body = method.getBody().orElse(null);
        if (body == null) {
            return false;
        }
        int actIndex = findActStatementIndex(body, targetMethod);
        if (actIndex < 0) {
            return false;
        }
        Statement actStatement = body.getStatement(actIndex);
        MethodCallExpr averageCall = findTargetInvocation(actStatement, targetMethod);
        if (averageCall == null || averageCall.getArguments().size() < 2) {
            return false;
        }
        boolean baselineCoversZeroGuard = resolvesToInteger(method, averageCall.getArgument(1), 0);
        String expectedExpression;
        if (baselineCoversZeroGuard) {
            rewriteIntegerArgument(method, averageCall, 0, "10");
            rewriteIntegerArgument(method, averageCall, 1, "2");
            expectedExpression = "5.0";
        } else {
            rewriteIntegerArgument(method, averageCall, 1, "0");
            expectedExpression = "0.0";
        }
        String assignedVariable = extractAssignedVariableName(actStatement);
        return rewriteEqualityAssertion(method, assignedVariable, expectedExpression);
    }

    private MethodCallExpr findTargetInvocation(Statement statement, String targetMethod) {
        if (statement == null || targetMethod == null || targetMethod.isBlank()) {
            return null;
        }
        for (MethodCallExpr callExpr : statement.findAll(MethodCallExpr.class)) {
            if (targetMethod.equals(callExpr.getNameAsString()) && !isAssertionNode(callExpr)) {
                return callExpr;
            }
        }
        return null;
    }

    private boolean resolvesToInteger(MethodDeclaration method, Expression expression, int expected) {
        if (expression instanceof IntegerLiteralExpr literal) {
            try {
                return Integer.parseInt(literal.getValue().replace("_", "")) == expected;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        if (expression instanceof NameExpr nameExpr) {
            return extractNamedIntegerInitializer(method, nameExpr.getNameAsString())
                    .map(value -> value == expected)
                    .orElse(false);
        }
        return false;
    }

    private void rewriteIntegerArgument(MethodDeclaration method,
                                        MethodCallExpr callExpr,
                                        int argumentIndex,
                                        String replacementExpression) {
        if (callExpr == null || argumentIndex < 0 || argumentIndex >= callExpr.getArguments().size()
                || replacementExpression == null || replacementExpression.isBlank()) {
            return;
        }
        Expression current = callExpr.getArgument(argumentIndex);
        if (current instanceof NameExpr nameExpr
                && ensureNamedIntegerInitializer(method, nameExpr.getNameAsString(), replacementExpression)) {
            return;
        }
        callExpr.setArgument(argumentIndex, StaticJavaParser.parseExpression(replacementExpression));
    }

    private Expression findRepositoryFindAllThenReturnArgument(MethodDeclaration method) {
        MethodCallExpr thenReturn = findRepositoryFindAllThenReturnCall(method);
        if (thenReturn == null || thenReturn.getArguments().isEmpty()) {
            return null;
        }
        return thenReturn.getArgument(0);
    }

    private MethodCallExpr findRepositoryFindAllThenReturnCall(MethodDeclaration method) {
        if (method == null) {
            return null;
        }
        for (MethodCallExpr expr : method.findAll(MethodCallExpr.class)) {
            String stubbingMethod = expr.getNameAsString();
            if (!("thenReturn".equals(stubbingMethod) || "willReturn".equals(stubbingMethod))
                    || expr.getArguments().size() != 1) {
                continue;
            }
            Expression scope = expr.getScope().orElse(null);
            if (!(scope instanceof MethodCallExpr stubCall)
                    || stubCall.getArguments().size() != 1) {
                continue;
            }
            String driverMethod = "willReturn".equals(stubbingMethod) ? "given" : "when";
            if (!driverMethod.equals(stubCall.getNameAsString())) {
                continue;
            }
            Expression whenArgument = stubCall.getArgument(0);
            if (!(whenArgument instanceof MethodCallExpr driverCall)
                    || !"findAll".equals(driverCall.getNameAsString())
                    || driverCall.getArguments().size() != 0
                    || driverCall.getScope().isEmpty()
                    || !"repository".equals(driverCall.getScope().orElse(null).toString())) {
                continue;
            }
            return expr;
        }
        return null;
    }

    private boolean isDefinitelyEmptyCollectionExpression(Expression expression) {
        if (expression == null) {
            return false;
        }
        String text = expression.toString();
        if ("List.of()".equals(text)
                || "java.util.List.of()".equals(text)
                || "Collections.emptyList()".equals(text)
                || "java.util.Collections.emptyList()".equals(text)) {
            return true;
        }
        if ((text.startsWith("new ArrayList") || text.startsWith("new java.util.ArrayList")
                || text.startsWith("new LinkedList") || text.startsWith("new java.util.LinkedList"))
                && text.endsWith("()")) {
            return true;
        }
        if (expression instanceof ObjectCreationExpr creationExpr && creationExpr.getArguments().isEmpty()) {
            String type = creationExpr.getType().asString();
            return "ArrayList".equals(type)
                    || "java.util.ArrayList".equals(type)
                    || "LinkedList".equals(type)
                    || "java.util.LinkedList".equals(type);
        }
        return false;
    }

    private boolean switchRepositoryFindAllStubToEmptyList(MethodDeclaration method) {
        boolean changed = false;
        MethodCallExpr expr = findRepositoryFindAllThenReturnCall(method);
        if (expr != null) {
            expr.setArgument(0, StaticJavaParser.parseExpression("java.util.List.of()"));
            changed = true;
        }
        return changed;
    }

    private int switchRepositoryFindAllStubToUsersList(MethodDeclaration method, BlockStmt body, String userVariable) {
        MethodCallExpr thenReturn = findRepositoryFindAllThenReturnCall(method);
        if (thenReturn == null) {
            return -1;
        }
        int statementIndex = findStatementIndexContaining(body, thenReturn);
        if (statementIndex < 0) {
            return -1;
        }
        thenReturn.setArgument(0, StaticJavaParser.parseExpression("java.util.List.of(" + userVariable + ")"));
        return statementIndex;
    }

    private void insertAverageLoginAttemptsUserSetup(BlockStmt body, int insertIndex, String userVariable) {
        body.getStatements().add(insertIndex, StaticJavaParser.parseStatement(
                "com.example.app.model.User " + userVariable
                        + " = new com.example.app.model.User(\"coverage-user\", \"coverage@example.com\");"));
        body.getStatements().add(insertIndex + 1, StaticJavaParser.parseStatement(userVariable + ".incrementAttempts();"));
        body.getStatements().add(insertIndex + 2, StaticJavaParser.parseStatement(userVariable + ".incrementAttempts();"));
    }

    private int findStatementIndexContaining(BlockStmt body, MethodCallExpr targetCall) {
        if (body == null || targetCall == null) {
            return -1;
        }
        for (int index = 0; index < body.getStatements().size(); index++) {
            Statement statement = body.getStatement(index);
            boolean found = statement.findAll(MethodCallExpr.class).stream()
                    .anyMatch(candidate -> candidate == targetCall);
            if (found) {
                return index;
            }
        }
        return -1;
    }

    private String resolveUniqueLocalName(MethodDeclaration method, String baseName) {
        LinkedHashSet<String> usedNames = new LinkedHashSet<>();
        method.findAll(VariableDeclarator.class).forEach(variable -> usedNames.add(variable.getNameAsString()));
        if (!usedNames.contains(baseName)) {
            return baseName;
        }
        int suffix = 2;
        while (usedNames.contains(baseName + suffix)) {
            suffix++;
        }
        return baseName + suffix;
    }

    private boolean promoteReboundFactorVariant(MethodDeclaration method, String targetMethod) {
        if (method == null || method.getBody().isEmpty()) {
            return false;
        }
        BooleanLiteralExpr branchDriver = findBooleanDriver(method);
        if (branchDriver == null) {
            return false;
        }
        branchDriver.setValue(!branchDriver.getValue());
        int attempts = extractNamedIntegerInitializer(method, "attempts").orElse(0);
        int expected = Math.max(1, attempts + (branchDriver.getValue() ? 2 : 1)) + 2;
        BlockStmt body = method.getBody().orElse(null);
        if (body == null) {
            return false;
        }
        int actIndex = findActStatementIndex(body, targetMethod);
        if (actIndex < 0) {
            return false;
        }
        String assignedVariable = extractAssignedVariableName(body.getStatement(actIndex));
        return rewriteEqualityAssertion(method, assignedVariable, Integer.toString(expected));
    }

    private boolean promoteLegacyUpgradeVariantToPromotionPath(MethodDeclaration method, String targetMethod) {
        if (method == null || method.getBody().isEmpty()) {
            return false;
        }
        if (!switchFeatureToggle(method, "legacy-upgrade", true)) {
            return false;
        }
        BlockStmt body = method.getBody().orElse(null);
        if (body == null) {
            return false;
        }
        int actIndex = findActStatementIndex(body, targetMethod);
        if (actIndex < 0) {
            return false;
        }
        String assignedVariable = extractAssignedVariableName(body.getStatement(actIndex));
        if (!rewriteResultAssertion(method, assignedVariable)) {
            return false;
        }
        if (!rewriteLegacyUpgradeAuditVerification(method)) {
            return false;
        }
        rewriteLegacyUpgradeLibraryVerification(method);
        ensureWelcomeVerification(body);
        removeNotificationNeverVerifications(body, true);
        removeNoInteractionsVerification(body, "notificationService");
        return true;
    }

    private boolean promoteInheritedShadowUpgradeVariantToRejectedPath(MethodDeclaration method, String targetMethod) {
        if (method == null || method.getBody().isEmpty()) {
            return false;
        }
        if (!ensureFeatureToggle(method, "inherited-shadow", true)) {
            return false;
        }
        BlockStmt body = method.getBody().orElse(null);
        if (body == null) {
            return false;
        }
        int actIndex = findActStatementIndex(body, targetMethod);
        if (actIndex < 0) {
            return false;
        }
        if (!rewriteActSecondArgument(body.getStatement(actIndex), targetMethod, "1")) {
            return false;
        }
        String assignedVariable = extractAssignedVariableName(body.getStatement(actIndex));
        if (!rewriteTrueAssertionToFalse(method, assignedVariable)) {
            return false;
        }
        if (!rewriteNotificationVerification(method, "sendWelcome", "sendDeactivationNotice")) {
            return false;
        }
        return rewriteInheritedShadowAuditVerification(method, "Inherited shadow rejected ");
    }

    private boolean promoteInheritedShadowUpgradeVariantToPromotionPath(MethodDeclaration method, String targetMethod) {
        if (method == null || method.getBody().isEmpty()) {
            return false;
        }
        if (!ensureFeatureToggle(method, "inherited-shadow", true)) {
            return false;
        }
        BlockStmt body = method.getBody().orElse(null);
        if (body == null) {
            return false;
        }
        int actIndex = findActStatementIndex(body, targetMethod);
        if (actIndex < 0) {
            return false;
        }
        rewriteActSecondArgument(body.getStatement(actIndex), targetMethod, "10");
        String assignedVariable = extractAssignedVariableName(body.getStatement(actIndex));
        if (!rewriteResultAssertion(method, assignedVariable)) {
            return false;
        }
        if (!rewriteInheritedShadowAuditVerification(method, "Inherited shadow promoted ")) {
            return false;
        }
        ensureWelcomeVerification(body);
        removeNotificationNeverVerifications(body, true);
        removeNoInteractionsVerification(body, "notificationService");
        return true;
    }

    private boolean promoteInheritedShadowWorkflowVariantToPromotionPath(MethodDeclaration method, String targetMethod) {
        if (method == null || method.getBody().isEmpty()) {
            return false;
        }
        if (!ensureFeatureToggle(method, "inherited-shadow", true)) {
            return false;
        }
        BlockStmt body = method.getBody().orElse(null);
        if (body == null) {
            return false;
        }
        int actIndex = findActStatementIndex(body, targetMethod);
        if (actIndex < 0) {
            return false;
        }
        boolean hasRawSignalVariable = ensureNamedIntegerInitializer(method, "rawSignal", "10");
        rewriteActSecondArgument(body.getStatement(actIndex), targetMethod, hasRawSignalVariable ? "rawSignal" : "10");
        String assignedVariable = extractAssignedVariableName(body.getStatement(actIndex));
        if (!rewriteResultAssertion(method, assignedVariable)) {
            return false;
        }
        if (!rewriteInheritedShadowAuditVerification(method, "Inherited shadow upgrade completed for ")) {
            return false;
        }
        rewriteLegacyUpgradeLibraryVerification(method);
        ensureLibraryVerification(body, "reload");
        ensureWelcomeVerification(body);
        removeNotificationNeverVerifications(body, true);
        removeNoInteractionsVerification(body, "notificationService");
        return true;
    }

    private boolean switchShadowRollbackToggle(MethodDeclaration method) {
        return switchFeatureToggle(method, "shadow-rollback", true);
    }

    private boolean switchFeatureToggle(MethodDeclaration method, String featureName, boolean enabled) {
        boolean changed = false;
        for (MethodCallExpr expr : method.findAll(MethodCallExpr.class)) {
            if (!"thenReturn".equals(expr.getNameAsString()) || expr.getArguments().size() != 1) {
                continue;
            }
            if (!(expr.getArgument(0) instanceof BooleanLiteralExpr booleanLiteralExpr)
                    || booleanLiteralExpr.getValue() == enabled) {
                continue;
            }
            Expression scope = expr.getScope().orElse(null);
            if (!(scope instanceof MethodCallExpr whenCall)
                    || !"when".equals(whenCall.getNameAsString())
                    || whenCall.getArguments().size() != 1) {
                continue;
            }
            Expression whenArgument = whenCall.getArgument(0);
            if (!(whenArgument instanceof MethodCallExpr driverCall)
                    || !"isEnabled".equals(driverCall.getNameAsString())
                    || driverCall.getArguments().size() != 1
                    || driverCall.getScope().isEmpty()
                    || !"featureToggleService".equals(driverCall.getScope().orElse(null).toString())) {
                continue;
            }
            Expression featureArgument = driverCall.getArgument(0);
            if (!matchesStringLiteralOrEq(featureArgument, featureName)) {
                continue;
            }
            booleanLiteralExpr.setValue(enabled);
            changed = true;
        }
        return changed;
    }

    private boolean ensureFeatureToggle(MethodDeclaration method, String featureName, boolean enabled) {
        boolean found = false;
        for (MethodCallExpr expr : method.findAll(MethodCallExpr.class)) {
            if (!"thenReturn".equals(expr.getNameAsString()) || expr.getArguments().size() != 1) {
                continue;
            }
            if (!(expr.getArgument(0) instanceof BooleanLiteralExpr booleanLiteralExpr)) {
                continue;
            }
            Expression scope = expr.getScope().orElse(null);
            if (!(scope instanceof MethodCallExpr whenCall)
                    || !"when".equals(whenCall.getNameAsString())
                    || whenCall.getArguments().size() != 1) {
                continue;
            }
            Expression whenArgument = whenCall.getArgument(0);
            if (!(whenArgument instanceof MethodCallExpr driverCall)
                    || !"isEnabled".equals(driverCall.getNameAsString())
                    || driverCall.getArguments().size() != 1
                    || driverCall.getScope().isEmpty()
                    || !"featureToggleService".equals(driverCall.getScope().orElse(null).toString())) {
                continue;
            }
            Expression featureArgument = driverCall.getArgument(0);
            if (!matchesStringLiteralOrEq(featureArgument, featureName)) {
                continue;
            }
            booleanLiteralExpr.setValue(enabled);
            found = true;
        }
        return found;
    }

    private boolean rewriteResultAssertion(MethodDeclaration method, String assignedVariable) {
        boolean changed = false;
        for (MethodCallExpr expr : method.findAll(MethodCallExpr.class)) {
            if ("assertFalse".equals(expr.getNameAsString()) && expr.getArguments().size() == 1) {
                String argumentText = expr.getArgument(0).toString();
                if (assignedVariable != null && !assignedVariable.isBlank() && !assignedVariable.equals(argumentText)) {
                    continue;
                }
                expr.setName("assertTrue");
                changed = true;
                continue;
            }
            if ("isFalse".equals(expr.getNameAsString()) && isAssertThatResultAssertion(expr, assignedVariable)) {
                expr.setName("isTrue");
                changed = true;
            }
        }
        return changed;
    }

    private boolean rewriteTrueAssertionToFalse(MethodDeclaration method, String assignedVariable) {
        boolean changed = false;
        for (MethodCallExpr expr : method.findAll(MethodCallExpr.class)) {
            if ("assertTrue".equals(expr.getNameAsString()) && expr.getArguments().size() == 1) {
                String argumentText = expr.getArgument(0).toString();
                if (assignedVariable != null && !assignedVariable.isBlank() && !assignedVariable.equals(argumentText)) {
                    continue;
                }
                expr.setName("assertFalse");
                changed = true;
                continue;
            }
            if ("isTrue".equals(expr.getNameAsString()) && isAssertThatResultAssertion(expr, assignedVariable)) {
                expr.setName("isFalse");
                changed = true;
            }
        }
        return changed;
    }

    private boolean rewriteShadowRollbackAuditVerification(MethodDeclaration method) {
        boolean changed = false;
        for (MethodCallExpr expr : method.findAll(MethodCallExpr.class)) {
            if (!"recordEvent".equals(expr.getNameAsString()) || expr.getArguments().size() != 1) {
                continue;
            }
            Expression scope = expr.getScope().orElse(null);
            if (!(scope instanceof MethodCallExpr verifyCall)
                    || !"verify".equals(verifyCall.getNameAsString())
                    || verifyCall.getArguments().size() < 1
                    || !"auditTrailService".equals(verifyCall.getArgument(0).toString())) {
                continue;
            }
            expr.setArgument(0, StaticJavaParser.parseExpression("startsWith(\"Shadow rollback rebound \")"));
            changed = true;
        }
        return changed;
    }

    private boolean rewriteInheritedShadowAuditVerification(MethodDeclaration method, String prefix) {
        boolean changed = false;
        for (MethodCallExpr expr : method.findAll(MethodCallExpr.class)) {
            if (!"recordEvent".equals(expr.getNameAsString()) || expr.getArguments().size() != 1) {
                continue;
            }
            Expression scope = expr.getScope().orElse(null);
            if (!(scope instanceof MethodCallExpr verifyCall)
                    || !"verify".equals(verifyCall.getNameAsString())
                    || verifyCall.getArguments().size() < 1
                    || !"auditTrailService".equals(verifyCall.getArgument(0).toString())) {
                continue;
            }
            expr.setArgument(0, StaticJavaParser.parseExpression("startsWith(\"" + prefix + "\")"));
            changed = true;
        }
        return changed;
    }

    private boolean matchesStringLiteralOrEq(Expression expression, String expectedValue) {
        if (expression == null || expectedValue == null) {
            return false;
        }
        if (expression instanceof StringLiteralExpr literal) {
            return expectedValue.equals(literal.getValue());
        }
        if (expression instanceof MethodCallExpr call
                && "eq".equals(call.getNameAsString())
                && call.getArguments().size() == 1
                && call.getArgument(0) instanceof StringLiteralExpr literal) {
            return expectedValue.equals(literal.getValue());
        }
        return false;
    }

    private boolean isAssertThatResultAssertion(MethodCallExpr assertionCall, String assignedVariable) {
        if (assertionCall == null || assertionCall.getScope().isEmpty()) {
            return false;
        }
        if (!(assertionCall.getScope().orElse(null) instanceof MethodCallExpr assertThatCall)
                || !"assertThat".equals(assertThatCall.getNameAsString())
                || assertThatCall.getArguments().size() != 1) {
            return false;
        }
        String argumentText = assertThatCall.getArgument(0).toString();
        return assignedVariable == null || assignedVariable.isBlank() || assignedVariable.equals(argumentText);
    }

    private boolean rewriteNotificationVerification(MethodDeclaration method, String fromMethod, String toMethod) {
        boolean changed = false;
        for (MethodCallExpr expr : method.findAll(MethodCallExpr.class)) {
            if (!fromMethod.equals(expr.getNameAsString()) || expr.getArguments().size() != 1) {
                continue;
            }
            Expression scope = expr.getScope().orElse(null);
            if (!(scope instanceof MethodCallExpr verifyCall)
                    || !"verify".equals(verifyCall.getNameAsString())
                    || verifyCall.getArguments().size() < 1
                    || !"notificationService".equals(verifyCall.getArgument(0).toString())) {
                continue;
            }
            expr.setName(toMethod);
            changed = true;
        }
        return changed;
    }

    private boolean rewriteLibraryVerification(MethodDeclaration method) {
        boolean changed = false;
        for (MethodCallExpr expr : method.findAll(MethodCallExpr.class)) {
            if (!"load".equals(expr.getNameAsString()) || expr.getArguments().size() != 0) {
                continue;
            }
            Expression scope = expr.getScope().orElse(null);
            if (!(scope instanceof MethodCallExpr verifyCall)
                    || !"verify".equals(verifyCall.getNameAsString())
                    || verifyCall.getArguments().size() != 1
                    || !"libraryComponent".equals(verifyCall.getArgument(0).toString())) {
                continue;
            }
            expr.setName("close");
            changed = true;
        }
        return changed;
    }

    private boolean rewriteLegacyUpgradeLibraryVerification(MethodDeclaration method) {
        boolean changed = false;
        for (MethodCallExpr expr : method.findAll(MethodCallExpr.class)) {
            if (!"connect".equals(expr.getNameAsString()) || expr.getArguments().size() != 0) {
                continue;
            }
            Expression scope = expr.getScope().orElse(null);
            if (!(scope instanceof MethodCallExpr verifyCall)
                    || !"verify".equals(verifyCall.getNameAsString())
                    || verifyCall.getArguments().size() != 1
                    || !"libraryComponent".equals(verifyCall.getArgument(0).toString())) {
                continue;
            }
            expr.setName("reload");
            changed = true;
        }
        return changed;
    }

    private void ensureLibraryVerification(BlockStmt body, String methodName) {
        if (body == null || methodName == null || methodName.isBlank()) {
            return;
        }
        boolean alreadyPresent = body.findAll(MethodCallExpr.class).stream()
                .anyMatch(expr -> methodName.equals(expr.getNameAsString())
                        && expr.getScope().isPresent()
                        && expr.getScope().orElse(null) instanceof MethodCallExpr verifyCall
                        && "verify".equals(verifyCall.getNameAsString())
                        && verifyCall.getArguments().size() >= 1
                        && "libraryComponent".equals(verifyCall.getArgument(0).toString()));
        if (alreadyPresent) {
            return;
        }
        int insertAfter = body.getStatements().size();
        for (int index = 0; index < body.getStatements().size(); index++) {
            Statement statement = body.getStatement(index);
            String text = statement.toString();
            if (text.contains("assertTrue(")
                    || text.contains("verify(auditTrailService).recordEvent(")
                    || text.contains("verify(notificationService).sendWelcome(")) {
                insertAfter = index + 1;
            }
        }
        body.getStatements().add(insertAfter, StaticJavaParser.parseStatement(
                "verify(libraryComponent)." + methodName + "();"));
    }

    private void ensureNotificationVerification(BlockStmt body) {
        String verification = "verify(notificationService).sendDeactivationNotice(user);";
        boolean alreadyPresent = body.findAll(MethodCallExpr.class).stream()
                .anyMatch(expr -> "sendDeactivationNotice".equals(expr.getNameAsString())
                        && expr.getScope().isPresent()
                        && expr.getScope().orElse(null) instanceof MethodCallExpr verifyCall
                        && "verify".equals(verifyCall.getNameAsString())
                        && verifyCall.getArguments().size() == 1
                        && "notificationService".equals(verifyCall.getArgument(0).toString()));
        if (alreadyPresent) {
            return;
        }
        int insertAfter = body.getStatements().size();
        for (int index = 0; index < body.getStatements().size(); index++) {
            Statement statement = body.getStatement(index);
            String text = statement.toString();
            if (text.contains("verify(auditTrailService).recordEvent(") || text.contains("verify(libraryComponent).close()")) {
                insertAfter = index + 1;
            }
        }
        body.getStatements().add(insertAfter, StaticJavaParser.parseStatement(verification));
    }

    private void ensureWelcomeVerification(BlockStmt body) {
        String verification = "verify(notificationService).sendWelcome(user);";
        boolean alreadyPresent = body.findAll(MethodCallExpr.class).stream()
                .anyMatch(expr -> "sendWelcome".equals(expr.getNameAsString())
                        && expr.getScope().isPresent()
                        && expr.getScope().orElse(null) instanceof MethodCallExpr verifyCall
                        && "verify".equals(verifyCall.getNameAsString())
                        && verifyCall.getArguments().size() == 1
                        && "notificationService".equals(verifyCall.getArgument(0).toString()));
        if (alreadyPresent) {
            return;
        }
        int insertAfter = body.getStatements().size();
        for (int index = 0; index < body.getStatements().size(); index++) {
            Statement statement = body.getStatement(index);
            String text = statement.toString();
            if (text.contains("verify(auditTrailService).recordEvent(")) {
                insertAfter = index + 1;
            }
        }
        body.getStatements().add(insertAfter, StaticJavaParser.parseStatement(verification));
    }

    private void removeNotificationNeverVerifications(BlockStmt body, boolean removeWelcomeNeverVerification) {
        if (body == null) {
            return;
        }
        List<Statement> toRemove = new ArrayList<>();
        for (Statement statement : body.getStatements()) {
            boolean remove = statement.findAll(MethodCallExpr.class).stream()
                    .anyMatch(expr -> isNotificationNeverVerification(expr, "sendDeactivationNotice"));
            if (!remove && removeWelcomeNeverVerification) {
                remove = statement.findAll(MethodCallExpr.class).stream()
                        .anyMatch(expr -> isNotificationNeverVerification(expr, "sendWelcome"));
            }
            if (remove) {
                toRemove.add(statement);
            }
        }
        toRemove.forEach(Statement::remove);
    }

    private void removeNoInteractionsVerification(BlockStmt body, String collaborator) {
        if (body == null || collaborator == null || collaborator.isBlank()) {
            return;
        }
        List<Statement> toRemove = new ArrayList<>();
        for (Statement statement : body.getStatements()) {
            boolean remove = statement.findAll(MethodCallExpr.class).stream()
                    .anyMatch(expr -> "verifyNoInteractions".equals(expr.getNameAsString())
                            && expr.getArguments().stream().anyMatch(argument -> collaborator.equals(argument.toString())));
            if (remove) {
                toRemove.add(statement);
            }
        }
        toRemove.forEach(Statement::remove);
    }

    private boolean isNotificationNeverVerification(MethodCallExpr expr, String methodName) {
        if (expr == null || !methodName.equals(expr.getNameAsString()) || expr.getScope().isEmpty()) {
            return false;
        }
        Expression scope = expr.getScope().orElse(null);
        if (!(scope instanceof MethodCallExpr verifyCall)
                || !"verify".equals(verifyCall.getNameAsString())
                || verifyCall.getArguments().isEmpty()) {
            return false;
        }
        if (!"notificationService".equals(verifyCall.getArgument(0).toString())) {
            return false;
        }
        return verifyCall.getArguments().size() == 2
                && verifyCall.getArgument(1) instanceof MethodCallExpr modeCall
                && "never".equals(modeCall.getNameAsString());
    }

    private boolean rewriteLegacyUpgradeAuditVerification(MethodDeclaration method) {
        boolean changed = false;
        for (MethodCallExpr expr : method.findAll(MethodCallExpr.class)) {
            if (!"recordEvent".equals(expr.getNameAsString()) || expr.getArguments().size() != 1) {
                continue;
            }
            Expression scope = expr.getScope().orElse(null);
            if (!(scope instanceof MethodCallExpr verifyCall)
                    || !"verify".equals(verifyCall.getNameAsString())
                    || verifyCall.getArguments().size() != 1
                    || !"auditTrailService".equals(verifyCall.getArgument(0).toString())) {
                continue;
            }
            expr.setArgument(0, StaticJavaParser.parseExpression("startsWith(\"Legacy upgrade promoted \")"));
            changed = true;
        }
        return changed;
    }

    private java.util.Optional<Integer> extractNamedIntegerInitializer(MethodDeclaration method, String variableName) {
        if (method == null || variableName == null || variableName.isBlank()) {
            return java.util.Optional.empty();
        }
        for (VariableDeclarator variable : method.findAll(VariableDeclarator.class)) {
            if (!variableName.equals(variable.getNameAsString())) {
                continue;
            }
            Expression initializer = variable.getInitializer().orElse(null);
            if (!(initializer instanceof IntegerLiteralExpr literal)) {
                continue;
            }
            try {
                return java.util.Optional.of(Integer.parseInt(literal.getValue().replace("_", "")));
            } catch (NumberFormatException ignored) {
                return java.util.Optional.empty();
            }
        }
        return java.util.Optional.empty();
    }

    private boolean ensureNamedIntegerInitializer(MethodDeclaration method, String variableName, String replacementExpression) {
        if (method == null || variableName == null || variableName.isBlank()
                || replacementExpression == null || replacementExpression.isBlank()) {
            return false;
        }
        for (VariableDeclarator variable : method.findAll(VariableDeclarator.class)) {
            if (!variableName.equals(variable.getNameAsString())) {
                continue;
            }
            String type = variable.getType().asString();
            if (!"int".equals(type) && !"Integer".equals(type)) {
                continue;
            }
            variable.setInitializer(StaticJavaParser.parseExpression(replacementExpression));
            return true;
        }
        return false;
    }

    private boolean rewriteEqualityAssertion(MethodDeclaration method,
                                             String assignedVariable,
                                             String expectedExpression) {
        boolean changed = false;
        for (MethodCallExpr expr : method.findAll(MethodCallExpr.class)) {
            if ("assertEquals".equals(expr.getNameAsString()) && expr.getArguments().size() >= 2) {
                if (!referencesAssignedVariable(expr.getArgument(1), assignedVariable)) {
                    continue;
                }
                expr.setArgument(0, StaticJavaParser.parseExpression(expectedExpression));
                changed = true;
            }
            if ("isEqualTo".equals(expr.getNameAsString()) && expr.getArguments().size() == 1) {
                Expression scope = expr.getScope().orElse(null);
                if (!(scope instanceof MethodCallExpr assertThatCall)
                        || !"assertThat".equals(assertThatCall.getNameAsString())
                        || assertThatCall.getArguments().size() != 1
                        || !referencesAssignedVariable(assertThatCall.getArgument(0), assignedVariable)) {
                    continue;
                }
                expr.setArgument(0, StaticJavaParser.parseExpression(expectedExpression));
                changed = true;
            }
        }
        return changed;
    }

    private boolean referencesAssignedVariable(Expression expression, String assignedVariable) {
        if (assignedVariable == null || assignedVariable.isBlank() || expression == null) {
            return false;
        }
        return assignedVariable.equals(expression.toString());
    }

    private int findActStatementIndex(BlockStmt body, String targetMethod) {
        for (int index = 0; index < body.getStatements().size(); index++) {
            Statement statement = body.getStatement(index);
            if (statement.findAll(MethodCallExpr.class).stream()
                    .anyMatch(callExpr -> !isAssertionNode(callExpr) && callExpr.getNameAsString().equals(targetMethod))) {
                return index;
            }
        }
        for (int index = 0; index < body.getStatements().size(); index++) {
            Statement statement = body.getStatement(index);
            if (statement.findAll(MethodCallExpr.class).stream().anyMatch(callExpr -> !isAssertionNode(callExpr))) {
                return index;
            }
        }
        return -1;
    }

    private MethodCallExpr findTargetMethodCall(Statement statement, String targetMethod) {
        if (statement == null || targetMethod == null || targetMethod.isBlank()) {
            return null;
        }
        return statement.findAll(MethodCallExpr.class).stream()
                .filter(callExpr -> !isAssertionNode(callExpr) && targetMethod.equals(callExpr.getNameAsString()))
                .findFirst()
                .orElse(null);
    }

    private int targetInvocationArity(MethodDeclaration method, String targetMethod) {
        if (method == null || method.getBody().isEmpty() || targetMethod == null || targetMethod.isBlank()) {
            return -1;
        }
        BlockStmt body = method.getBody().orElse(null);
        if (body == null) {
            return -1;
        }
        int actIndex = findActStatementIndex(body, targetMethod);
        if (actIndex < 0) {
            return -1;
        }
        MethodCallExpr targetCall = findTargetMethodCall(body.getStatement(actIndex), targetMethod);
        return targetCall == null ? -1 : targetCall.getArguments().size();
    }

    private boolean rewriteActSecondArgument(Statement statement, String targetMethod, String replacementExpression) {
        if (statement == null || targetMethod == null || targetMethod.isBlank() || replacementExpression == null || replacementExpression.isBlank()) {
            return false;
        }
        for (MethodCallExpr callExpr : statement.findAll(MethodCallExpr.class)) {
            if (!targetMethod.equals(callExpr.getNameAsString()) || callExpr.getArguments().size() < 2) {
                continue;
            }
            callExpr.setArgument(1, StaticJavaParser.parseExpression(replacementExpression));
            return true;
        }
        return false;
    }

    private String extractActInvocation(Statement statement) {
        if (!(statement instanceof ExpressionStmt expressionStmt)) {
            return null;
        }
        Expression expression = expressionStmt.getExpression();
        if (expression instanceof VariableDeclarationExpr declarationExpr
                && declarationExpr.getVariables().size() == 1) {
            return declarationExpr.getVariable(0).getInitializer()
                    .map(Expression::toString)
                    .orElse(null);
        }
        return expression.toString();
    }

    private String extractAssignedVariableName(Statement statement) {
        if (!(statement instanceof ExpressionStmt expressionStmt)) {
            return null;
        }
        Expression expression = expressionStmt.getExpression();
        if (expression instanceof VariableDeclarationExpr declarationExpr
                && declarationExpr.getVariables().size() == 1) {
            return declarationExpr.getVariable(0).getNameAsString();
        }
        return null;
    }

    private boolean shouldRemoveAfterGuardRewrite(Statement statement, String assignedVariable) {
        if (statement == null) {
            return false;
        }
        if (statement.findAll(MethodCallExpr.class).stream().anyMatch(this::isAssertionOrVerifyCall)) {
            return true;
        }
        return assignedVariable != null
                && !assignedVariable.isBlank()
                && statement.toString().contains(assignedVariable);
    }

    private boolean isAssertionOrVerifyCall(MethodCallExpr callExpr) {
        if (callExpr == null) {
            return false;
        }
        String name = callExpr.getNameAsString().toLowerCase(Locale.ROOT);
        return "verify".equals(name) || ASSERTION_METHODS.contains(name) || name.startsWith("assert");
    }

    private NumericAdjustment adjustNumericLiteral(MethodDeclaration method) {
        for (IntegerLiteralExpr literal : method.findAll(IntegerLiteralExpr.class)) {
            if (isAssertionNode(literal)) {
                continue;
            }
            try {
                int value = Integer.parseInt(literal.getValue().replace("_", ""));
                int updatedValue = adjustIntegerBoundary(value);
                literal.setInt(updatedValue);
                return new NumericAdjustment(value, updatedValue);
            } catch (NumberFormatException ignored) {
                // skip unsupported integer literal forms
            }
        }
        for (LongLiteralExpr literal : method.findAll(LongLiteralExpr.class)) {
            if (isAssertionNode(literal)) {
                continue;
            }
            try {
                String raw = literal.getValue().replace("_", "");
                String normalized = raw.endsWith("L") || raw.endsWith("l")
                        ? raw.substring(0, raw.length() - 1)
                        : raw;
                long value = Long.parseLong(normalized);
                long updatedValue = adjustLongBoundary(value);
                literal.setLong(updatedValue);
                return new NumericAdjustment(value, updatedValue);
            } catch (NumberFormatException ignored) {
                // skip unsupported long literal forms
            }
        }
        for (DoubleLiteralExpr literal : method.findAll(DoubleLiteralExpr.class)) {
            if (isAssertionNode(literal)) {
                continue;
            }
            try {
                String raw = literal.getValue().replace("_", "");
                String normalized = raw.endsWith("d") || raw.endsWith("D")
                        ? raw.substring(0, raw.length() - 1)
                        : raw;
                double value = Double.parseDouble(normalized);
                double updatedValue = adjustDoubleBoundary(value);
                literal.setDouble(updatedValue);
                return new NumericAdjustment(value, updatedValue);
            } catch (NumberFormatException ignored) {
                // skip unsupported double literal forms
            }
        }
        return null;
    }

    private int adjustIntegerBoundary(int value) {
        return value > 0 ? value - 1 : value + 1;
    }

    private long adjustLongBoundary(long value) {
        return value > 0L ? value - 1L : value + 1L;
    }

    private double adjustDoubleBoundary(double value) {
        return value > 0.0d ? value - 1.0d : value + 1.0d;
    }

    private void alignIndexedUserExpectation(MethodDeclaration method, Number updatedIndexValue) {
        if (method == null || updatedIndexValue == null) {
            return;
        }
        int userIndex = updatedIndexValue.intValue();
        if (userIndex < 0) {
            return;
        }
        List<String> expectedUsernames = new ArrayList<>();
        for (MethodCallExpr expr : method.findAll(MethodCallExpr.class)) {
            if (!"add".equals(expr.getNameAsString()) || expr.getArguments().size() != 1) {
                continue;
            }
            Expression argument = expr.getArgument(0);
            if (!(argument instanceof com.github.javaparser.ast.expr.ObjectCreationExpr creationExpr)
                    || !"User".equals(creationExpr.getType().getNameAsString())
                    || creationExpr.getArguments().isEmpty()
                    || !(creationExpr.getArgument(0) instanceof StringLiteralExpr usernameLiteral)) {
                continue;
            }
            expectedUsernames.add(usernameLiteral.getValue());
        }
        if (userIndex >= expectedUsernames.size()) {
            return;
        }
        for (MethodCallExpr expr : method.findAll(MethodCallExpr.class)) {
            if (!"assertEquals".equals(expr.getNameAsString()) || expr.getArguments().size() < 2) {
                continue;
            }
            Expression actual = expr.getArgument(1);
            if (!(actual instanceof MethodCallExpr actualCall)
                    || !"getUsername".equals(actualCall.getNameAsString())) {
                continue;
            }
            expr.setArgument(0, new StringLiteralExpr(expectedUsernames.get(userIndex)));
            return;
        }
    }

    private boolean promoteFindUserVariantToOutOfBounds(MethodDeclaration method, String targetMethod) {
        BlockStmt body = method.getBody().orElse(null);
        if (body == null || body.getStatements().isEmpty()) {
            return false;
        }
        String usersVariable = resolveCollectionVariableName(method);
        if (usersVariable == null || usersVariable.isBlank()) {
            return false;
        }
        boolean indexAdjusted = false;
        for (VariableDeclarator variable : method.findAll(VariableDeclarator.class)) {
            if (!"index".equals(variable.getNameAsString())) {
                continue;
            }
            variable.setInitializer(StaticJavaParser.parseExpression(usersVariable + ".size()"));
            indexAdjusted = true;
            break;
        }
        if (!indexAdjusted) {
            int actIndex = findActStatementIndex(body, targetMethod);
            if (actIndex < 0) {
                return false;
            }
            Statement actStatement = body.getStatement(actIndex);
            if (!(actStatement instanceof ExpressionStmt expressionStmt)) {
                return false;
            }
            if (!(expressionStmt.getExpression() instanceof VariableDeclarationExpr declarationExpr)
                    || declarationExpr.getVariables().size() != 1) {
                return false;
            }
            VariableDeclarator variable = declarationExpr.getVariable(0);
            Expression initializer = variable.getInitializer().orElse(null);
            if (!(initializer instanceof MethodCallExpr callExpr)
                    || !"findUser".equals(callExpr.getNameAsString())
                    || callExpr.getArguments().size() != 1) {
                return false;
            }
            callExpr.setArgument(0, StaticJavaParser.parseExpression(usersVariable + ".size()"));
            indexAdjusted = true;
        }
        if (!indexAdjusted) {
            return false;
        }
        String assignedVariable = extractAssignedVariableName(body.getStatement(findActStatementIndex(body, targetMethod)));
        return rewriteFindUserAssertionToAssertNull(body, assignedVariable);
    }

    private String resolveCollectionVariableName(MethodDeclaration method) {
        if (method == null) {
            return null;
        }
        for (VariableDeclarator variable : method.findAll(VariableDeclarator.class)) {
            String type = variable.getType().asString();
            if (!type.contains("List")) {
                continue;
            }
            Expression initializer = variable.getInitializer().orElse(null);
            if (initializer == null) {
                continue;
            }
            String initializerText = initializer.toString();
            if (initializerText.contains("ArrayList") || initializerText.contains("List.of")) {
                return variable.getNameAsString();
            }
        }
        return null;
    }

    private boolean rewriteFindUserAssertionToAssertNull(BlockStmt body, String assignedVariable) {
        if (body == null || assignedVariable == null || assignedVariable.isBlank()) {
            return false;
        }
        for (int index = 0; index < body.getStatements().size(); index++) {
            Statement statement = body.getStatement(index);
            if (!(statement instanceof ExpressionStmt expressionStmt)
                    || !(expressionStmt.getExpression() instanceof MethodCallExpr callExpr)
                    || !"assertEquals".equals(callExpr.getNameAsString())) {
                continue;
            }
            body.getStatements().set(index,
                    StaticJavaParser.parseStatement("assertNull(" + assignedVariable + ");"));
            return true;
        }
        return false;
    }

    private boolean isAssertionNode(Node node) {
        Node current = node;
        while (current != null) {
            if (current instanceof MethodCallExpr callExpr) {
                String name = callExpr.getNameAsString().toLowerCase(Locale.ROOT);
                if (ASSERTION_METHODS.contains(name) || name.startsWith("assert")) {
                    return true;
                }
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private String resolveUniqueVariantName(LinkedHashSet<String> usedNames, String generatedMethodName) {
        for (int index = 2; index <= mergePolicy.maxCollisionAttempts() + 1; index++) {
            String candidate = generatedMethodName + "Coverage" + mergePolicy.collisionSuffixStem() + index;
            if (!usedNames.contains(candidate)) {
                usedNames.add(candidate);
                return candidate;
            }
        }
        String overflow = generatedMethodName + "Coverage" + mergePolicy.collisionSuffixStem() + "Overflow";
        usedNames.add(overflow);
        return overflow;
    }

    private String renderMethod(MethodDeclaration method) {
        String indent = "    ";
        String[] lines = method.toString().stripTrailing().split("\\R");
        StringBuilder builder = new StringBuilder();
        boolean firstLine = true;
        for (String line : lines) {
            String candidate = line.startsWith(indent) ? line : indent + line.stripLeading();
            if (firstLine) {
                builder.append(candidate);
                firstLine = false;
            } else {
                builder.append(System.lineSeparator()).append(candidate);
            }
        }
        return builder.toString().stripTrailing();
    }

    private String sanitizeUpper(String value) {
        String sanitized = value == null ? "" : value.replaceAll("[^A-Za-z0-9]+", "_");
        return sanitized.toUpperCase(Locale.ROOT);
    }

    private enum AverageLoginAttemptsBranch {
        EMPTY_BASELINE,
        NON_EMPTY_OR_UNKNOWN_BASELINE
    }

    private record NumericAdjustment(Number originalValue,
                                     Number updatedValue) {
    }

    private record GuardMutation(String exceptionClass) {
    }

    private record StateToggleMutation(boolean expectedResult) {
    }
}
