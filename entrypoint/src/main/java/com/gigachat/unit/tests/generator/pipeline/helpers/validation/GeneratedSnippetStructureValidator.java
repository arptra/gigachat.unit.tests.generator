package com.gigachat.unit.tests.generator.pipeline.helpers.validation;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.gradle.ResolvedTestRuntimeClasspath;
import com.gigachat.unit.tests.generator.gradle.TestRuntimeClasspathResolver;
import com.gigachat.unit.tests.generator.pipeline.InvalidLLMResponseException;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.pipeline.helpers.TargetConstructorPolicyResolver;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

final class GeneratedSnippetStructureValidator {

    private final PipelineLogger logger;
    private final ClasspathTypeResolver classpathTypeResolver;
    private final TargetConstructorPolicyResolver targetConstructorPolicyResolver = new TargetConstructorPolicyResolver();

    GeneratedSnippetStructureValidator(PipelineLogger logger) {
        this(logger, new GradleClasspathTypeResolver(logger));
    }

    GeneratedSnippetStructureValidator(PipelineLogger logger, ClasspathTypeResolver classpathTypeResolver) {
        this.logger = logger;
        this.classpathTypeResolver = classpathTypeResolver;
    }

    void ensureNoInternalFieldAccess(String generatedCode, Analyze.AnalysisSummary analysisSummary) {
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
        LinkedHashSet<String> violations = new LinkedHashSet<>();
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        String targetInstance = targetContext == null ? "" : ValidationSupport.normalise(targetContext.instanceName());
        Set<String> accessible = analysisSummary.accessibleFields();
        CompilationUnit parsed = parseGeneratedCode(code);
        if (parsed == null) {
            return;
        }
        for (FieldAccessExpr fieldAccess : parsed.findAll(FieldAccessExpr.class)) {
            String field = fieldAccess.getNameAsString();
            if (!internalFields.contains(field)) {
                continue;
            }
            if (accessible != null && accessible.contains(field)) {
                continue;
            }
            String scopeText = fieldAccess.getScope().toString();
            if ("this".equals(scopeText) && field.equals(targetInstance)) {
                continue;
            }
            violations.add(scopeText + '.' + field);
        }
        if (!violations.isEmpty()) {
            String message = "E103: Internal field access " + String.join(", ", violations);
            logger.warn("⚠️  " + message);
            throw new InvalidLLMResponseException(message);
        }
    }

    private CompilationUnit parseGeneratedCode(String code) {
        try {
            return StaticJavaParser.parse(code);
        } catch (ParseProblemException ignored) {
            try {
                return StaticJavaParser.parse("class GeneratedSnippetValidationProbe {\n  void probe() {\n" + code + "\n  }\n}\n");
            } catch (ParseProblemException exception) {
                logger.warn("Unable to parse generated source for structure validation: " + exception.getMessage());
                return null;
            }
        }
    }

    void ensureTargetUsesMockAwareConstruction(CompilationUnit compilationUnit,
                                               Analyze.AnalysisSummary analysisSummary) {
        if (compilationUnit == null || analysisSummary == null || analysisSummary.testTargetContext() == null) {
            return;
        }
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        if (!targetContext.requiresInstance() || targetContext.isStatic()) {
            return;
        }
        List<MockAwareConstructorExpectation> expectations = buildMockAwareConstructorExpectations(analysisSummary);
        if (expectations.isEmpty()) {
            return;
        }
        String targetClass = ValidationSupport.simpleName(targetContext.className());
        List<ObjectCreationExpr> targetCreations = compilationUnit.findAll(ObjectCreationExpr.class).stream()
                .filter(expr -> targetClass.equals(ValidationSupport.simpleName(expr.getType().asString())))
                .toList();
        if (targetCreations.isEmpty()) {
            return;
        }
        boolean validConstruction = targetCreations.stream()
                .anyMatch(expr -> expectations.stream().anyMatch(expectation -> matchesMockAwareConstruction(expr, expectation)));
        if (validConstruction) {
            return;
        }
        LinkedHashSet<String> foundConstructions = new LinkedHashSet<>();
        targetCreations.forEach(expr -> foundConstructions.add(ValidationSupport.abbreviate(expr.toString(), 160)));
        LinkedHashSet<String> requiredCollaborators = new LinkedHashSet<>();
        LinkedHashSet<String> preferredSignatures = new LinkedHashSet<>();
        expectations.forEach(expectation -> {
            requiredCollaborators.addAll(expectation.mockedCollaboratorNames());
            preferredSignatures.add(expectation.signature());
        });
        String message = "E107: class under test \"" + targetClass
                + "\" must be constructed with mocked collaborators "
                + requiredCollaborators
                + " using one of "
                + preferredSignatures
                + " instead of "
                + foundConstructions;
        logger.warn("⚠️  " + message);
        throw new InvalidLLMResponseException(message);
    }

