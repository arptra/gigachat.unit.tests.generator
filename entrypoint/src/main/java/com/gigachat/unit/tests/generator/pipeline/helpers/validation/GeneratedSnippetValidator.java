package com.gigachat.unit.tests.generator.pipeline.helpers.validation;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ExternalCollaboratorDetector;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.PipelineModuleConfig;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.InvalidLLMResponseException;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates generated test snippets against bounded source-derived rules before the pipeline accepts
 * them for compile/execute stages.
 */
public class GeneratedSnippetValidator {

    private final PipelineLogger logger;
    private final GeneratedSnippetApiUsageValidator apiUsageValidator;
    private final GeneratedSnippetStructureValidator structureValidator;
    private final GeneratedSnippetMockitoValidator mockitoValidator;

    public GeneratedSnippetValidator(PipelineLogger logger,
                                     Analyze analyze,
                                     MethodSignatureRegistry signatureRegistry) {
        this.logger = Objects.requireNonNull(logger, "logger");
        Analyze requiredAnalyze = Objects.requireNonNull(analyze, "analyze");
        MethodSignatureRegistry requiredRegistry = Objects.requireNonNull(signatureRegistry, "signatureRegistry");
        ExternalCollaboratorDetector collaboratorDetector = new ExternalCollaboratorDetector();
        this.apiUsageValidator = new GeneratedSnippetApiUsageValidator(logger, requiredAnalyze, requiredRegistry);
        this.structureValidator = new GeneratedSnippetStructureValidator(logger);
        this.mockitoValidator = new GeneratedSnippetMockitoValidator(logger, requiredRegistry, collaboratorDetector);
    }

    public void validateGeneratedSnippet(AgentConfig config,
                                         TestClassInfo classInfo,
                                         GeneratedTestSnippet snippet,
                                         TestMethodInfo methodInfo,
                                         Analyze.AnalysisSummary analysisSummary,
                                         PipelineModuleConfig moduleConfig) {
        if (snippet == null || methodInfo == null) {
            return;
        }
        String sourceForValidation = composeValidationSource(classInfo, snippet);
        if (sourceForValidation == null || sourceForValidation.isBlank()) {
            return;
        }
        String signature = methodInfo.getSignature();
        if (signature == null || signature.isBlank()) {
            return;
        }
        String normalisedSignature = signature.replaceAll("\\s+", " ").trim();
        String normalisedSource = sourceForValidation.replaceAll("\\s+", " ").trim();
        if (normalisedSource.contains(normalisedSignature + " {")) {
            String methodName = analysisSummary.methodAnalysis().method().name();
            logger.warn("⚠️  LLM reimplemented method " + methodName + " inside test class. Marking generation as invalid.");
            throw new InvalidLLMResponseException("LLM returned reimplementation of tested method instead of test.");
        }
        CompilationUnit compilationUnit = parseCompilationUnit(sourceForValidation);
        if (compilationUnit == null) {
            return;
        }
        structureValidator.ensureNoConflictingLifecycleFixtureRedefinition(classInfo, snippet, compilationUnit, analysisSummary);
        structureValidator.ensureProjectImportsAreResolvable(config.getProjectPath(), compilationUnit);
        Map<String, String> variableTypes = apiUsageValidator.ensureMethodAndConstructorUsageIsValid(config,
                compilationUnit,
                analysisSummary,
                classInfo,
                methodInfo);
        ensureNoInternalFieldAccess(sourceForValidation, analysisSummary);
        mockitoValidator.ensureNoForbiddenConstructorMockUsage(compilationUnit, analysisSummary);
        ensureTargetUsesMockAwareConstruction(compilationUnit, analysisSummary);
        structureValidator.ensureRequiredConstructorArgumentsAreNotNull(config.getProjectPath(), compilationUnit, analysisSummary);
        structureValidator.ensureGeneratedTestInvokesTargetMethod(compilationUnit, analysisSummary, methodInfo, sourceForValidation);
        mockitoValidator.ensureNoVoidMethodStubbingInsideMockitoWhen(compilationUnit, variableTypes, classInfo);
        mockitoValidator.ensureNoVoidMethodUsedAsValue(compilationUnit, variableTypes, classInfo);
        mockitoValidator.ensureNoMockitoStubbingOnStaticBranchDrivers(compilationUnit, analysisSummary, variableTypes, classInfo);
        if (moduleConfig != null && moduleConfig.validateMockUsage()) {
            mockitoValidator.ensureMockUsageIsValid(sourceForValidation, analysisSummary, classInfo);
        }
    }

