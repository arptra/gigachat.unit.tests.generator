package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.compile.CompilerInvoker;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.execute.ExecutionInvoker;
import com.gigachat.unit.tests.generator.reasoning.service.SourceFileEditor;
import com.gigachat.unit.tests.generator.reasoning.service.SymbolLookupService;
import com.gigachat.unit.tests.generator.resources.SiblingIsolationPolicy;
import com.gigachat.unit.tests.generator.resources.SiblingIsolationPolicyCatalog;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Validates a generated snippet in an isolated scratch test class before the snippet is merged into
 * the accumulated generated test class.
 */
public class PreMergeScratchValidator {

    private static final String TEST_IMPORT = "org.junit.jupiter.api.Test";

    private final PipelineLogger logger;
    private final CompilerInvoker compilerInvoker;
    private final ExecutionInvoker executionInvoker;
    private final SiblingIsolationPolicy policy;
    private final TestClassWriter testClassWriter;

    public PreMergeScratchValidator(PipelineLogger logger,
                                    CompilerInvoker compilerInvoker,
                                    ExecutionInvoker executionInvoker) {
        this(logger, compilerInvoker, executionInvoker, new SiblingIsolationPolicyCatalog().policy());
    }

    public PreMergeScratchValidator(PipelineLogger logger,
                                    CompilerInvoker compilerInvoker,
                                    ExecutionInvoker executionInvoker,
                                    SiblingIsolationPolicy policy) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.compilerInvoker = Objects.requireNonNull(compilerInvoker, "compilerInvoker");
        this.executionInvoker = Objects.requireNonNull(executionInvoker, "executionInvoker");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.testClassWriter = new TestClassWriter(logger);
    }

    public ValidationResult validate(Path projectRoot,
                                     TestClassInfo classInfo,
                                     GeneratedTestSnippet snippet,
                                     boolean compileEnabled,
                                     boolean executeEnabled) {
        return validate(projectRoot, classInfo, snippet, compileEnabled, executeEnabled, null, null);
    }

    public ValidationResult validate(Path projectRoot,
                                     TestClassInfo classInfo,
                                     GeneratedTestSnippet snippet,
                                     boolean compileEnabled,
                                     boolean executeEnabled,
                                     ScratchCompilationRepair compileRepair) {
        return validate(projectRoot, classInfo, snippet, compileEnabled, executeEnabled, compileRepair, null);
    }

    public ValidationResult validate(Path projectRoot,
                                     TestClassInfo classInfo,
                                     GeneratedTestSnippet snippet,
                                     boolean compileEnabled,
                                     boolean executeEnabled,
                                     ScratchCompilationRepair compileRepair,
                                     ScratchExecutionRepair executionRepair) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(classInfo, "classInfo");
        Objects.requireNonNull(snippet, "snippet");

        if (!policy.enabled()) {
            return ValidationResult.passed();
        }

        boolean targetClassPopulated = isTargetClassAlreadyPopulated(classInfo.getTargetPath());
        boolean snippetContainsSiblingTests = shouldExecuteWholeScratchSuite(snippet);
        boolean shouldExecute = policy.executeBeforeMerge()
                && executeEnabled
                && (snippetContainsSiblingTests
                || !policy.executeOnlyWhenTargetClassAlreadyPopulated()
                || targetClassPopulated);
        boolean shouldCompile = (policy.compileBeforeMerge() && compileEnabled) || shouldExecute;
        if (!shouldCompile && !shouldExecute) {
            return ValidationResult.passed();
        }
        String scratchClassName = classInfo.getTestClassName() + policy.scratchClassSuffix();
        Path scratchFile = classInfo.getTargetPath().resolveSibling(scratchClassName + ".java");

        try {
            ScratchValidationPlan scratchPlan = buildScratchValidationPlan(classInfo,
                    snippet,
                    scratchClassName,
                    targetClassPopulated,
                    shouldExecute,
                    snippetContainsSiblingTests);
            GeneratedTestSnippet validatedSnippet = scratchPlan.preparedSnippet();
            String replacementSource = "";
            boolean replaceTargetClassSource = false;
            String replacementDiagnostic = scratchPlan.validatesMergedClass()
                    ? "PRE_VALIDATED_MERGED_SCRATCH_SOURCE"
                    : "PRE_VALIDATED_SCRATCH_SNIPPET";
            String scratchClassFqcn = resolveQualifiedClassName(classInfo.getTargetPath(), scratchClassName);
            Files.createDirectories(scratchFile.getParent());
            Files.writeString(scratchFile,
                    scratchPlan.scratchSource(),
                    StandardCharsets.UTF_8);
            enrichResolvableImports(projectRoot, scratchFile);
            logger.info("[SIBLING_ISOLATION] Validating " + snippet.methodName()
                    + " in isolated scratch class " + scratchClassName
                    + (scratchPlan.validatesMergedClass() ? " [merged-preview]" : ""));

            CompileResult compileResult = null;
            if (shouldCompile) {
                compileResult = compilerInvoker.compileWithoutCache(projectRoot, scratchFile, snippet.methodName());
                if (!compileResult.success()) {
                    logger.warn("[SIBLING_ISOLATION] Scratch compilation failed for "
                            + snippet.methodName()
                            + " in "
                            + scratchClassName
                            + ": "
                            + summariseCompileFailure(compileResult));
                    boolean repairAttempted = false;
                    if (compileRepair != null) {
                        repairAttempted = true;
                        ScratchRepairOutcome repairOutcome = compileRepair.repair(new ScratchCompilationFailure(
                                projectRoot,
                                classInfo,
                                scratchPlan.preparedSnippet(),
                                scratchFile,
                                scratchClassName,
                                scratchClassFqcn,
                                scratchPlan.effectiveMethodName(),
                                scratchPlan.validatesMergedClass(),
                                compileResult));
                        if (repairOutcome != null && repairOutcome.compileResult() != null) {
                            compileResult = repairOutcome.compileResult();
                        }
                        if (repairOutcome != null && repairOutcome.success() && compileResult != null && compileResult.success()) {
                            ScratchCarryover carryover = buildScratchCarryover(scratchFile,
                                    classInfo,
                                    scratchPlan,
                                    scratchClassName);
                            if (carryover != null) {
                                validatedSnippet = carryover.validatedSnippet();
                                replacementSource = carryover.replacementSource();
                                replaceTargetClassSource = carryover.replaceTargetClassSource();
                                replacementDiagnostic = carryover.replacementDiagnostic();
                            }
                        }
                    }
                    if (!compileResult.success()) {
                        logger.trace("ACTION",
                                snippet.methodName(),
                                "SCRATCH_COMPILATION_FAILED",
                                "action=RETURN_TO_GENERATION_RETRY compileReasoning="
                                        + (repairAttempted ? "FAILED_PRE_MERGE_SCRATCH_REPAIR" : "SKIPPED")
                                        + " reason=pre-merge scratch compilation failed");
                        return ValidationResult.failed("SCRATCH_COMPILATION_FAILED",
                                compileResult,
                                null,
                                validatedSnippet,
                                replacementSource,
                                replaceTargetClassSource,
                                replacementDiagnostic);
                    }
                }
            }

            String executionMethodName = scratchPlan.executeWholeSuite() ? null : validatedSnippet.methodName();
            if (shouldExecute) {
                ExecuteResult executeResult = executionInvoker.execute(projectRoot, scratchFile, executionMethodName);
                if (!executeResult.success()) {
                    logger.warn("[SIBLING_ISOLATION] Scratch execution failed for "
                            + scratchPlan.effectiveMethodName()
                            + " in "
                            + scratchClassName
                            + ": "
                            + summariseExecutionFailure(executeResult));
                    boolean repairAttempted = false;
                    if (executionRepair != null) {
                        repairAttempted = true;
                        ScratchExecutionRepairOutcome repairOutcome = executionRepair.repair(new ScratchExecutionFailure(
                                projectRoot,
                                classInfo,
                                scratchPlan.preparedSnippet(),
                                scratchFile,
                                scratchClassName,
                                scratchClassFqcn,
                                scratchPlan.effectiveMethodName(),
                                scratchPlan.validatesMergedClass(),
                                scratchPlan.executeWholeSuite(),
                                compileResult,
                                executeResult));
                        if (repairOutcome != null && repairOutcome.compileResult() != null) {
                            compileResult = repairOutcome.compileResult();
                        }
                        if (repairOutcome != null && repairOutcome.executeResult() != null) {
                            executeResult = repairOutcome.executeResult();
                        }
                        if (repairOutcome != null
                                && repairOutcome.success()
                                && (compileResult == null || compileResult.success())
                                && executeResult != null
                                && executeResult.success()) {
                            ScratchCarryover carryover = buildScratchCarryover(scratchFile,
                                    classInfo,
                                    scratchPlan,
                                    scratchClassName);
                            if (carryover != null) {
                                validatedSnippet = carryover.validatedSnippet();
                                replacementSource = carryover.replacementSource();
                                replaceTargetClassSource = carryover.replaceTargetClassSource();
                                replacementDiagnostic = carryover.replacementDiagnostic();
                            }
                        }
                    }
                    if (!executeResult.success()) {
                        logger.trace("ACTION",
                                snippet.methodName(),
                                "SCRATCH_EXECUTION_FAILED",
                                "action=RETURN_TO_GENERATION_RETRY executionReasoning="
                                        + (repairAttempted ? "FAILED_PRE_MERGE_SCRATCH_REPAIR" : "SKIPPED")
                                        + " reason=pre-merge scratch execution failed");
                        return ValidationResult.failed("SCRATCH_EXECUTION_FAILED",
                                compileResult,
                                executeResult,
                                validatedSnippet,
                                replacementSource,
                                replaceTargetClassSource,
                                replacementDiagnostic);
                    }
                }
                if (compileResult != null && !compileResult.success()) {
                    return ValidationResult.failed("SCRATCH_EXECUTION_FAILED",
                            compileResult,
                            executeResult,
                            validatedSnippet,
                            replacementSource,
                            replaceTargetClassSource,
                            replacementDiagnostic);
                }
                return ValidationResult.passed(validatedSnippet,
                        compileResult,
                        executeResult,
                        replacementSource,
                        replaceTargetClassSource,
                        replacementDiagnostic);
            }

            return ValidationResult.passed(validatedSnippet,
                    compileResult,
                    null,
                    replacementSource,
                    replaceTargetClassSource,
                    replacementDiagnostic);
        } catch (IOException exception) {
            String message = "Unable to write scratch validation class " + scratchFile + ": " + exception.getMessage();
            logger.warn("[SIBLING_ISOLATION] " + message);
            return ValidationResult.failed(message,
                    new CompileResult(false, List.of(message), "", exception.getMessage()),
                    null,
                    snippet,
                    "",
                    false,
                    "");
        } catch (RuntimeException exception) {
            String message = "Unable to prepare scratch validation class " + scratchFile + ": " + exception.getMessage();
            logger.warn("[SIBLING_ISOLATION] " + message);
            return ValidationResult.failed("SCRATCH_PREPARATION_FAILED",
                    new CompileResult(false, List.of(message), "", exception.toString()),
                    null,
                    snippet,
                    "",
                    false,
                    "");
        } finally {
            cleanupScratchArtifacts(classInfo.getTargetPath(), scratchFile, scratchClassName);
        }
    }

    private ScratchValidationPlan buildScratchValidationPlan(TestClassInfo classInfo,
                                                             GeneratedTestSnippet snippet,
                                                             String scratchClassName,
                                                             boolean targetClassPopulated,
                                                             boolean shouldExecute,
                                                             boolean snippetContainsSiblingTests) {
        if (targetClassPopulated && policy.validateAgainstMergedClassWhenTargetClassAlreadyPopulated()) {
            ScratchValidationPlan mergedPlan = buildMergedScratchValidationPlan(classInfo,
                    snippet,
                    scratchClassName,
                    shouldExecute);
            if (mergedPlan != null) {
                return mergedPlan;
            }
        }
        boolean executeWholeScratchSuite = shouldExecute && snippetContainsSiblingTests;
        return new ScratchValidationPlan(
                renderScratchSource(classInfo, snippet, scratchClassName),
                snippet,
                snippet.methodName(),
                executeWholeScratchSuite,
                false
        );
    }

    private ScratchValidationPlan buildMergedScratchValidationPlan(TestClassInfo classInfo,
                                                                   GeneratedTestSnippet snippet,
                                                                   String scratchClassName,
                                                                   boolean shouldExecute) {
        Path targetPath = classInfo.getTargetPath();
        if (targetPath == null || !Files.isRegularFile(targetPath)) {
            return null;
        }
        try {
            String originalSource = testClassWriter.readSource(targetPath);
            if (originalSource == null || originalSource.isBlank()) {
                return null;
            }
            String withStructure = testClassWriter.applyClassStructure(originalSource, snippet);
            String withImports = testClassWriter.ensureImports(withStructure, snippet.imports());
            TestClassWriter.AppendResult appendResult = testClassWriter.appendMethod(withImports, snippet);
            String scratchSource = rewriteExistingClassSource(appendResult.source(), classInfo, scratchClassName);
            if (scratchSource.isBlank()) {
                return null;
            }
            boolean executeWholeSuite = shouldExecute && countTestMethods(scratchSource) > 1;
            return new ScratchValidationPlan(
                    scratchSource,
                    appendResult.mergedSnippet(),
                    appendResult.mergedSnippet().methodName(),
                    executeWholeSuite,
                    true
            );
        } catch (IllegalStateException exception) {
            logger.warn("[SIBLING_ISOLATION] Unable to build merged scratch preview: " + exception.getMessage());
            return null;
        }
    }

    private boolean shouldExecuteWholeScratchSuite(GeneratedTestSnippet snippet) {
        if (snippet == null || !policy.executeWholeScratchSuiteWhenSnippetContainsSiblingTests()) {
            return false;
        }
        if (snippet.helperMethods() != null && snippet.helperMethods().stream().anyMatch(this::containsTestAnnotation)) {
            return true;
        }
        String fullClassSource = snippet.fullClassSource();
        if (fullClassSource == null || fullClassSource.isBlank()) {
            return false;
        }
        try {
            CompilationUnit unit = StaticJavaParser.parse(JavaImportSanitizer.sanitizeSourceImports(fullClassSource));
            ClassOrInterfaceDeclaration declaration = locateClass(unit, snippet.className());
            if (declaration == null) {
                return false;
            }
            long annotatedTests = declaration.getMethods().stream().filter(this::hasTestAnnotation).count();
            return annotatedTests > 1;
        } catch (ParseProblemException exception) {
            return countOccurrences(fullClassSource, "@Test") > 1;
        }
    }

    private String renderScratchSource(TestClassInfo classInfo,
                                       GeneratedTestSnippet snippet,
                                       String scratchClassName) {
        String fullClassSource = snippet.fullClassSource();
        if (fullClassSource != null && !fullClassSource.isBlank()) {
            String rewritten = rewriteFullClassSource(classInfo, snippet, scratchClassName);
            if (!rewritten.isBlank()) {
                return rewritten;
            }
        }
        return synthesizeScratchSource(classInfo, snippet, scratchClassName);
    }

    private String rewriteFullClassSource(TestClassInfo classInfo,
                                          GeneratedTestSnippet snippet,
                                          String scratchClassName) {
        try {
            CompilationUnit unit = StaticJavaParser.parse(JavaImportSanitizer.sanitizeSourceImports(snippet.fullClassSource()));
            ClassOrInterfaceDeclaration declaration = locateClass(unit, snippet.className());
            if (declaration == null) {
                return "";
            }
            declaration.setName(scratchClassName);
            String packageName = determinePackage(classInfo.getTargetPath());
            if (!packageName.isBlank() && unit.getPackageDeclaration().isEmpty()) {
                unit.setPackageDeclaration(packageName);
            }
            ensureImports(unit, snippet.imports());
            ensureTestAnnotationImport(unit);
            ensureGeneratedMethodAnnotated(unit, snippet.methodName());
            return unit.toString();
        } catch (ParseProblemException exception) {
            logger.warn("[SIBLING_ISOLATION] Unable to parse fullClassSource for scratch validation: "
                    + exception.getMessage());
            return "";
        }
    }

    private String rewriteExistingClassSource(String source,
                                              TestClassInfo classInfo,
                                              String scratchClassName) {
        try {
            CompilationUnit unit = StaticJavaParser.parse(JavaImportSanitizer.sanitizeSourceImports(source));
            ClassOrInterfaceDeclaration declaration = locateClass(unit, classInfo.getTestClassName());
            if (declaration == null) {
                return "";
            }
            declaration.setName(scratchClassName);
            String packageName = determinePackage(classInfo.getTargetPath());
            if (!packageName.isBlank() && unit.getPackageDeclaration().isEmpty()) {
                unit.setPackageDeclaration(packageName);
            }
            ensureTestAnnotationImport(unit);
            return unit.toString();
        } catch (ParseProblemException exception) {
            logger.warn("[SIBLING_ISOLATION] Unable to rewrite merged class source for scratch validation: "
                    + exception.getMessage());
            return "";
        }
    }

    private ScratchCarryover buildScratchCarryover(Path scratchFile,
                                                   TestClassInfo classInfo,
                                                   ScratchValidationPlan scratchPlan,
                                                   String scratchClassName) {
        try {
            String scratchSource = Files.readString(scratchFile, StandardCharsets.UTF_8);
            String rewrittenSource = rewriteScratchSourceClassName(scratchSource,
                    classInfo,
                    scratchClassName);
            if (scratchPlan.validatesMergedClass()) {
                return new ScratchCarryover(scratchPlan.preparedSnippet(),
                        rewrittenSource,
                        true,
                        "PRE_VALIDATED_MERGED_SCRATCH_SOURCE");
            }
            return extractSnippetFromClassSource(rewrittenSource,
                    classInfo.getTestClassName(),
                    scratchPlan.preparedSnippet().methodName())
                    .map(snippet -> new ScratchCarryover(snippet, "", false, "PRE_VALIDATED_SCRATCH_SNIPPET"))
                    .orElseGet(() -> new ScratchCarryover(
                            new GeneratedTestSnippet(classInfo.getTestClassName(),
                                    scratchPlan.preparedSnippet().methodName(),
                                    scratchPlan.preparedSnippet().methodBody(),
                                    scratchPlan.preparedSnippet().imports(),
                                    scratchPlan.preparedSnippet().classAnnotations(),
                                    scratchPlan.preparedSnippet().fieldDeclarations(),
                                    scratchPlan.preparedSnippet().helperMethods(),
                                    rewrittenSource),
                            "",
                            false,
                            "PRE_VALIDATED_SCRATCH_FULL_SOURCE"));
        } catch (IOException exception) {
            logger.warn("[SIBLING_ISOLATION] Unable to capture repaired scratch source: " + exception.getMessage());
            return null;
        }
    }

    private String rewriteScratchSourceClassName(String source,
                                                 TestClassInfo classInfo,
                                                 String scratchClassName) {
        if (source == null || source.isBlank()) {
            return "";
        }
        try {
            CompilationUnit unit = StaticJavaParser.parse(JavaImportSanitizer.sanitizeSourceImports(source));
            ClassOrInterfaceDeclaration declaration = locateClass(unit, scratchClassName);
            if (declaration != null) {
                declaration.setName(classInfo.getTestClassName());
            }
            String packageName = determinePackage(classInfo.getTargetPath());
            if (!packageName.isBlank() && unit.getPackageDeclaration().isEmpty()) {
                unit.setPackageDeclaration(packageName);
            }
            return normaliseLineEndings(unit.toString());
        } catch (ParseProblemException exception) {
            return normaliseLineEndings(source.replace("class " + scratchClassName,
                    "class " + classInfo.getTestClassName()));
        }
    }

    private Optional<GeneratedTestSnippet> extractSnippetFromClassSource(String source,
                                                                         String expectedClassName,
                                                                         String preferredMethodName) {
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }
        try {
            CompilationUnit unit = StaticJavaParser.parse(JavaImportSanitizer.sanitizeSourceImports(source));
            ClassOrInterfaceDeclaration classDeclaration = locateClass(unit, expectedClassName);
            if (classDeclaration == null) {
                return Optional.empty();
            }
            MethodDeclaration method = locatePreferredMethod(classDeclaration, preferredMethodName);
            if (method == null) {
                return Optional.empty();
            }
            MethodDeclaration copy = method.clone();
            ensureTestAnnotation(copy);
            List<String> imports = new ArrayList<>();
            for (ImportDeclaration declaration : unit.getImports()) {
                String line = declaration.toString().trim();
                if (!line.isEmpty()) {
                    imports.add(line);
                }
            }
            imports = new ArrayList<>(JavaImportSanitizer.sanitizeImports(imports));
            return Optional.of(new GeneratedTestSnippet(expectedClassName,
                    copy.getNameAsString(),
                    normaliseLineEndings(copy.toString()),
                    imports,
                    collectClassAnnotations(classDeclaration),
                    collectFieldDeclarations(classDeclaration),
                    collectHelperMethods(classDeclaration, method),
                    normaliseLineEndings(JavaImportSanitizer.sanitizeSourceImports(unit.toString()))));
        } catch (ParseProblemException exception) {
            logger.warn("[SIBLING_ISOLATION] Unable to extract repaired snippet from scratch source: "
                    + exception.getMessage());
            return Optional.empty();
        }
    }

    private MethodDeclaration locatePreferredMethod(ClassOrInterfaceDeclaration declaration, String preferredMethodName) {
        if (declaration == null) {
            return null;
        }
        if (preferredMethodName != null && !preferredMethodName.isBlank()) {
            MethodDeclaration preferred = declaration.getMethodsByName(preferredMethodName).stream()
                    .findFirst()
                    .orElse(null);
            if (preferred != null) {
                return preferred;
            }
        }
        return declaration.getMethods().stream()
                .filter(this::hasTestAnnotation)
                .findFirst()
                .orElseGet(() -> declaration.getMethods().stream().findFirst().orElse(null));
    }

    private List<String> collectClassAnnotations(ClassOrInterfaceDeclaration declaration) {
        List<String> annotations = new ArrayList<>();
        declaration.getAnnotations().forEach(annotation -> {
            String value = normaliseLineEndings(annotation.toString()).trim();
            if (!value.isEmpty() && !annotations.contains(value)) {
                annotations.add(value);
            }
        });
        return annotations;
    }

    private List<String> collectFieldDeclarations(ClassOrInterfaceDeclaration declaration) {
        List<String> fields = new ArrayList<>();
        for (BodyDeclaration<?> member : declaration.getMembers()) {
            if (!member.isFieldDeclaration()) {
                continue;
            }
            String value = normaliseLineEndings(member.asFieldDeclaration().toString()).trim();
            if (!value.isEmpty() && !fields.contains(value)) {
                fields.add(value);
            }
        }
        return fields;
    }

    private List<String> collectHelperMethods(ClassOrInterfaceDeclaration declaration, MethodDeclaration primaryMethod) {
        List<String> helpers = new ArrayList<>();
        for (MethodDeclaration candidate : declaration.getMethods()) {
            if (candidate.equals(primaryMethod)) {
                continue;
            }
            String value = normaliseLineEndings(candidate.toString()).trim();
            if (!value.isEmpty() && !helpers.contains(value)) {
                helpers.add(value);
            }
        }
        return helpers;
    }

    private void ensureGeneratedMethodAnnotated(CompilationUnit unit, String methodName) {
        ClassOrInterfaceDeclaration declaration = locateClass(unit, null);
        if (declaration == null) {
            return;
        }
        declaration.getMethods().stream()
                .filter(method -> method.getNameAsString().equals(methodName))
                .findFirst()
                .ifPresent(this::ensureTestAnnotation);
    }

    private String synthesizeScratchSource(TestClassInfo classInfo,
                                           GeneratedTestSnippet snippet,
                                           String scratchClassName) {
        String packageName = determinePackage(classInfo.getTargetPath());
        LinkedHashSet<String> imports = new LinkedHashSet<>();
        imports.add(TEST_IMPORT);
        for (String rawImport : snippet.imports()) {
            String normalized = normalizeImport(rawImport);
            if (!normalized.isBlank()) {
                imports.add(normalized);
            }
        }

        StringBuilder builder = new StringBuilder();
        if (!packageName.isBlank()) {
            builder.append("package ").append(packageName).append(';').append(System.lineSeparator()).append(System.lineSeparator());
        }
        for (String importedType : imports) {
            builder.append("import ").append(importedType).append(';').append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());
        for (String annotation : snippet.classAnnotations()) {
            if (annotation != null && !annotation.isBlank()) {
                builder.append(annotation.trim()).append(System.lineSeparator());
            }
        }
        builder.append("public class ").append(scratchClassName).append(" {").append(System.lineSeparator());
        appendMembers(builder, snippet.fieldDeclarations());
        appendMembers(builder, snippet.helperMethods());
        builder.append(System.lineSeparator()).append(indentBlock(normalizeMethodBody(snippet.methodBody()))).append(System.lineSeparator());
        builder.append("}").append(System.lineSeparator());
        return builder.toString();
    }

    public void enrichResolvableImports(Path projectRoot, Path scratchFile) {
        try {
            CompilationUnit unit = StaticJavaParser.parse(JavaImportSanitizer.sanitizeSourceImports(
                    Files.readString(scratchFile, StandardCharsets.UTF_8)));
            String packageName = unit.getPackageDeclaration()
                    .map(packageDeclaration -> packageDeclaration.getNameAsString())
                    .orElse("");
            LinkedHashSet<String> importedSimpleNames = new LinkedHashSet<>();
            unit.getImports().stream()
                    .filter(importDeclaration -> !importDeclaration.isAsterisk())
                    .forEach(importDeclaration -> importedSimpleNames.add(simpleName(importDeclaration.getNameAsString())));
            LinkedHashSet<String> declaredTypeNames = new LinkedHashSet<>();
            unit.findAll(TypeDeclaration.class)
                    .forEach(typeDeclaration -> declaredTypeNames.add(typeDeclaration.getNameAsString()));

            LinkedHashSet<String> symbolCandidates = new LinkedHashSet<>();
            unit.findAll(ClassOrInterfaceType.class).stream()
                    .filter(type -> type.getScope().isEmpty())
                    .map(ClassOrInterfaceType::getNameAsString)
                    .filter(this::isLikelyTypeName)
                    .forEach(symbolCandidates::add);
            unit.findAll(MethodCallExpr.class).stream()
                    .map(MethodCallExpr::getScope)
                    .flatMap(Optional::stream)
                    .map(this::extractScopedTypeCandidate)
                    .filter(Objects::nonNull)
                    .forEach(symbolCandidates::add);
            unit.findAll(FieldAccessExpr.class).stream()
                    .map(FieldAccessExpr::getScope)
                    .map(this::extractScopedTypeCandidate)
                    .filter(Objects::nonNull)
                    .forEach(symbolCandidates::add);

            SourceFileEditor sourceFileEditor = new SourceFileEditor();
            SymbolLookupService symbolLookupService = new SymbolLookupService(projectRoot, sourceFileEditor);
            for (String symbol : symbolCandidates) {
                if (declaredTypeNames.contains(symbol) || importedSimpleNames.contains(symbol) || TEST_IMPORT.endsWith("." + symbol)) {
                    continue;
                }
                List<String> matches = symbolLookupService.lookupSymbolCandidates(symbol);
                if (matches.size() != 1) {
                    continue;
                }
                String fqcn = matches.get(0);
                if (fqcn.startsWith("java.lang.") || samePackage(packageName, fqcn)) {
                    continue;
                }
                String updated = sourceFileEditor.addImport(scratchFile, fqcn);
                if (!updated.isBlank()) {
                    importedSimpleNames.add(simpleName(fqcn));
                }
            }
        } catch (IOException | ParseProblemException ignored) {
            // best effort import enrichment
        }
    }

    private void appendMembers(StringBuilder builder, List<String> members) {
        if (members == null || members.isEmpty()) {
            return;
        }
        for (String member : members) {
            if (member == null || member.isBlank()) {
                continue;
            }
            builder.append(System.lineSeparator()).append(indentBlock(member.trim())).append(System.lineSeparator());
        }
    }

    private String normalizeMethodBody(String methodBody) {
        String trimmed = methodBody == null ? "" : methodBody.trim();
        if (trimmed.isBlank()) {
            return "@Test void generatedScratchValidationMethod() {}";
        }
        try {
            BodyDeclaration<?> body = StaticJavaParser.parseBodyDeclaration(trimmed);
            if (!body.isMethodDeclaration()) {
                return trimmed;
            }
            MethodDeclaration declaration = body.asMethodDeclaration();
            ensureTestAnnotation(declaration);
            dedupeAnnotations(declaration);
            return declaration.toString();
        } catch (ParseProblemException exception) {
            logger.warn("[SIBLING_ISOLATION] Unable to parse generated method for scratch validation: "
                    + exception.getMessage());
            if (!trimmed.contains("@Test")) {
                return "@Test" + System.lineSeparator() + trimmed;
            }
            return trimmed;
        }
    }

    private String indentBlock(String source) {
        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (index > 0) {
                builder.append(System.lineSeparator());
            }
            if (line.isBlank()) {
                builder.append("");
            } else if (line.startsWith("    ")) {
                builder.append(line);
            } else {
                builder.append("    ").append(line.stripLeading());
            }
        }
        return builder.toString();
    }

    private boolean containsTestAnnotation(String source) {
        return source != null && source.contains("@Test");
    }

    private boolean hasTestAnnotation(MethodDeclaration method) {
        return method != null && method.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .anyMatch("Test"::equals);
    }

    private int countOccurrences(String value, String needle) {
        if (value == null || value.isBlank() || needle == null || needle.isBlank()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private long countTestMethods(String source) {
        if (source == null || source.isBlank()) {
            return 0;
        }
        try {
            CompilationUnit unit = StaticJavaParser.parse(JavaImportSanitizer.sanitizeSourceImports(source));
            ClassOrInterfaceDeclaration declaration = locateClass(unit, null);
            if (declaration == null) {
                return 0;
            }
            return declaration.getMethods().stream().filter(this::hasTestAnnotation).count();
        } catch (ParseProblemException exception) {
            return countOccurrences(source, "@Test");
        }
    }

    private String extractScopedTypeCandidate(Expression expression) {
        if (expression instanceof NameExpr nameExpr) {
            String name = nameExpr.getNameAsString();
            return isLikelyTypeName(name) ? name : null;
        }
        return null;
    }

    private boolean isLikelyTypeName(String symbol) {
        return symbol != null
                && !symbol.isBlank()
                && Character.isUpperCase(symbol.charAt(0))
                && symbol.indexOf('.') < 0;
    }

    private boolean samePackage(String packageName, String fqcn) {
        String fqcnPackage = packageName(fqcn);
        return !packageName.isBlank() && packageName.equals(fqcnPackage);
    }

    private String simpleName(String fqcn) {
        int separator = fqcn.lastIndexOf('.');
        return separator >= 0 ? fqcn.substring(separator + 1) : fqcn;
    }

    private String packageName(String fqcn) {
        int separator = fqcn.lastIndexOf('.');
        return separator >= 0 ? fqcn.substring(0, separator) : "";
    }

    private String resolveQualifiedClassName(Path targetFile, String className) {
        String packageName = determinePackage(targetFile);
        if (packageName == null || packageName.isBlank()) {
            return className;
        }
        return packageName + "." + className;
    }

    private void cleanupScratchArtifacts(Path targetTestFile, Path scratchFile, String scratchClassName) {
        if (policy.cleanupScratchSource()) {
            try {
                Files.deleteIfExists(scratchFile);
            } catch (IOException exception) {
                logger.warn("[SIBLING_ISOLATION] Unable to delete scratch source "
                        + scratchFile + ": " + exception.getMessage());
            }
        }
        if (!policy.cleanupCompiledArtifacts()) {
            return;
        }
        Path compiledDirectory = resolveCompiledDirectory(targetTestFile);
        if (compiledDirectory == null || !Files.isDirectory(compiledDirectory)) {
            return;
        }
        try {
            List<Path> compiledArtifacts = new ArrayList<>();
            try (var stream = Files.list(compiledDirectory)) {
                stream.filter(path -> {
                            String fileName = path.getFileName().toString();
                            return fileName.equals(scratchClassName + ".class")
                                    || fileName.startsWith(scratchClassName + "$");
                        })
                        .forEach(compiledArtifacts::add);
            }
            for (Path artifact : compiledArtifacts) {
                Files.deleteIfExists(artifact);
            }
        } catch (IOException exception) {
            logger.warn("[SIBLING_ISOLATION] Unable to clean compiled scratch artifacts for "
                    + scratchClassName + ": " + exception.getMessage());
        }
    }

    private Path resolveCompiledDirectory(Path targetTestFile) {
        String normalized = targetTestFile.toAbsolutePath().normalize().toString().replace('\\', '/');
        String marker = "/src/test/java/";
        int index = normalized.indexOf(marker);
        if (index < 0) {
            return null;
        }
        Path moduleRoot = Path.of(normalized.substring(0, index));
        String packageName = determinePackage(targetTestFile);
        Path compiledRoot = moduleRoot.resolve(Path.of("build", "classes", "java", "test"));
        if (packageName.isBlank()) {
            return compiledRoot;
        }
        return compiledRoot.resolve(Path.of(packageName.replace('.', '/')));
    }

    private String determinePackage(Path targetFile) {
        String normalized = targetFile.toAbsolutePath().normalize().toString().replace('\\', '/');
        String marker = "/src/test/java/";
        int index = normalized.indexOf(marker);
        if (index < 0) {
            return "";
        }
        String remainder = normalized.substring(index + marker.length());
        int lastSlash = remainder.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "";
        }
        return remainder.substring(0, lastSlash).replace('/', '.');
    }

    private String summariseCompileFailure(CompileResult compileResult) {
        if (compileResult == null) {
            return "unknown";
        }
        String stderr = compileResult.stderr();
        if (stderr != null && !stderr.isBlank()) {
            return abbreviate(stderr.replaceAll("\\s+", " ").trim());
        }
        if (compileResult.messages() != null && !compileResult.messages().isEmpty()) {
            return abbreviate(String.join(" | ", compileResult.messages()).replaceAll("\\s+", " ").trim());
        }
        String stdout = compileResult.stdout();
        if (stdout != null && !stdout.isBlank()) {
            return abbreviate(stdout.replaceAll("\\s+", " ").trim());
        }
        return "unknown";
    }

    private String summariseExecutionFailure(ExecuteResult executeResult) {
        if (executeResult == null) {
            return "unknown";
        }
        String stderr = executeResult.stderr();
        if (stderr != null && !stderr.isBlank()) {
            return abbreviate(stderr.replaceAll("\\s+", " ").trim());
        }
        List<String> failedTests = executeResult.failedTests();
        if (failedTests != null && !failedTests.isEmpty()) {
            return abbreviate(String.join(" | ", failedTests).replaceAll("\\s+", " ").trim());
        }
        String stdout = executeResult.stdout();
        if (stdout != null && !stdout.isBlank()) {
            return abbreviate(stdout.replaceAll("\\s+", " ").trim());
        }
        return "unknown";
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= 1800) {
            return value;
        }
        return value.substring(0, 1800) + "...";
    }

    private String normaliseLineEndings(String source) {
        if (source == null) {
            return "";
        }
        String unix = source.replace("\r\n", "\n").replace('\r', '\n');
        if ("\n".equals(System.lineSeparator())) {
            return unix;
        }
        return unix.replace("\n", System.lineSeparator());
    }

    private ClassOrInterfaceDeclaration locateClass(CompilationUnit unit, String className) {
        if (className != null && !className.isBlank()) {
            ClassOrInterfaceDeclaration exact = unit.getClassByName(className).orElse(null);
            if (exact != null) {
                return exact;
            }
        }
        return unit.getPrimaryType()
                .flatMap(type -> type.toClassOrInterfaceDeclaration())
                .orElseGet(() -> unit.findFirst(ClassOrInterfaceDeclaration.class).orElse(null));
    }

    private boolean isTargetClassAlreadyPopulated(Path targetTestFile) {
        if (targetTestFile == null || !Files.exists(targetTestFile)) {
            return false;
        }
        try {
            String source = Files.readString(targetTestFile);
            if (source == null || source.isBlank()) {
                return false;
            }
            if (source.contains("@Test")
                    || source.contains("@BeforeEach")
                    || source.contains("@AfterEach")
                    || source.contains("@BeforeAll")
                    || source.contains("@AfterAll")) {
                return true;
            }
            CompilationUnit unit = StaticJavaParser.parse(JavaImportSanitizer.sanitizeSourceImports(source));
            TypeDeclaration<?> declaration = unit.getPrimaryType()
                    .orElseGet(() -> unit.findFirst(ClassOrInterfaceDeclaration.class).orElse(null));
            if (declaration == null) {
                return false;
            }
            return declaration.getMembers().stream().anyMatch(member ->
                    member.isFieldDeclaration()
                            || member.isMethodDeclaration()
                            || member.isConstructorDeclaration()
                            || member.isInitializerDeclaration()
                            || member.isClassOrInterfaceDeclaration()
                            || member.isEnumDeclaration());
        } catch (IOException | ParseProblemException exception) {
            logger.warn("[SIBLING_ISOLATION] Unable to inspect target test class for pre-merge execution gating: "
                    + exception.getMessage());
            return true;
        }
    }

    private void ensureImports(CompilationUnit unit, List<String> imports) {
        if (imports == null) {
            return;
        }
        for (String rawImport : imports) {
            String normalized = normalizeImport(rawImport);
            if (normalized.isBlank()) {
                continue;
            }
            boolean staticImport = normalized.startsWith("static ");
            String target = staticImport ? normalized.substring("static ".length()).trim() : normalized;
            boolean wildcard = target.endsWith(".*");
            String importName = wildcard ? target.substring(0, target.length() - 2) : target;
            boolean exists = unit.getImports().stream()
                    .anyMatch(declaration -> declaration.isStatic() == staticImport
                            && declaration.isAsterisk() == wildcard
                            && declaration.getNameAsString().equals(importName));
            if (!exists) {
                unit.addImport(importName, staticImport, wildcard);
            }
        }
    }

    private void ensureTestAnnotationImport(CompilationUnit unit) {
        if (unit.getImports().stream().map(ImportDeclaration::getNameAsString).noneMatch(TEST_IMPORT::equals)) {
            unit.addImport(TEST_IMPORT);
        }
    }

    private String normalizeImport(String rawImport) {
        return JavaImportSanitizer.normalizeImportTarget(rawImport);
    }

    private void ensureTestAnnotation(MethodDeclaration declaration) {
        if (declaration.getAnnotationByName("Test").isEmpty()) {
            declaration.addAnnotation("Test");
        }
    }

    private void dedupeAnnotations(MethodDeclaration declaration) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<AnnotationExpr> duplicates = new ArrayList<>();
        for (AnnotationExpr annotation : declaration.getAnnotations()) {
            String key = annotation.toString().trim();
            if (!seen.add(key)) {
                duplicates.add(annotation);
            }
        }
        duplicates.forEach(AnnotationExpr::remove);
    }

    public record ValidationResult(boolean success,
                                   CompileResult compileResult,
                                   ExecuteResult executeResult,
                                   String failureReason,
                                   GeneratedTestSnippet validatedSnippet,
                                   String replacementSource,
                                   boolean replaceTargetClassSource,
                                   String replacementDiagnostic) {

        public static ValidationResult passed() {
            return new ValidationResult(true, null, null, "", null, "", false, "");
        }

        public static ValidationResult passed(GeneratedTestSnippet validatedSnippet,
                                              CompileResult compileResult,
                                              ExecuteResult executeResult,
                                              String replacementSource,
                                              boolean replaceTargetClassSource,
                                              String replacementDiagnostic) {
            return new ValidationResult(true,
                    compileResult,
                    executeResult,
                    "",
                    validatedSnippet,
                    replacementSource == null ? "" : replacementSource,
                    replaceTargetClassSource,
                    replacementDiagnostic == null ? "" : replacementDiagnostic);
        }

        public static ValidationResult failed(String failureReason,
                                              CompileResult compileResult,
                                              ExecuteResult executeResult,
                                              GeneratedTestSnippet validatedSnippet,
                                              String replacementSource,
                                              boolean replaceTargetClassSource,
                                              String replacementDiagnostic) {
            return new ValidationResult(false,
                    compileResult,
                    executeResult,
                    failureReason == null ? "" : failureReason,
                    validatedSnippet,
                    replacementSource == null ? "" : replacementSource,
                    replaceTargetClassSource,
                    replacementDiagnostic == null ? "" : replacementDiagnostic);
        }
    }

    @FunctionalInterface
    public interface ScratchCompilationRepair {
        ScratchRepairOutcome repair(ScratchCompilationFailure failure);
    }

    @FunctionalInterface
    public interface ScratchExecutionRepair {
        ScratchExecutionRepairOutcome repair(ScratchExecutionFailure failure);
    }

    public record ScratchCompilationFailure(Path projectRoot,
                                            TestClassInfo classInfo,
                                            GeneratedTestSnippet snippet,
                                            Path scratchFile,
                                            String scratchClassName,
                                            String scratchClassFqcn,
                                            String generatedMethodName,
                                            boolean validatesMergedClass,
                                            CompileResult compileResult) {
    }

    public record ScratchRepairOutcome(boolean success, CompileResult compileResult) {

        public static ScratchRepairOutcome success(CompileResult compileResult) {
            return new ScratchRepairOutcome(true, compileResult);
        }

        public static ScratchRepairOutcome failed(CompileResult compileResult) {
            return new ScratchRepairOutcome(false, compileResult);
        }
    }

    public record ScratchExecutionFailure(Path projectRoot,
                                          TestClassInfo classInfo,
                                          GeneratedTestSnippet snippet,
                                          Path scratchFile,
                                          String scratchClassName,
                                          String scratchClassFqcn,
                                          String generatedMethodName,
                                          boolean validatesMergedClass,
                                          boolean executeWholeSuite,
                                          CompileResult compileResult,
                                          ExecuteResult executeResult) {
    }

    public record ScratchExecutionRepairOutcome(boolean success,
                                                CompileResult compileResult,
                                                ExecuteResult executeResult) {

        public static ScratchExecutionRepairOutcome success(CompileResult compileResult,
                                                            ExecuteResult executeResult) {
            return new ScratchExecutionRepairOutcome(true, compileResult, executeResult);
        }

        public static ScratchExecutionRepairOutcome failed(CompileResult compileResult,
                                                           ExecuteResult executeResult) {
            return new ScratchExecutionRepairOutcome(false, compileResult, executeResult);
        }
    }

    private record ScratchCarryover(GeneratedTestSnippet validatedSnippet,
                                    String replacementSource,
                                    boolean replaceTargetClassSource,
                                    String replacementDiagnostic) {
    }

    private record ScratchValidationPlan(String scratchSource,
                                         GeneratedTestSnippet preparedSnippet,
                                         String effectiveMethodName,
                                         boolean executeWholeSuite,
                                         boolean validatesMergedClass) {
    }
}