    List<String> buildTargetConstructionRetryConstraints(Analyze.AnalysisSummary analysisSummary) {
        if (analysisSummary == null || analysisSummary.testTargetContext() == null) {
            return List.of();
        }
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        String targetClass = ValidationSupport.simpleName(targetContext.className());
        if (targetClass.isBlank()) {
            return List.of();
        }
        List<MockAwareConstructorExpectation> expectations = buildMockAwareConstructorExpectations(analysisSummary);
        if (expectations.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> constraints = new LinkedHashSet<>();
        MockAwareConstructorExpectation preferred = expectations.get(0);
        constraints.add("Do not construct \"" + targetClass + "\" with " + targetClass + "() because that bypasses mocked collaborators: "
                + String.join(", ", preferred.mockedCollaboratorNames()) + '.');
        constraints.add("Use explicit constructor injection for \"" + targetClass + "\" with constructor "
                + preferred.signature() + '.');
        if (!preferred.bindingSummary().isBlank()) {
            constraints.add("Constructor parameters that must receive the same Mockito mocks are: "
                    + preferred.bindingSummary() + '.');
        }
        constraints.add("Do not pass null literals to constructor parameters that the source class requires to be non-null on the exercised path.");
        constraints.add("For constructor parameters not listed in shouldMock, keep setup minimal and avoid creating deep real dependency graphs unless the source snippet actually dereferences them on the exercised path.");
        return List.copyOf(constraints);
    }

    void ensureRequiredConstructorArgumentsAreNotNull(Path projectRoot,
                                                      CompilationUnit compilationUnit,
                                                      Analyze.AnalysisSummary analysisSummary) {
        if (projectRoot == null || compilationUnit == null || analysisSummary == null || analysisSummary.testTargetContext() == null) {
            return;
        }
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        if (!targetContext.requiresInstance() || targetContext.isStatic()) {
            return;
        }
        String targetClass = ValidationSupport.simpleName(targetContext.className());
        if (targetClass.isBlank()) {
            return;
        }
        Map<String, List<TargetConstructorPolicyResolver.RequiredConstructorArgument>> requiredArgsBySignature =
                targetConstructorPolicyResolver.resolveRequiredNonNullArguments(projectRoot, analysisSummary);
        if (requiredArgsBySignature.isEmpty()) {
            return;
        }
        List<RequiredConstructorExpectation> expectations = buildRequiredConstructorExpectations(analysisSummary, targetClass, requiredArgsBySignature);
        if (expectations.isEmpty()) {
            return;
        }
        List<ObjectCreationExpr> targetCreations = compilationUnit.findAll(ObjectCreationExpr.class).stream()
                .filter(expr -> targetClass.equals(ValidationSupport.simpleName(expr.getType().asString())))
                .toList();
        if (targetCreations.isEmpty()) {
            return;
        }
        boolean evaluatedStrictConstructor = false;
        LinkedHashSet<String> violations = new LinkedHashSet<>();
        for (ObjectCreationExpr creation : targetCreations) {
            for (RequiredConstructorExpectation expectation : expectations) {
                if (creation.getArguments().size() != expectation.argCount()) {
                    continue;
                }
                evaluatedStrictConstructor = true;
                List<String> missingArgs = missingRequiredArguments(creation, expectation);
                if (missingArgs.isEmpty()) {
                    return;
                }
                violations.add((expectation.signature().isBlank() ? targetClass : expectation.signature())
                        + " -> " + String.join(", ", missingArgs));
            }
        }
        if (!evaluatedStrictConstructor || violations.isEmpty()) {
            return;
        }
        String message = "E110: class under test \"" + targetClass
                + "\" must not receive null literals for required constructor arguments "
                + violations;
        logger.warn("⚠️  " + message);
        throw new InvalidLLMResponseException(message);
    }

    void ensureProjectImportsAreResolvable(Path projectRoot,
                                           CompilationUnit compilationUnit) {
        ensureProjectImportsAreResolvable(projectRoot, null, compilationUnit);
    }

    void ensureProjectImportsAreResolvable(Path projectRoot,
                                           TestClassInfo classInfo,
                                           CompilationUnit compilationUnit) {
        if (projectRoot == null || compilationUnit == null) {
            return;
        }
        Path mainSourceRoot = projectRoot.resolve("src/main/java");
        if (!Files.isDirectory(mainSourceRoot)) {
            return;
        }
        Path testClassFile = classInfo == null ? null : classInfo.getTargetPath();
        LinkedHashSet<String> violations = new LinkedHashSet<>();
        for (ImportDeclaration importDeclaration : compilationUnit.getImports()) {
            if (importDeclaration == null || importDeclaration.isStatic() || importDeclaration.isAsterisk()) {
                continue;
            }
            String importedFqcn = importDeclaration.getNameAsString();
            if (shouldIgnoreImportValidation(importedFqcn)) {
                continue;
            }
            Path importedPath = mainSourceRoot.resolve(importedFqcn.replace('.', '/') + ".java");
            if (Files.isRegularFile(importedPath)) {
                continue;
            }
            if (isResolvableFromBuildClasspath(projectRoot, testClassFile, importedFqcn)) {
                continue;
            }
            List<String> candidates = findProjectTypesBySimpleName(mainSourceRoot, ValidationSupport.simpleName(importedFqcn));
            if (candidates.size() == 1
                    && !importedFqcn.equals(candidates.get(0))
                    && looksLikeWrongProjectPackage(importedFqcn, candidates.get(0))) {
                violations.add(importedFqcn + " -> " + candidates.get(0));
            }
        }
        if (violations.isEmpty()) {
            return;
        }
        String message = "E111: project import does not resolve and should use the authoritative in-project type "
                + violations;
        logger.warn("⚠️  " + message);
        throw new InvalidLLMResponseException(message);
    }

    void ensureNoConflictingLifecycleFixtureRedefinition(TestClassInfo classInfo,
                                                         GeneratedTestSnippet snippet,
                                                         CompilationUnit compilationUnit,
                                                         Analyze.AnalysisSummary analysisSummary) {
        if (classInfo == null || snippet == null || compilationUnit == null || analysisSummary == null) {
            return;
        }
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        String targetInstance = targetContext == null ? "" : ValidationSupport.normalise(targetContext.instanceName());
        if (targetInstance.isBlank()) {
            return;
        }
        Path targetPath = classInfo.getTargetPath();
        if (targetPath == null || !Files.isRegularFile(targetPath)) {
            return;
        }
        CompilationUnit existingUnit = parseExistingCompilationUnit(targetPath);
        if (existingUnit == null) {
            return;
        }
        List<MethodDeclaration> existingLifecycleRebindings = findLifecycleHelpersRebindingTarget(existingUnit, targetInstance);
        List<MethodDeclaration> incomingLifecycleRebindings = findIncomingLifecycleHelpersRebindingTarget(snippet, compilationUnit, targetInstance);
        if (incomingLifecycleRebindings.isEmpty()) {
            return;
        }
        if (existingLifecycleRebindings.isEmpty()) {
            if (!hasExistingTargetFixtureWithoutLifecycle(existingUnit, targetInstance)) {
                return;
            }
            LinkedHashSet<String> incomingHelpers = new LinkedHashSet<>();
            incomingLifecycleRebindings.forEach(method -> incomingHelpers.add(describeLifecycleHelper(method)));
            String message = "E112: existing generated test class already exposes reusable fixture wiring for \""
                    + targetInstance
                    + "\"; do not add a new lifecycle helper that reinitializes the class under test. incoming="
                    + incomingHelpers;
            logger.warn("⚠️  " + message);
            throw new InvalidLLMResponseException(message);
        }
        List<MethodDeclaration> conflictingIncomingHelpers = incomingLifecycleRebindings.stream()
                .filter(incoming -> existingLifecycleRebindings.stream().noneMatch(existing -> sameLifecycleHelper(existing, incoming, targetInstance)))
                .toList();
        if (conflictingIncomingHelpers.isEmpty()) {
            return;
        }
        LinkedHashSet<String> existingHelpers = new LinkedHashSet<>();
        existingLifecycleRebindings.forEach(method -> existingHelpers.add(describeLifecycleHelper(method)));
        LinkedHashSet<String> incomingHelpers = new LinkedHashSet<>();
        conflictingIncomingHelpers.forEach(method -> incomingHelpers.add(describeLifecycleHelper(method)));
        String message = "E112: existing generated test class already defines lifecycle setup for \""
                + targetInstance
                + "\"; do not add another lifecycle helper that reinitializes the class under test. existing="
                + existingHelpers
                + ", incoming="
                + incomingHelpers;
        logger.warn("⚠️  " + message);
        throw new InvalidLLMResponseException(message);
    }

    private boolean hasExistingTargetFixtureWithoutLifecycle(CompilationUnit existingUnit,
                                                             String targetInstance) {
        if (existingUnit == null || targetInstance == null || targetInstance.isBlank()) {
            return false;
        }
        ClassOrInterfaceDeclaration declaration = existingUnit.getPrimaryType()
                .flatMap(type -> type.toClassOrInterfaceDeclaration())
                .orElseGet(() -> existingUnit.findFirst(ClassOrInterfaceDeclaration.class).orElse(null));
        if (declaration == null) {
            return false;
        }
        boolean hasTargetField = declaration.getFields().stream()
                .flatMap(field -> field.getVariables().stream())
                .map(VariableDeclarator::getNameAsString)
                .anyMatch(targetInstance::equals);
        if (!hasTargetField) {
            return false;
        }
        return declaration.getMethods().stream().anyMatch(this::hasTestAnnotation);
    }

    void ensureGeneratedTestInvokesTargetMethod(CompilationUnit compilationUnit,
                                                Analyze.AnalysisSummary analysisSummary,
                                                TestMethodInfo methodInfo,
                                                String fullSource) {
        if (compilationUnit == null || analysisSummary == null || analysisSummary.testTargetContext() == null || methodInfo == null) {
            return;
        }
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        String expectedMethodName = analysisSummary.methodAnalysis() != null
                && analysisSummary.methodAnalysis().method() != null
                ? analysisSummary.methodAnalysis().method().name()
                : ValidationSupport.extractMethodName(methodInfo.getSignature());
        if (expectedMethodName == null || expectedMethodName.isBlank()) {
            return;
        }
        boolean invokesTarget = compilationUnit.findAll(MethodCallExpr.class).stream()
                .anyMatch(expr -> callsExpectedTarget(expr, targetContext, expectedMethodName))
                || compilationUnit.findAll(MethodReferenceExpr.class).stream()
                .anyMatch(expr -> referencesExpectedTarget(expr, targetContext, expectedMethodName));
        if (invokesTarget) {
            return;
        }
        String message = "E108: generated test does not invoke target method \""
                + expectedMethodName
                + "\" on the class under test. Placeholder or tautological tests are invalid.";
        if (fullSource != null && (fullSource.contains("TODO") || fullSource.contains("assertTrue(true)") || fullSource.contains("assertFalse(false)"))) {
            message += " Detected placeholder content in generated source.";
        }
        logger.warn("⚠️  " + message);
        throw new InvalidLLMResponseException(message);
    }

    private CompilationUnit parseExistingCompilationUnit(Path targetPath) {
        try {
            String source = Files.readString(targetPath, StandardCharsets.UTF_8);
            if (source.isBlank()) {
                return null;
            }
            return StaticJavaParser.parse(source);
        } catch (IOException exception) {
            logger.warn("Unable to read existing generated test class for lifecycle validation: "
                    + targetPath + " -> " + exception.getMessage());
            return null;
        } catch (ParseProblemException exception) {
            logger.warn("Unable to parse existing generated test class for lifecycle validation: "
                    + targetPath + " -> " + exception.getMessage());
            return null;
        }
    }

    private boolean isResolvableFromBuildClasspath(Path projectRoot, Path testClassFile, String importedFqcn) {
        if (classpathTypeResolver == null
                || projectRoot == null
                || testClassFile == null
                || importedFqcn == null
                || importedFqcn.isBlank()) {
            return false;
        }
        try {
            boolean resolvable = classpathTypeResolver.isResolvable(projectRoot, testClassFile, importedFqcn);
            if (resolvable) {
                logger.info("Skipping project-import correction for " + importedFqcn
                        + " because it resolves from the Gradle test runtime classpath.");
            }
            return resolvable;
        } catch (Exception exception) {
            logger.warn("Unable to verify import " + importedFqcn + " against Gradle classpath: "
                    + exception.getMessage());
            return false;
        }
    }

    private boolean looksLikeWrongProjectPackage(String importedFqcn, String candidateFqcn) {
        String importedPackage = packageName(importedFqcn);
        String candidatePackage = packageName(candidateFqcn);
        if (importedPackage.isBlank() || candidatePackage.isBlank()) {
            return false;
        }
        List<String> importedParts = splitPackage(importedPackage);
        List<String> candidateParts = splitPackage(candidatePackage);
        int shared = 0;
        int max = Math.min(importedParts.size(), candidateParts.size());
        while (shared < max && importedParts.get(shared).equals(candidateParts.get(shared))) {
            shared++;
        }
        return shared >= 2;
    }

    private String packageName(String fqcn) {
        String value = ValidationSupport.normalise(fqcn);
        int lastDot = value.lastIndexOf('.');
        if (lastDot <= 0) {
            return "";
        }
        return value.substring(0, lastDot);
    }

    private List<String> splitPackage(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(packageName.split("\\."))
                .filter(part -> !part.isBlank())
                .toList();
    }

    private List<MethodDeclaration> findIncomingLifecycleHelpersRebindingTarget(GeneratedTestSnippet snippet,
                                                                                CompilationUnit compilationUnit,
                                                                                String targetInstance) {
        List<MethodDeclaration> helperMethods = new ArrayList<>();
        if (snippet.helperMethods() != null && !snippet.helperMethods().isEmpty()) {
            for (String helperSource : snippet.helperMethods()) {
                if (helperSource == null || helperSource.isBlank()) {
                    continue;
                }
                try {
                    BodyDeclaration<?> declaration = StaticJavaParser.parseBodyDeclaration(helperSource);
                    if (declaration.isMethodDeclaration()) {
                        helperMethods.add(declaration.asMethodDeclaration());
                    }
                } catch (ParseProblemException exception) {
                    logger.warn("Unable to parse generated helper method for lifecycle validation: "
                            + exception.getMessage());
                }
            }
        }
        if (helperMethods.isEmpty()) {
            helperMethods.addAll(compilationUnit.findAll(MethodDeclaration.class));
        }
        return helperMethods.stream()
                .filter(this::isLifecycleHelper)
                .filter(method -> rebindsTargetInstance(method, targetInstance))
                .toList();
    }

    private List<MethodDeclaration> findLifecycleHelpersRebindingTarget(CompilationUnit compilationUnit,
                                                                        String targetInstance) {
        return compilationUnit.findAll(MethodDeclaration.class).stream()
                .filter(this::isLifecycleHelper)
                .filter(method -> rebindsTargetInstance(method, targetInstance))
                .toList();
    }

    private boolean rebindsTargetInstance(MethodDeclaration method, String targetInstance) {
        if (method == null || targetInstance == null || targetInstance.isBlank()) {
            return false;
        }
        boolean assignsField = method.findAll(AssignExpr.class).stream()
                .map(AssignExpr::getTarget)
                .anyMatch(target -> isTargetInstanceReference(target, targetInstance));
        if (assignsField) {
            return true;
        }
        return method.findAll(VariableDeclarator.class).stream()
                .map(VariableDeclarator::getNameAsString)
                .anyMatch(targetInstance::equals);
    }

    private boolean isTargetInstanceReference(Expression expression, String targetInstance) {
        if (expression == null || targetInstance == null || targetInstance.isBlank()) {
            return false;
        }
        if (expression instanceof NameExpr nameExpr) {
            return targetInstance.equals(nameExpr.getNameAsString());
        }
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            if (!targetInstance.equals(fieldAccessExpr.getNameAsString())) {
                return false;
            }
            return fieldAccessExpr.getScope() instanceof ThisExpr || fieldAccessExpr.getScope() instanceof NameExpr;
        }
        return false;
    }

