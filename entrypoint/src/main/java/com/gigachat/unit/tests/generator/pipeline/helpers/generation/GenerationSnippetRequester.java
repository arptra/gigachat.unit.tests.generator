package com.gigachat.unit.tests.generator.pipeline.helpers.generation;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.config.PipelineModuleConfig;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.llm.LlmClient;
import com.gigachat.unit.tests.generator.pipeline.InvalidLLMResponseException;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import com.gigachat.unit.tests.generator.pipeline.helpers.PromptBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.AutoCorrectionStage;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.ConstructorLocalSideEffectFallbackBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.ConstructorReturnSideEffectFallbackBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.ConstructorStatePathFallbackBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.GenerationValidationRetryBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.InventedStateMutatorFallbackBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.LifecycleFixtureReuseFallbackBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.OptionalSideEffectFallbackBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.PlaceholderInvocationFallbackBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.ProjectImportCorrectionFallbackBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.repair.StaticBranchDriverFallbackBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.validation.GeneratedSnippetValidator;
import com.gigachat.unit.tests.generator.reasoning.model.AgentState;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Owns the bounded generation request/retry loop around the LLM.
 */
public class GenerationSnippetRequester {

    private final PipelineLogger logger;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final AutoCorrectionStage autoCorrectionStage;
    private final GenerationValidationRetryBuilder generationValidationRetryBuilder;
    private final GeneratedSnippetValidator generatedSnippetValidator;
    private final PlaceholderInvocationFallbackBuilder placeholderInvocationFallbackBuilder;
    private final LifecycleFixtureReuseFallbackBuilder lifecycleFixtureReuseFallbackBuilder;
    private final OptionalSideEffectFallbackBuilder optionalSideEffectFallbackBuilder;
    private final InventedStateMutatorFallbackBuilder inventedStateMutatorFallbackBuilder;
    private final ConstructorStatePathFallbackBuilder constructorStatePathFallbackBuilder;
    private final ConstructorLocalSideEffectFallbackBuilder constructorLocalSideEffectFallbackBuilder;
    private final ConstructorReturnSideEffectFallbackBuilder constructorReturnSideEffectFallbackBuilder;
    private final ProjectImportCorrectionFallbackBuilder projectImportCorrectionFallbackBuilder;
    private final StaticBranchDriverFallbackBuilder staticBranchDriverFallbackBuilder;

