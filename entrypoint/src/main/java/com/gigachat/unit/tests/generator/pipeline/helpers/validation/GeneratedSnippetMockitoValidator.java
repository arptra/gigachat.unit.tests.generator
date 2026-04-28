package com.gigachat.unit.tests.generator.pipeline.helpers.validation;

import com.gigachat.unit.tests.generator.analyzer.ExternalCollaboratorDetector;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.pipeline.InvalidLLMResponseException;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.testagent.entrypoint.pipeline.helpers.analyze.DependencyInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.MockType;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GeneratedSnippetMockitoValidator {

    private final PipelineLogger logger;
    private final MethodSignatureRegistry signatureRegistry;
    private final ExternalCollaboratorDetector collaboratorDetector;

    GeneratedSnippetMockitoValidator(PipelineLogger logger,
                                     MethodSignatureRegistry signatureRegistry,
                                     ExternalCollaboratorDetector collaboratorDetector) {
        this.logger = logger;
        this.signatureRegistry = signatureRegistry;
        this.collaboratorDetector = collaboratorDetector;
    }

    void ensureMockUsageIsValid(String fullSource,
                                Analyze.AnalysisSummary analysisSummary,
                                TestClassInfo classInfo) {
        MockPlan plan = analysisSummary.mockPlan();
        if (plan != null && plan.strategy() == MockStrategy.NONE && ValidationSupport.containsMockito(fullSource)) {
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

    void ensureNoForbiddenConstructorMockUsage(CompilationUnit compilationUnit,
                                               Analyze.AnalysisSummary analysisSummary) {
        if (compilationUnit == null || analysisSummary == null || analysisSummary.methodAnalysis() == null) {
            return;
        }
        Set<String> forbiddenVariables = new LinkedHashSet<>();
        Set<String> forbiddenTypes = new LinkedHashSet<>();
        for (DependencyInfo dependency : analysisSummary.methodAnalysis().dependencies()) {
            if (dependency == null || dependency.mockType() != MockType.CONSTRUCTOR) {
                continue;
            }
            String variableName = ValidationSupport.normalise(dependency.variableName());
            if (!variableName.isBlank() && !"unknown".equalsIgnoreCase(variableName)) {
                forbiddenVariables.add(variableName);
            }
            String className = ValidationSupport.simpleName(dependency.className());
            if (!className.isBlank()) {
                forbiddenTypes.add(className);
            }
        }
        if (forbiddenVariables.isEmpty() && forbiddenTypes.isEmpty()) {
            return;
        }
        LinkedHashSet<String> violations = new LinkedHashSet<>();
        compilationUnit.findAll(MethodCallExpr.class).forEach(expr ->
                collectForbiddenMockitoViolations(expr, forbiddenVariables, forbiddenTypes, violations));
        if (!violations.isEmpty()) {
            String message = "E106: forbidden Mockito usage on constructor-created local objects " + String.join(", ", violations);
            logger.warn("⚠️  " + message);
            throw new InvalidLLMResponseException(message);
        }
    }

    void ensureNoVoidMethodStubbingInsideMockitoWhen(CompilationUnit compilationUnit,
                                                     Map<String, String> variableTypes,
                                                     TestClassInfo classInfo) {
        if (compilationUnit == null) {
            return;
        }
        LinkedHashSet<String> violations = new LinkedHashSet<>();
        compilationUnit.findAll(MethodCallExpr.class).forEach(expr -> {
            if (!"when".equals(ValidationSupport.normalise(expr.getNameAsString())) || expr.getArguments().size() != 1) {
                return;
            }
            Expression argument = expr.getArgument(0);
            if (!(argument instanceof MethodCallExpr methodCallExpr)) {
                return;
            }
            if (!isKnownVoidMethodInvocation(methodCallExpr, variableTypes, classInfo)) {
                return;
            }
            violations.add("when(" + ValidationSupport.abbreviate(methodCallExpr.toString(), 120) + ")");
        });
        compilationUnit.findAll(MethodCallExpr.class).forEach(expr -> {
            String chainedMethod = ValidationSupport.normalise(expr.getNameAsString());
            if (!Set.of("thenCallRealMethod", "thenReturn", "thenAnswer", "thenThrow").contains(chainedMethod)) {
                return;
            }
            if (expr.getScope().isEmpty()) {
                return;
            }
            Expression scope = expr.getScope().orElse(null);
            if (!(scope instanceof MethodCallExpr whenCall)
                    || !"when".equals(ValidationSupport.normalise(whenCall.getNameAsString()))
                    || whenCall.getArguments().size() != 1) {
                return;
            }
            Expression whenArgument = whenCall.getArgument(0);
            if (!(whenArgument instanceof MethodCallExpr methodCallExpr)) {
                return;
            }
            if (!isKnownVoidMethodInvocation(methodCallExpr, variableTypes, classInfo)) {
                return;
            }
            violations.add("when(" + ValidationSupport.abbreviate(methodCallExpr.toString(), 120) + ")." + chainedMethod + "(...)");
        });
        if (violations.isEmpty()) {
            return;
        }
        String message = "E109: void method invocation used inside Mockito.when(...) " + String.join(", ", violations);
        logger.warn("⚠️  " + message);
        throw new InvalidLLMResponseException(message);
    }

    void ensureNoVoidMethodUsedAsValue(CompilationUnit compilationUnit,
                                       Map<String, String> variableTypes,
                                       TestClassInfo classInfo) {
        if (compilationUnit == null) {
            return;
        }
        LinkedHashSet<String> violations = new LinkedHashSet<>();
        compilationUnit.findAll(MethodCallExpr.class).forEach(expr -> {
            if (!isKnownVoidMethodInvocation(expr, variableTypes, classInfo)) {
                return;
            }
            if (isStandaloneVoidStatement(expr) || isHandledByMockitoWhenValidation(expr)) {
                return;
            }
            violations.add(ValidationSupport.abbreviate(expr.toString(), 120));
        });
        if (violations.isEmpty()) {
            return;
        }
        String message = "E113: void mutator used as value expression " + String.join(", ", violations);
        logger.warn("⚠️  " + message);
        throw new InvalidLLMResponseException(message);
    }

    void ensureNoMockitoStubbingOnStaticBranchDrivers(CompilationUnit compilationUnit,
                                                      Analyze.AnalysisSummary analysisSummary,
                                                      Map<String, String> variableTypes,
                                                      TestClassInfo classInfo) {
        if (compilationUnit == null || analysisSummary == null || analysisSummary.methodAnalysis() == null) {
            return;
        }
        Set<String> staticOwnerTypes = analysisSummary.methodAnalysis().dependencies().stream()
                .filter(dependency -> dependency != null && dependency.mockType() == MockType.STATIC)
                .map(DependencyInfo::className)
                .map(ValidationSupport::simpleName)
                .filter(name -> !name.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (staticOwnerTypes.isEmpty()) {
            return;
        }
        LinkedHashSet<String> violations = new LinkedHashSet<>();
        compilationUnit.findAll(MethodCallExpr.class).forEach(expr -> {
            if (!"when".equals(ValidationSupport.normalise(expr.getNameAsString())) || expr.getArguments().size() != 1) {
                return;
            }
            Expression argument = expr.getArgument(0);
            if (!(argument instanceof MethodCallExpr methodCallExpr)) {
                return;
            }
            if (!isStaticBranchDriverInvocation(methodCallExpr, staticOwnerTypes, variableTypes, classInfo)) {
                return;
            }
            violations.add("when(" + ValidationSupport.abbreviate(methodCallExpr.toString(), 120) + ")");
        });
        compilationUnit.findAll(MethodCallExpr.class).forEach(expr -> {
            String chainedMethod = ValidationSupport.normalise(expr.getNameAsString());
            if (!Set.of("thenCallRealMethod", "thenReturn", "thenAnswer", "thenThrow").contains(chainedMethod)) {
                return;
            }
            if (expr.getScope().isEmpty()) {
                return;
            }
            Expression scope = expr.getScope().orElse(null);
            if (!(scope instanceof MethodCallExpr whenCall)
                    || !"when".equals(ValidationSupport.normalise(whenCall.getNameAsString()))
                    || whenCall.getArguments().size() != 1) {
                return;
            }
            Expression whenArgument = whenCall.getArgument(0);
            if (!(whenArgument instanceof MethodCallExpr methodCallExpr)) {
                return;
            }
            if (!isStaticBranchDriverInvocation(methodCallExpr, staticOwnerTypes, variableTypes, classInfo)) {
                return;
            }
            violations.add("when(" + ValidationSupport.abbreviate(methodCallExpr.toString(), 120) + ")." + chainedMethod + "(...)");
        });
        if (violations.isEmpty()) {
            return;
        }
        String message = "E114: Mockito stubbing applied to non-mock static branch driver " + String.join(", ", violations);
        logger.warn("⚠️  " + message);
        throw new InvalidLLMResponseException(message);
    }

    private boolean isKnownVoidMethodInvocation(MethodCallExpr expression,
                                                Map<String, String> variableTypes,
                                                TestClassInfo classInfo) {
        if (expression == null || expression.getScope().isEmpty()) {
            return false;
        }
        String resolvedType = ValidationSupport.resolveExpressionType(expression.getScope().orElse(null), variableTypes, classInfo);
        if (ValidationSupport.isStandardLibraryType(resolvedType)) {
            return false;
        }
        String ownerType = ValidationSupport.simpleName(resolvedType);
        if (ownerType.isBlank() || !signatureRegistry.hasClass(ownerType)) {
            return false;
        }
        String methodName = ValidationSupport.normalise(expression.getNameAsString());
        int arity = expression.getArguments().size();
        return signatureRegistry.methodsFor(ownerType).stream()
                .filter(signature -> methodSignatureMatches(signature, methodName, arity))
                .anyMatch(this::isVoidMethodSignature);
    }

    private boolean isStaticBranchDriverInvocation(MethodCallExpr expression,
                                                   Set<String> staticOwnerTypes,
                                                   Map<String, String> variableTypes,
                                                   TestClassInfo classInfo) {
        if (expression == null || expression.getScope().isEmpty() || staticOwnerTypes == null || staticOwnerTypes.isEmpty()) {
            return false;
        }
        String resolvedType = ValidationSupport.resolveExpressionType(expression.getScope().orElse(null), variableTypes, classInfo);
        String ownerType = ValidationSupport.simpleName(resolvedType);
        return !ownerType.isBlank() && staticOwnerTypes.contains(ownerType);
    }

    private boolean methodSignatureMatches(String signature, String methodName, int arity) {
        if (signature == null || signature.isBlank() || methodName == null || methodName.isBlank()) {
            return false;
        }
        Matcher matcher = Pattern.compile("(.+)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)").matcher(signature.trim());
        if (!matcher.matches()) {
            return false;
        }
        if (!methodName.equals(ValidationSupport.normalise(matcher.group(2)))) {
            return false;
        }
        String params = matcher.group(3).trim();
        if (params.isEmpty()) {
            return arity == 0;
        }
        return params.split(",").length == arity;
    }

    private boolean isVoidMethodSignature(String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        Matcher matcher = Pattern.compile("(.+)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\(").matcher(signature.trim());
        if (!matcher.find()) {
            return false;
        }
        String returnType = matcher.group(1).trim();
        return "void".equals(ValidationSupport.simpleName(returnType));
    }

    private boolean isStandaloneVoidStatement(MethodCallExpr expression) {
        return expression != null
                && expression.getParentNode().isPresent()
                && expression.getParentNode().get() instanceof ExpressionStmt;
    }

    private boolean isHandledByMockitoWhenValidation(MethodCallExpr expression) {
        if (expression == null || expression.getParentNode().isEmpty()) {
            return false;
        }
        if (!(expression.getParentNode().get() instanceof MethodCallExpr parentCall)) {
            return false;
        }
        return "when".equals(ValidationSupport.normalise(parentCall.getNameAsString()))
                && parentCall.getArguments().size() == 1
                && parentCall.getArgument(0) == expression;
    }

    private void collectForbiddenMockitoViolations(MethodCallExpr expr,
                                                   Set<String> forbiddenVariables,
                                                   Set<String> forbiddenTypes,
                                                   Set<String> collector) {
        if (expr == null || collector == null) {
            return;
        }
        String methodName = ValidationSupport.normalise(expr.getNameAsString());
        if (!Set.of("when", "verify", "spy", "given").contains(methodName)) {
            return;
        }
        if (expr.getArguments().isEmpty()) {
            return;
        }
        Expression candidate = expr.getArgument(0);
        if (!containsForbiddenConstructorReference(candidate, forbiddenVariables, forbiddenTypes)) {
            return;
        }
        collector.add(methodName + "(" + ValidationSupport.abbreviate(candidate.toString(), 120) + ")");
    }

    private boolean containsForbiddenConstructorReference(Expression expression,
                                                          Set<String> forbiddenVariables,
                                                          Set<String> forbiddenTypes) {
        if (expression == null) {
            return false;
        }
        if (expression.findFirst(NameExpr.class, nameExpr ->
                forbiddenVariables.contains(nameExpr.getNameAsString())).isPresent()) {
            return true;
        }
        return expression.findFirst(ObjectCreationExpr.class, creationExpr ->
                forbiddenTypes.contains(ValidationSupport.simpleName(creationExpr.getType().asString()))).isPresent();
    }
}