    public void ensureNoInternalFieldAccess(String generatedCode,
                                            Analyze.AnalysisSummary analysisSummary) {
        structureValidator.ensureNoInternalFieldAccess(generatedCode, analysisSummary);
    }

    public void ensureTargetUsesMockAwareConstruction(CompilationUnit compilationUnit,
                                                      Analyze.AnalysisSummary analysisSummary) {
        structureValidator.ensureTargetUsesMockAwareConstruction(compilationUnit, analysisSummary);
    }

    public void ensureProjectImportsAreResolvable(java.nio.file.Path projectRoot,
                                                  CompilationUnit compilationUnit) {
        structureValidator.ensureProjectImportsAreResolvable(projectRoot, compilationUnit);
    }

    public void ensureRequiredConstructorArgumentsAreNotNull(java.nio.file.Path projectRoot,
                                                             CompilationUnit compilationUnit,
                                                             Analyze.AnalysisSummary analysisSummary) {
        structureValidator.ensureRequiredConstructorArgumentsAreNotNull(projectRoot, compilationUnit, analysisSummary);
    }

    public void ensureNoConflictingLifecycleFixtureRedefinition(TestClassInfo classInfo,
                                                                GeneratedTestSnippet snippet,
                                                                CompilationUnit compilationUnit,
                                                                Analyze.AnalysisSummary analysisSummary) {
        structureValidator.ensureNoConflictingLifecycleFixtureRedefinition(classInfo, snippet, compilationUnit, analysisSummary);
    }

    public List<String> buildTargetConstructionRetryConstraints(Analyze.AnalysisSummary analysisSummary) {
        return structureValidator.buildTargetConstructionRetryConstraints(analysisSummary);
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

    private String composeValidationSource(TestClassInfo classInfo, GeneratedTestSnippet snippet) {
        if (snippet == null) {
            return "";
        }
        boolean hasHelperOrFieldContent = (snippet.helperMethods() != null && !snippet.helperMethods().isEmpty())
                || (snippet.fieldDeclarations() != null && !snippet.fieldDeclarations().isEmpty());
        if (hasHelperOrFieldContent) {
            String className = classInfo != null && classInfo.getTestClassName() != null && !classInfo.getTestClassName().isBlank()
                    ? classInfo.getTestClassName()
                    : (snippet.className() == null || snippet.className().isBlank() ? "GeneratedSnippetValidationProbe" : snippet.className());
            StringBuilder builder = new StringBuilder();
            appendImports(builder, snippet.imports());
            builder.append("class ").append(className).append(" {\n");
            appendMembers(builder, snippet.fieldDeclarations());
            if (snippet.methodBody() != null && !snippet.methodBody().isBlank()) {
                builder.append(snippet.methodBody()).append('\n');
            }
            appendMembers(builder, snippet.helperMethods());
            builder.append("}\n");
            return builder.toString();
        }
        String fullSource = snippet.fullClassSource();
        if (fullSource != null && !fullSource.isBlank()) {
            return fullSource;
        }
        String methodBody = snippet.methodBody();
        if (methodBody != null && !methodBody.isBlank() && classInfo != null) {
            return "class " + classInfo.getTestClassName() + " {\n" + methodBody + "\n}";
        }
        return methodBody == null ? "" : methodBody;
    }

    private void appendImports(StringBuilder builder, List<String> imports) {
        if (builder == null || imports == null || imports.isEmpty()) {
            return;
        }
        boolean appended = false;
        for (String rawImport : imports) {
            if (rawImport == null || rawImport.isBlank()) {
                continue;
            }
            String importLine = rawImport.trim();
            if (!importLine.startsWith("import ")) {
                importLine = importLine.startsWith("static ")
                        ? "import " + importLine
                        : "import " + importLine;
            }
            if (!importLine.endsWith(";")) {
                importLine += ";";
            }
            builder.append(importLine).append('\n');
            appended = true;
        }
        if (appended) {
            builder.append('\n');
        }
    }

    private void appendMembers(StringBuilder builder, List<String> members) {
        if (builder == null || members == null || members.isEmpty()) {
            return;
        }
        for (String member : members) {
            if (member == null || member.isBlank()) {
                continue;
            }
            builder.append(member).append('\n');
        }
    }

}