    public GenerationSnippetRequester(PipelineLogger logger,
                                      PromptBuilder promptBuilder,
                                      LlmClient llmClient,
                                      AutoCorrectionStage autoCorrectionStage,
                                      GenerationValidationRetryBuilder generationValidationRetryBuilder,
                                      GeneratedSnippetValidator generatedSnippetValidator) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.autoCorrectionStage = Objects.requireNonNull(autoCorrectionStage, "autoCorrectionStage");
        this.generationValidationRetryBuilder = Objects.requireNonNull(generationValidationRetryBuilder, "generationValidationRetryBuilder");
        this.generatedSnippetValidator = Objects.requireNonNull(generatedSnippetValidator, "generatedSnippetValidator");
        this.placeholderInvocationFallbackBuilder = new PlaceholderInvocationFallbackBuilder();
        this.lifecycleFixtureReuseFallbackBuilder = new LifecycleFixtureReuseFallbackBuilder(logger);
        this.optionalSideEffectFallbackBuilder = new OptionalSideEffectFallbackBuilder(logger);
        this.inventedStateMutatorFallbackBuilder = new InventedStateMutatorFallbackBuilder(logger);
        this.constructorStatePathFallbackBuilder = new ConstructorStatePathFallbackBuilder(logger);
        this.constructorLocalSideEffectFallbackBuilder = new ConstructorLocalSideEffectFallbackBuilder(logger);
        this.constructorReturnSideEffectFallbackBuilder = new ConstructorReturnSideEffectFallbackBuilder(logger);
        this.projectImportCorrectionFallbackBuilder = new ProjectImportCorrectionFallbackBuilder(logger);
        this.staticBranchDriverFallbackBuilder = new StaticBranchDriverFallbackBuilder(logger);
    }

    public GeneratedTestSnippet requestSnippet(AgentConfig config,
                                               TestClassInfo classInfo,
                                               TestMethodInfo methodInfo,
                                               MockPlan plan,
                                               JSONObject contextJson,
                                               Analyze.AnalysisSummary analysisSummary,
                                               PipelineModuleConfig moduleConfig,
                                               boolean retryAttempt) {
        GeneratedTestSnippet fastPathSnippet = tryDeterministicFastPath(config,
                classInfo,
                methodInfo,
                analysisSummary,
                contextJson,
                moduleConfig);
        if (fastPathSnippet != null) {
            return fastPathSnippet;
        }
        String llmPrompt = promptBuilder.buildPromptForLLM(contextJson, config.getPromptConfig());
        logger.trace("GIGACHAT", methodInfo.getSignature(), retryAttempt ? "GENERATION_RETRY" : "GENERATION", "sending generation prompt");
        logger.info("-> DEBUG LOG Request to gigachat \n" + llmPrompt);
        logger.info("Prepared LLM prompt for method " + methodInfo.getSignature()
                + (retryAttempt ? " [retry]" : ""));
        GeneratedTestSnippet snippet = llmClient.generateTestSnippet(llmPrompt, classInfo, methodInfo, plan);
        logger.trace("GIGACHAT", methodInfo.getSignature(), retryAttempt ? "GENERATION_RETRY" : "GENERATION", "received generation response method=" + (snippet == null ? "null" : snippet.methodName()));
        logger.info("Response from gigachat " + snippet);
        snippet = autoCorrectionStage.apply(snippet);
        try {
            generatedSnippetValidator.validateGeneratedSnippet(config, classInfo, snippet, methodInfo, analysisSummary, moduleConfig);
            return snippet;
        } catch (InvalidLLMResponseException first) {
            GeneratedTestSnippet inventedStateMutatorFallback = tryInventedStateMutatorFallback(config,
                    classInfo,
                    methodInfo,
                    analysisSummary,
                    moduleConfig,
                    snippet,
                    first);
            if (inventedStateMutatorFallback != null) {
                return inventedStateMutatorFallback;
            }
            GeneratedTestSnippet constructorStateFallback = tryConstructorStatePathFallback(config,
                    classInfo,
                    methodInfo,
                    analysisSummary,
                    moduleConfig,
                    snippet,
                    first);
            if (constructorStateFallback != null) {
                return constructorStateFallback;
            }
            GeneratedTestSnippet projectImportCorrectionFallback = tryProjectImportCorrectionFallback(config,
                    classInfo,
                    methodInfo,
                    analysisSummary,
                    moduleConfig,
                    snippet,
                    first);
            if (projectImportCorrectionFallback != null) {
                return projectImportCorrectionFallback;
            }
            GeneratedTestSnippet staticBranchDriverFallback = tryStaticBranchDriverFallback(config,
                    classInfo,
                    methodInfo,
                    analysisSummary,
                    moduleConfig,
                    snippet,
                    first);
            if (staticBranchDriverFallback != null) {
                return staticBranchDriverFallback;
            }
            GeneratedTestSnippet constructorLocalSideEffectFallback = tryConstructorLocalSideEffectFallback(config,
                    classInfo,
                    methodInfo,
                    analysisSummary,
                    moduleConfig,
                    snippet,
                    contextJson,
                    first);
            if (constructorLocalSideEffectFallback != null) {
                return constructorLocalSideEffectFallback;
            }
            GeneratedTestSnippet firstLifecycleReuseFallback = tryLifecycleFixtureReuseFallback(config,
                    classInfo,
                    methodInfo,
                    analysisSummary,
                    moduleConfig,
                    snippet,
                    first);
            if (firstLifecycleReuseFallback != null) {
                return firstLifecycleReuseFallback;
            }
            if (!generationValidationRetryBuilder.shouldRetry(first)) {
                logger.warn("Discarding invalid generation response for method "
                        + methodInfo.getSignature()
                        + " because no bounded retry path applies ("
                        + first.getMessage()
                        + ").");
                return null;
            }
            logger.warn("Retrying generation for method " + methodInfo.getSignature()
                    + " due to invalid response (" + first.getMessage() + ")");
            logger.trace("RESULT", methodInfo.getSignature(), "GENERATION_VALIDATION_RETRY", "generated snippet rejected reason=" + first.getMessage());
            JSONObject retryContext = generationValidationRetryBuilder.buildRetryContext(contextJson,
                    first,
                    analysisSummary,
                    snippet);
            String retryPrompt = promptBuilder.buildPromptForLLM(retryContext, config.getPromptConfig());
            logger.trace("GIGACHAT", methodInfo.getSignature(), "GENERATION_VALIDATION_RETRY", "sending validation retry prompt");
            logger.info("-> DEBUG LOG Request to gigachat \n" + retryPrompt);
            logger.info("Prepared LLM prompt for method " + methodInfo.getSignature() + " [retry-after-validation]");
            GeneratedTestSnippet retrySnippet = llmClient.generateTestSnippet(retryPrompt, classInfo, methodInfo, plan);
            logger.trace("GIGACHAT", methodInfo.getSignature(), "GENERATION_VALIDATION_RETRY", "received validation retry response method=" + (retrySnippet == null ? "null" : retrySnippet.methodName()));
            logger.info("Response from gigachat " + retrySnippet);
            retrySnippet = autoCorrectionStage.apply(retrySnippet);
            try {
                generatedSnippetValidator.validateGeneratedSnippet(config, classInfo, retrySnippet, methodInfo, analysisSummary, moduleConfig);
                return retrySnippet;
            } catch (InvalidLLMResponseException second) {
                GeneratedTestSnippet secondInventedStateMutatorFallback = tryInventedStateMutatorFallback(config,
                        classInfo,
                        methodInfo,
                        analysisSummary,
                        moduleConfig,
                        retrySnippet,
                        second);
                if (secondInventedStateMutatorFallback != null) {
                    return secondInventedStateMutatorFallback;
                }
                GeneratedTestSnippet secondConstructorStateFallback = tryConstructorStatePathFallback(config,
                        classInfo,
                        methodInfo,
                        analysisSummary,
                        moduleConfig,
                        retrySnippet,
                        second);
                if (secondConstructorStateFallback != null) {
                    return secondConstructorStateFallback;
                }
                GeneratedTestSnippet secondProjectImportCorrectionFallback = tryProjectImportCorrectionFallback(config,
                        classInfo,
                        methodInfo,
                        analysisSummary,
                        moduleConfig,
                        retrySnippet,
                        second);
                if (secondProjectImportCorrectionFallback != null) {
                    return secondProjectImportCorrectionFallback;
                }
                GeneratedTestSnippet secondStaticBranchDriverFallback = tryStaticBranchDriverFallback(config,
                        classInfo,
                        methodInfo,
                        analysisSummary,
                        moduleConfig,
                        retrySnippet,
                        second);
                if (secondStaticBranchDriverFallback != null) {
                    return secondStaticBranchDriverFallback;
                }
                GeneratedTestSnippet secondConstructorLocalSideEffectFallback = tryConstructorLocalSideEffectFallback(config,
                        classInfo,
                        methodInfo,
                        analysisSummary,
                        moduleConfig,
                        retrySnippet,
                        retryContext,
                        second);
                if (secondConstructorLocalSideEffectFallback != null) {
                    return secondConstructorLocalSideEffectFallback;
                }
                GeneratedTestSnippet secondLifecycleReuseFallback = tryLifecycleFixtureReuseFallback(config,
                        classInfo,
                        methodInfo,
                        analysisSummary,
                        moduleConfig,
                        retrySnippet,
                        second);
                if (secondLifecycleReuseFallback != null) {
                    return secondLifecycleReuseFallback;
                }
                GeneratedTestSnippet fallbackSnippet = tryPlaceholderInvocationFallback(config,
                        classInfo,
                        methodInfo,
                        analysisSummary,
                        moduleConfig,
                        retrySnippet,
                        second);
                if (fallbackSnippet != null) {
                    return fallbackSnippet;
                }
                logger.warn("Discarding invalid retry response for method "
                        + methodInfo.getSignature()
                        + " after validation failure ("
                        + second.getMessage()
                        + ").");
                logger.trace("RESULT", methodInfo.getSignature(), AgentState.S6_GIVE_UP.name(), "validation retry also rejected reason=" + second.getMessage());
                return null;
            }
        }
    }

    private GeneratedTestSnippet tryLifecycleFixtureReuseFallback(AgentConfig config,
                                                                  TestClassInfo classInfo,
                                                                  TestMethodInfo methodInfo,
                                                                  Analyze.AnalysisSummary analysisSummary,
                                                                  PipelineModuleConfig moduleConfig,
                                                                  GeneratedTestSnippet snippet,
                                                                  InvalidLLMResponseException validationException) {
        if (validationException == null || validationException.getMessage() == null || !validationException.getMessage().contains("E112")) {
            return null;
        }
        GeneratedTestSnippet fallbackSnippet = lifecycleFixtureReuseFallbackBuilder.build(classInfo, snippet, validationException.getMessage());
        if (fallbackSnippet == null) {
            return null;
        }
        try {
            generatedSnippetValidator.validateGeneratedSnippet(config, classInfo, fallbackSnippet, methodInfo, analysisSummary, moduleConfig);
            logger.info("Using deterministic lifecycle-fixture reuse fallback for method " + methodInfo.getSignature());
            return fallbackSnippet;
        } catch (InvalidLLMResponseException lifecycleFailure) {
            GeneratedTestSnippet chainedConstructorStateFallback = tryConstructorStatePathFallback(config,
                    classInfo,
                    methodInfo,
                    analysisSummary,
                    moduleConfig,
                    fallbackSnippet,
                    lifecycleFailure);
            if (chainedConstructorStateFallback != null) {
                logger.info("Using chained lifecycle + constructor-state fallback for method "
                        + methodInfo.getSignature());
                return chainedConstructorStateFallback;
            }
            GeneratedTestSnippet chainedInventedStateFallback = tryInventedStateMutatorFallback(config,
                    classInfo,
                    methodInfo,
                    analysisSummary,
                    moduleConfig,
                    fallbackSnippet,
                    lifecycleFailure);
            if (chainedInventedStateFallback != null) {
                logger.info("Using chained lifecycle + invented-state-mutator fallback for method "
                        + methodInfo.getSignature());
                return chainedInventedStateFallback;
            }
            logger.info("Deterministic lifecycle-fixture reuse fallback was rejected for method "
                    + methodInfo.getSignature() + ": " + lifecycleFailure.getMessage());
            return null;
        }
    }

    private GeneratedTestSnippet tryProjectImportCorrectionFallback(AgentConfig config,
                                                                    TestClassInfo classInfo,
                                                                    TestMethodInfo methodInfo,
                                                                    Analyze.AnalysisSummary analysisSummary,
                                                                    PipelineModuleConfig moduleConfig,
                                                                    GeneratedTestSnippet snippet,
                                                                    InvalidLLMResponseException validationException) {
        if (validationException == null || validationException.getMessage() == null || !validationException.getMessage().contains("E111")) {
            return null;
        }
        GeneratedTestSnippet fallbackSnippet = projectImportCorrectionFallbackBuilder.build(snippet, validationException.getMessage());
        if (fallbackSnippet == null) {
            return null;
        }
        try {
            generatedSnippetValidator.validateGeneratedSnippet(config, classInfo, fallbackSnippet, methodInfo, analysisSummary, moduleConfig);
            logger.info("Using deterministic project-import correction fallback for method " + methodInfo.getSignature());
            return fallbackSnippet;
        } catch (InvalidLLMResponseException ignored) {
            logger.info("Deterministic project-import correction fallback was rejected for method "
                    + methodInfo.getSignature() + ": " + ignored.getMessage());
            return null;
        }
    }

    private GeneratedTestSnippet tryConstructorStatePathFallback(AgentConfig config,
                                                                 TestClassInfo classInfo,
                                                                 TestMethodInfo methodInfo,
                                                                 Analyze.AnalysisSummary analysisSummary,
                                                                 PipelineModuleConfig moduleConfig,
                                                                 GeneratedTestSnippet snippet,
                                                                 InvalidLLMResponseException validationException) {
        if (validationException == null || validationException.getMessage() == null) {
            return null;
        }
        String message = validationException.getMessage();
        if (!message.contains("E104") && !message.contains("E102") && !message.contains("E113")) {
            return null;
        }
        GeneratedTestSnippet fallbackSnippet = constructorStatePathFallbackBuilder.build(snippet,
                analysisSummary,
                message);
        if (fallbackSnippet == null) {
            return null;
        }
        try {
            generatedSnippetValidator.validateGeneratedSnippet(config, classInfo, fallbackSnippet, methodInfo, analysisSummary, moduleConfig);
            logger.info("Using deterministic constructor-state fallback for method " + methodInfo.getSignature());
            return fallbackSnippet;
        } catch (InvalidLLMResponseException ignored) {
            logger.info("Deterministic constructor-state fallback was rejected for method "
                    + methodInfo.getSignature() + ": " + ignored.getMessage());
            return null;
        }
    }

    private GeneratedTestSnippet tryStaticBranchDriverFallback(AgentConfig config,
                                                               TestClassInfo classInfo,
                                                               TestMethodInfo methodInfo,
                                                               Analyze.AnalysisSummary analysisSummary,
                                                               PipelineModuleConfig moduleConfig,
                                                               GeneratedTestSnippet snippet,
                                                               InvalidLLMResponseException validationException) {
        if (validationException == null || validationException.getMessage() == null || !validationException.getMessage().contains("E114")) {
            return null;
        }
        GeneratedTestSnippet fallbackSnippet = staticBranchDriverFallbackBuilder.build(snippet,
                analysisSummary,
                validationException.getMessage());
        if (fallbackSnippet == null) {
            return null;
        }
        try {
            generatedSnippetValidator.validateGeneratedSnippet(config, classInfo, fallbackSnippet, methodInfo, analysisSummary, moduleConfig);
            logger.info("Using deterministic static-branch-driver fallback for method " + methodInfo.getSignature());
            return fallbackSnippet;
        } catch (InvalidLLMResponseException ignored) {
            logger.info("Deterministic static-branch-driver fallback was rejected for method "
                    + methodInfo.getSignature() + ": " + ignored.getMessage());
            return null;
        }
    }

    private GeneratedTestSnippet tryConstructorLocalSideEffectFallback(AgentConfig config,
                                                                       TestClassInfo classInfo,
                                                                       TestMethodInfo methodInfo,
                                                                       Analyze.AnalysisSummary analysisSummary,
                                                                       PipelineModuleConfig moduleConfig,
                                                                       GeneratedTestSnippet snippet,
                                                                       JSONObject contextJson,
                                                                       InvalidLLMResponseException validationException) {
        if (validationException == null || validationException.getMessage() == null) {
            return null;
        }
        GeneratedTestSnippet fallbackSnippet = constructorLocalSideEffectFallbackBuilder.build(classInfo,
                methodInfo,
                analysisSummary,
                snippet,
                contextJson,
                validationException.getMessage());
        if (fallbackSnippet == null) {
            return null;
        }
        try {
            generatedSnippetValidator.validateGeneratedSnippet(config, classInfo, fallbackSnippet, methodInfo, analysisSummary, moduleConfig);
            logger.info("Using deterministic constructor-local side-effect fallback for method " + methodInfo.getSignature());
            return fallbackSnippet;
        } catch (InvalidLLMResponseException ignored) {
            logger.info("Deterministic constructor-local side-effect fallback was rejected for method "
                    + methodInfo.getSignature() + ": " + ignored.getMessage());
            return null;
        }
    }

    private GeneratedTestSnippet tryDeterministicFastPath(AgentConfig config,
                                                          TestClassInfo classInfo,
                                                          TestMethodInfo methodInfo,
                                                          Analyze.AnalysisSummary analysisSummary,
                                                          JSONObject contextJson,
                                                          PipelineModuleConfig moduleConfig) {
        GeneratedTestSnippet constructorLocalFastPath = constructorLocalSideEffectFallbackBuilder.buildFastPath(classInfo,
                methodInfo,
                analysisSummary,
                contextJson);
        if (constructorLocalFastPath != null) {
            try {
                generatedSnippetValidator.validateGeneratedSnippet(config, classInfo, constructorLocalFastPath, methodInfo, analysisSummary, moduleConfig);
                logger.info("Using deterministic constructor-local fast-path for method " + methodInfo.getSignature());
                return constructorLocalFastPath;
            } catch (InvalidLLMResponseException ignored) {
                logger.info("Deterministic constructor-local fast-path was rejected for method "
                        + methodInfo.getSignature() + ": " + ignored.getMessage());
            }
        }
        GeneratedTestSnippet constructorReturnFastPath = constructorReturnSideEffectFallbackBuilder.buildFastPath(classInfo,
                methodInfo,
                analysisSummary,
                contextJson);
        if (constructorReturnFastPath != null) {
            try {
                generatedSnippetValidator.validateGeneratedSnippet(config, classInfo, constructorReturnFastPath, methodInfo, analysisSummary, moduleConfig);
                logger.info("Using deterministic constructor-return side-effect fast-path for method " + methodInfo.getSignature());
                return constructorReturnFastPath;
            } catch (InvalidLLMResponseException ignored) {
                logger.info("Deterministic constructor-return side-effect fast-path was rejected for method "
                        + methodInfo.getSignature() + ": " + ignored.getMessage());
            }
        }
        GeneratedTestSnippet optionalSideEffectFastPath = optionalSideEffectFallbackBuilder.buildFastPath(classInfo,
                methodInfo,
                analysisSummary,
                contextJson);
        if (optionalSideEffectFastPath != null) {
            try {
                generatedSnippetValidator.validateGeneratedSnippet(config, classInfo, optionalSideEffectFastPath, methodInfo, analysisSummary, moduleConfig);
                logger.info("Using deterministic optional side-effect fast-path for method " + methodInfo.getSignature());
                return optionalSideEffectFastPath;
            } catch (InvalidLLMResponseException ignored) {
                logger.info("Deterministic optional side-effect fast-path was rejected for method "
                        + methodInfo.getSignature() + ": " + ignored.getMessage());
            }
        }
        GeneratedTestSnippet fastPathSnippet = placeholderInvocationFallbackBuilder.buildFastPath(classInfo,
                methodInfo,
                analysisSummary,
                new GeneratedTestSnippet(classInfo == null ? "" : classInfo.getTestClassName(),
                        deriveDeterministicMethodName(methodInfo),
                        "",
                        List.of()));
        if (fastPathSnippet == null) {
            return null;
        }
        try {
            generatedSnippetValidator.validateGeneratedSnippet(config, classInfo, fastPathSnippet, methodInfo, analysisSummary, moduleConfig);
            logger.info("Using deterministic generation fast-path for method " + methodInfo.getSignature());
            return fastPathSnippet;
        } catch (InvalidLLMResponseException ignored) {
            return null;
        }
    }

    private GeneratedTestSnippet tryInventedStateMutatorFallback(AgentConfig config,
                                                                 TestClassInfo classInfo,
                                                                 TestMethodInfo methodInfo,
                                                                 Analyze.AnalysisSummary analysisSummary,
                                                                 PipelineModuleConfig moduleConfig,
                                                                 GeneratedTestSnippet snippet,
                                                                 InvalidLLMResponseException validationException) {
        if (validationException == null || validationException.getMessage() == null || !validationException.getMessage().contains("E102")) {
            return null;
        }
        GeneratedTestSnippet fallbackSnippet = inventedStateMutatorFallbackBuilder.build(snippet,
                analysisSummary,
                validationException.getMessage());
        if (fallbackSnippet == null) {
            return null;
        }
        try {
            generatedSnippetValidator.validateGeneratedSnippet(config, classInfo, fallbackSnippet, methodInfo, analysisSummary, moduleConfig);
            logger.info("Using deterministic invented-state-mutator fallback for method " + methodInfo.getSignature());
            return fallbackSnippet;
        } catch (InvalidLLMResponseException ignored) {
            logger.info("Deterministic invented-state-mutator fallback was rejected for method "
                    + methodInfo.getSignature() + ": " + ignored.getMessage());
            return null;
        }
    }

    private String deriveDeterministicMethodName(TestMethodInfo methodInfo) {
        if (methodInfo == null) {
            return "shouldInvokeTargetMethod";
        }
        if (methodInfo.getDeclaration() != null) {
            return "shouldInvoke" + capitalise(methodInfo.getDeclaration().getNameAsString());
        }
        try {
            MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration(methodInfo.getSignature() + " {}");
            return "shouldInvoke" + capitalise(declaration.getNameAsString());
        } catch (Exception ignored) {
            return "shouldInvokeTargetMethod";
        }
    }

    private String capitalise(String value) {
        if (value == null || value.isBlank()) {
            return "TargetMethod";
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private GeneratedTestSnippet tryPlaceholderInvocationFallback(AgentConfig config,
                                                                  TestClassInfo classInfo,
                                                                  TestMethodInfo methodInfo,
                                                                  Analyze.AnalysisSummary analysisSummary,
                                                                  PipelineModuleConfig moduleConfig,
                                                                  GeneratedTestSnippet retrySnippet,
                                                                  InvalidLLMResponseException validationException) {
        if (validationException == null || validationException.getMessage() == null || !validationException.getMessage().contains("E108")) {
            return null;
        }
        GeneratedTestSnippet fallbackSnippet = placeholderInvocationFallbackBuilder.build(classInfo,
                methodInfo,
                analysisSummary,
                retrySnippet);
        if (fallbackSnippet == null) {
            return null;
        }
        try {
            generatedSnippetValidator.validateGeneratedSnippet(config, classInfo, fallbackSnippet, methodInfo, analysisSummary, moduleConfig);
            logger.info("Using deterministic placeholder-invocation fallback for method " + methodInfo.getSignature());
            return fallbackSnippet;
        } catch (InvalidLLMResponseException ignored) {
            return null;
        }
    }
}