    private boolean isLifecycleHelper(MethodDeclaration method) {
        if (method == null) {
            return false;
        }
        return method.getAnnotationByName("BeforeEach").isPresent()
                || method.getAnnotationByName("BeforeAll").isPresent()
                || method.getAnnotationByName("AfterEach").isPresent()
                || method.getAnnotationByName("AfterAll").isPresent();
    }

    private boolean hasTestAnnotation(MethodDeclaration method) {
        if (method == null) {
            return false;
        }
        return method.getAnnotationByName("Test").isPresent();
    }

    private String describeLifecycleHelper(MethodDeclaration method) {
        if (method == null) {
            return "unknown";
        }
        List<String> lifecycleAnnotations = method.getAnnotations().stream()
                .map(annotation -> annotation.getName().asString())
                .filter(name -> "BeforeEach".equals(name)
                        || "BeforeAll".equals(name)
                        || "AfterEach".equals(name)
                        || "AfterAll".equals(name))
                .toList();
        String prefix = lifecycleAnnotations.isEmpty() ? "@Lifecycle" : '@' + String.join("+", lifecycleAnnotations);
        return prefix + ' ' + method.getNameAsString() + "()";
    }

    private boolean sameLifecycleHelper(MethodDeclaration existing,
                                        MethodDeclaration incoming,
                                        String targetInstance) {
        if (existing == null || incoming == null) {
            return false;
        }
        if (!lifecycleAnnotationKey(existing).equals(lifecycleAnnotationKey(incoming))) {
            return false;
        }
        LinkedHashSet<String> existingBindings = extractTargetBindings(existing, targetInstance);
        LinkedHashSet<String> incomingBindings = extractTargetBindings(incoming, targetInstance);
        if (!existingBindings.isEmpty() || !incomingBindings.isEmpty()) {
            return !existingBindings.isEmpty() && existingBindings.equals(incomingBindings);
        }
        return normaliseMethodBody(existing).equals(normaliseMethodBody(incoming));
    }

    private LinkedHashSet<String> extractTargetBindings(MethodDeclaration method, String targetInstance) {
        LinkedHashSet<String> bindings = new LinkedHashSet<>();
        if (method == null || targetInstance == null || targetInstance.isBlank()) {
            return bindings;
        }
        method.findAll(AssignExpr.class).stream()
                .filter(assignExpr -> isTargetInstanceReference(assignExpr.getTarget(), targetInstance))
                .map(AssignExpr::getValue)
                .map(this::normaliseBindingExpression)
                .filter(binding -> !binding.isBlank())
                .forEach(bindings::add);
        method.findAll(VariableDeclarator.class).stream()
                .filter(variable -> targetInstance.equals(variable.getNameAsString()))
                .map(VariableDeclarator::getInitializer)
                .flatMap(Optional::stream)
                .map(this::normaliseBindingExpression)
                .filter(binding -> !binding.isBlank())
                .forEach(bindings::add);
        return bindings;
    }

    private String normaliseBindingExpression(Expression expression) {
        if (expression == null) {
            return "";
        }
        return expression.toString()
                .replace("this.", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normaliseMethodBody(MethodDeclaration method) {
        if (method == null) {
            return "";
        }
        String source = method.getBody().map(Object::toString).orElse("");
        source = source.replaceAll("(?s)/\\*.*?\\*/", " ");
        source = source.replaceAll("(?m)//.*$", " ");
        return source.replaceAll("\\s+", " ").trim();
    }

    private String lifecycleAnnotationKey(MethodDeclaration method) {
        if (method == null) {
            return "";
        }
        return method.getAnnotations().stream()
                .map(annotation -> annotation.getName().asString())
                .filter(name -> "BeforeEach".equals(name)
                        || "BeforeAll".equals(name)
                        || "AfterEach".equals(name)
                        || "AfterAll".equals(name))
                .sorted()
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
    }

    private List<MockAwareConstructorExpectation> buildMockAwareConstructorExpectations(Analyze.AnalysisSummary analysisSummary) {
        if (analysisSummary == null || analysisSummary.testTargetContext() == null || analysisSummary.mockPlan() == null) {
            return List.of();
        }
        if (analysisSummary.mockPlan().shouldMock().isEmpty()) {
            return List.of();
        }
        String targetClass = ValidationSupport.simpleName(analysisSummary.testTargetContext().className());
        if (targetClass.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> mockNames = analysisSummary.mockPlan().shouldMock().stream()
                .map(ValidationSupport::normalise)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<MockAwareConstructorExpectation> expectations = new java.util.ArrayList<>();
        for (Map.Entry<String, List<ConstructorMetadata>> entry : analysisSummary.availableConstructors().entrySet()) {
            if (!targetClass.equals(ValidationSupport.simpleName(entry.getKey()))) {
                continue;
            }
            for (ConstructorMetadata metadata : entry.getValue()) {
                if (metadata == null || metadata.parameters() == null || metadata.parameters().isEmpty()) {
                    continue;
                }
                LinkedHashMap<Integer, String> bindings = new LinkedHashMap<>();
                for (int index = 0; index < metadata.parameters().size(); index++) {
                    ParameterMetadata parameter = metadata.parameters().get(index);
                    if (parameter == null) {
                        continue;
                    }
                    String parameterName = ValidationSupport.normalise(parameter.name());
                    String lowerCamelType = ValidationSupport.lowerCamel(ValidationSupport.simpleName(parameter.type()));
                    if (mockNames.contains(parameterName)) {
                        bindings.put(index, parameterName);
                    } else if (!lowerCamelType.isBlank() && mockNames.contains(lowerCamelType)) {
                        bindings.put(index, lowerCamelType);
                    }
                }
                if (!bindings.isEmpty()) {
                    expectations.add(new MockAwareConstructorExpectation(metadata.signature(), metadata.parameters().size(), bindings));
                }
            }
        }
        expectations.sort((left, right) -> {
            int bindingCompare = Integer.compare(right.bindings().size(), left.bindings().size());
            if (bindingCompare != 0) {
                return bindingCompare;
            }
            return Integer.compare(right.argCount(), left.argCount());
        });
        return List.copyOf(expectations);
    }

    private List<RequiredConstructorExpectation> buildRequiredConstructorExpectations(Analyze.AnalysisSummary analysisSummary,
                                                                                      String targetClass,
                                                                                      Map<String, List<TargetConstructorPolicyResolver.RequiredConstructorArgument>> requiredArgsBySignature) {
        if (analysisSummary == null || analysisSummary.availableConstructors() == null || requiredArgsBySignature == null || requiredArgsBySignature.isEmpty()) {
            return List.of();
        }
        List<RequiredConstructorExpectation> expectations = new java.util.ArrayList<>();
        for (Map.Entry<String, List<ConstructorMetadata>> entry : analysisSummary.availableConstructors().entrySet()) {
            if (!targetClass.equals(ValidationSupport.simpleName(entry.getKey()))) {
                continue;
            }
            for (ConstructorMetadata metadata : entry.getValue()) {
                List<TargetConstructorPolicyResolver.RequiredConstructorArgument> requiredArgs =
                        requiredArgsBySignature.getOrDefault(metadata.signature(), List.of());
                if (requiredArgs.isEmpty()) {
                    continue;
                }
                expectations.add(new RequiredConstructorExpectation(
                        metadata.signature(),
                        metadata.parameters().size(),
                        requiredArgs));
            }
        }
        return List.copyOf(expectations);
    }

    private List<String> missingRequiredArguments(ObjectCreationExpr creation,
                                                  RequiredConstructorExpectation expectation) {
        List<String> missingArgs = new java.util.ArrayList<>();
        for (TargetConstructorPolicyResolver.RequiredConstructorArgument argument : expectation.requiredArguments()) {
            int zeroBasedPosition = argument.position() - 1;
            if (zeroBasedPosition < 0 || zeroBasedPosition >= creation.getArguments().size()) {
                continue;
            }
            if (creation.getArgument(zeroBasedPosition) instanceof NullLiteralExpr) {
                missingArgs.add("#" + argument.position() + " " + argument.parameterName());
            }
        }
        return List.copyOf(missingArgs);
    }

    private boolean callsExpectedTarget(MethodCallExpr expression,
                                        Analyze.TestTargetContext targetContext,
                                        String expectedMethodName) {
        if (expression == null || targetContext == null || expectedMethodName == null) {
            return false;
        }
        if (!expectedMethodName.equals(expression.getNameAsString())) {
            return false;
        }
        Optional<Expression> scope = expression.getScope();
        String targetClass = ValidationSupport.simpleName(targetContext.className());
        if (targetContext.isStatic()) {
            return scope
                    .filter(NameExpr.class::isInstance)
                    .map(NameExpr.class::cast)
                    .map(NameExpr::getNameAsString)
                    .map(ValidationSupport::simpleName)
                    .filter(name -> targetClass.equals(name))
                    .isPresent();
        }
        if (scope.isEmpty()) {
            return false;
        }
        Expression scopedExpression = scope.get();
        if (scopedExpression instanceof NameExpr nameExpr) {
            return ValidationSupport.normalise(targetContext.instanceName()).equals(ValidationSupport.normalise(nameExpr.getNameAsString()));
        }
        if (scopedExpression instanceof FieldAccessExpr fieldAccessExpr && fieldAccessExpr.getScope() instanceof ThisExpr) {
            return ValidationSupport.normalise(targetContext.instanceName()).equals(ValidationSupport.normalise(fieldAccessExpr.getNameAsString()));
        }
        if (scopedExpression instanceof ObjectCreationExpr objectCreationExpr) {
            return targetClass.equals(ValidationSupport.simpleName(objectCreationExpr.getType().asString()));
        }
        return false;
    }

    private boolean referencesExpectedTarget(MethodReferenceExpr expression,
                                             Analyze.TestTargetContext targetContext,
                                             String expectedMethodName) {
        if (expression == null || targetContext == null || expectedMethodName == null) {
            return false;
        }
        if (!expectedMethodName.equals(expression.getIdentifier())) {
            return false;
        }
        Expression scopedExpression = expression.getScope();
        String targetClass = ValidationSupport.simpleName(targetContext.className());
        if (targetContext.isStatic()) {
            return targetClass.equals(ValidationSupport.simpleName(scopedExpression.toString()));
        }
        if (scopedExpression instanceof TypeExpr typeExpr) {
            return ValidationSupport.normalise(targetContext.instanceName()).equals(ValidationSupport.normalise(typeExpr.getType().asString()));
        }
        if (scopedExpression instanceof NameExpr nameExpr) {
            return ValidationSupport.normalise(targetContext.instanceName()).equals(ValidationSupport.normalise(nameExpr.getNameAsString()));
        }
        if (scopedExpression instanceof FieldAccessExpr fieldAccessExpr && fieldAccessExpr.getScope() instanceof ThisExpr) {
            return ValidationSupport.normalise(targetContext.instanceName()).equals(ValidationSupport.normalise(fieldAccessExpr.getNameAsString()));
        }
        if (scopedExpression instanceof ObjectCreationExpr objectCreationExpr) {
            return targetClass.equals(ValidationSupport.simpleName(objectCreationExpr.getType().asString()));
        }
        return false;
    }

    private boolean shouldIgnoreImportValidation(String fqcn) {
        String candidate = ValidationSupport.normalise(fqcn);
        return candidate.startsWith("java.")
                || candidate.startsWith("javax.")
                || candidate.startsWith("jakarta.")
                || candidate.startsWith("org.junit.")
                || candidate.startsWith("org.mockito.")
                || candidate.startsWith("org.assertj.")
                || candidate.startsWith("com.github.")
                || candidate.startsWith("chat.giga.")
                || candidate.startsWith("org.json.")
                || candidate.startsWith("com.fasterxml.");
    }

    private List<String> findProjectTypesBySimpleName(Path mainSourceRoot, String simpleName) {
        if (mainSourceRoot == null || simpleName == null || simpleName.isBlank()) {
            return List.of();
        }
        try (var stream = Files.walk(mainSourceRoot)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(simpleName + ".java"))
                    .map(path -> mainSourceRoot.relativize(path).toString().replace('\\', '/'))
                    .map(relative -> relative.substring(0, relative.length() - ".java".length()).replace('/', '.'))
                    .sorted()
                    .toList();
        } catch (Exception exception) {
            return List.of();
        }
    }

    @FunctionalInterface
    interface ClasspathTypeResolver {
        boolean isResolvable(Path projectRoot, Path testClassFile, String fqcn);
    }

    private static final class GradleClasspathTypeResolver implements ClasspathTypeResolver {
        private final TestRuntimeClasspathResolver resolver;
        private final ConcurrentMap<String, Boolean> cache = new ConcurrentHashMap<>();

        private GradleClasspathTypeResolver(PipelineLogger logger) {
            this.resolver = new TestRuntimeClasspathResolver(logger);
        }

        @Override
        public boolean isResolvable(Path projectRoot, Path testClassFile, String fqcn) {
            if (projectRoot == null || testClassFile == null || fqcn == null || fqcn.isBlank()) {
                return false;
            }
            String key = projectRoot.toAbsolutePath().normalize()
                    + "|" + testClassFile.toAbsolutePath().normalize()
                    + "|" + fqcn;
            return cache.computeIfAbsent(key, ignored -> resolve(projectRoot, testClassFile, fqcn));
        }

        private boolean resolve(Path projectRoot, Path testClassFile, String fqcn) {
            ResolvedTestRuntimeClasspath classpath = resolver.resolve(projectRoot, testClassFile);
            if (classpath.entries().isEmpty()) {
                return false;
            }
            List<String> resources = classResourceCandidates(fqcn);
            for (Path entry : classpath.entries()) {
                if (containsAnyResource(entry, resources)) {
                    return true;
                }
            }
            return false;
        }

        private boolean containsAnyResource(Path entry, List<String> resources) {
            if (entry == null || resources == null || resources.isEmpty() || !Files.exists(entry)) {
                return false;
            }
            if (Files.isDirectory(entry)) {
                return resources.stream().anyMatch(resource -> Files.isRegularFile(entry.resolve(resource)));
            }
            String fileName = entry.getFileName() == null ? "" : entry.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (!fileName.endsWith(".jar")) {
                return false;
            }
            try (JarFile jar = new JarFile(entry.toFile())) {
                return resources.stream().anyMatch(resource -> jar.getJarEntry(resource) != null);
            } catch (IOException ignored) {
                return false;
            }
        }

        private List<String> classResourceCandidates(String fqcn) {
            String slashPath = fqcn.replace('.', '/');
            LinkedHashSet<String> resources = new LinkedHashSet<>();
            resources.add(slashPath + ".class");
            int slash = slashPath.lastIndexOf('/');
            while (slash > 0) {
                String nested = slashPath.substring(0, slash)
                        + "$"
                        + slashPath.substring(slash + 1).replace('/', '$')
                        + ".class";
                resources.add(nested);
                slash = slashPath.lastIndexOf('/', slash - 1);
            }
            return List.copyOf(resources);
        }
    }

    private boolean matchesMockAwareConstruction(ObjectCreationExpr expression,
                                                 MockAwareConstructorExpectation expectation) {
        if (expression == null || expectation == null) {
            return false;
        }
        if (expression.getArguments().size() != expectation.argCount()) {
            return false;
        }
        for (Map.Entry<Integer, String> binding : expectation.bindings().entrySet()) {
            int index = binding.getKey();
            if (index < 0 || index >= expression.getArguments().size()) {
                return false;
            }
            if (!referencesVariable(expression.getArgument(index), binding.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean referencesVariable(Expression expression, String variableName) {
        String expected = ValidationSupport.normalise(variableName);
        if (expression == null || expected.isBlank()) {
            return false;
        }
        if (expression.findFirst(NameExpr.class, nameExpr -> expected.equals(ValidationSupport.normalise(nameExpr.getNameAsString()))).isPresent()) {
            return true;
        }
        return expression.findFirst(FieldAccessExpr.class, fieldAccessExpr ->
                expected.equals(ValidationSupport.normalise(fieldAccessExpr.getNameAsString()))
                        && fieldAccessExpr.getScope() instanceof ThisExpr).isPresent();
    }

    private record MockAwareConstructorExpectation(String signature,
                                                   int argCount,
                                                   Map<Integer, String> bindings) {

        private MockAwareConstructorExpectation {
            bindings = Map.copyOf(bindings);
        }

        private List<String> mockedCollaboratorNames() {
            return bindings.values().stream().distinct().toList();
        }

        private String bindingSummary() {
            return bindings.entrySet().stream()
                    .map(entry -> "#" + (entry.getKey() + 1) + " -> " + entry.getValue())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
        }
    }

    private record RequiredConstructorExpectation(String signature,
                                                  int argCount,
                                                  List<TargetConstructorPolicyResolver.RequiredConstructorArgument> requiredArguments) {

        private RequiredConstructorExpectation {
            requiredArguments = List.copyOf(requiredArguments);
        }
    }
}
