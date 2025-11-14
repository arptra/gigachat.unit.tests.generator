package com.gigachat.unit.tests.generator.prompt.deep;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze.AnalysisSummary;
import com.gigachat.unit.tests.generator.pipeline.helpers.PromptBuilder;
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.InstructionComposerFactory;
import com.gigachat.unit.tests.generator.pipeline.helpers.prompt.PromptJsonRenderer;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticAnalysis;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticStaticCall;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticTypeConstructor;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticTypeMethod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Prompt builder that augments the JSON context with semantic analysis details.
 */
public class DeepPromptContextBuilder extends PromptBuilder {
    public DeepPromptContextBuilder() {
        super();
    }

    public DeepPromptContextBuilder(InstructionComposerFactory composerFactory, PromptJsonRenderer jsonRenderer) {
        super(composerFactory, jsonRenderer);
    }

    @Override
    protected void customizeContext(Map<String, Object> root,
                                    AgentConfig config,
                                    TestClassInfo classInfo,
                                    TestMethodInfo methodInfo,
                                    String skeletonJson,
                                    AnalysisSummary summary) {
        if (summary == null || summary.methodAnalysis() == null) {
            return;
        }
        MethodAnalysisResult analysisResult = summary.methodAnalysis();
        SemanticAnalysis semanticAnalysis = analysisResult.semanticAnalysis();
        if (semanticAnalysis == null || semanticAnalysis.isEmpty()) {
            return;
        }
        mergeAvailableMethods(root, semanticAnalysis);
        mergeAvailableConstructors(root, semanticAnalysis);
        root.put("semanticAnalysis", buildSemanticBlock(semanticAnalysis));
    }

    private void mergeAvailableMethods(Map<String, Object> root, SemanticAnalysis semanticAnalysis) {
        @SuppressWarnings("unchecked")
        Map<String, List<String>> availableMethods = (Map<String, List<String>>) root.computeIfAbsent("availableMethods",
                key -> new LinkedHashMap<>());
        semanticAnalysis.typeMethods().forEach((typeName, methods) -> {
            List<String> formatted = new ArrayList<>();
            for (SemanticTypeMethod method : methods) {
                if (method == null || method.name().isBlank()) {
                    continue;
                }
                String signature = formatMethodSignature(method);
                if (!signature.isBlank()) {
                    formatted.add(signature);
                }
            }
            if (formatted.isEmpty()) {
                return;
            }
            availableMethods.merge(typeName, new ArrayList<>(formatted), (existing, additional) -> {
                List<String> merged = new ArrayList<>(existing);
                for (String entry : additional) {
                    if (!merged.contains(entry)) {
                        merged.add(entry);
                    }
                }
                return merged;
            });
        });
    }

    private void mergeAvailableConstructors(Map<String, Object> root, SemanticAnalysis semanticAnalysis) {
        @SuppressWarnings("unchecked")
        Map<String, Object> constructorsBlock = (Map<String, Object>) root.computeIfAbsent("availableConstructors",
                key -> new LinkedHashMap<>());
        semanticAnalysis.typeConstructors().forEach((typeName, constructors) -> {
            if (constructors == null || constructors.isEmpty()) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> existing = constructorsBlock.containsKey(typeName)
                    ? new ArrayList<>((List<Map<String, Object>>) constructorsBlock.get(typeName))
                    : new ArrayList<>();
            LinkedHashSet<String> signatures = new LinkedHashSet<>();
            for (Map<String, Object> descriptor : existing) {
                String signature = Objects.toString(descriptor.get("signature"), "");
                if (!signature.isBlank()) {
                    signatures.add(signature);
                }
            }
            for (SemanticTypeConstructor constructor : constructors) {
                if (constructor == null || constructor.signature().isBlank()) {
                    continue;
                }
                if (!signatures.add(constructor.signature())) {
                    continue;
                }
                existing.add(toConstructorDescriptor(constructor));
            }
            constructorsBlock.put(typeName, existing);
        });
    }

    private Map<String, Object> toConstructorDescriptor(SemanticTypeConstructor constructor) {
        LinkedHashMap<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("signature", constructor.signature());
        if (!constructor.parameterTypes().isEmpty()) {
            List<Map<String, Object>> parameters = new ArrayList<>();
            int index = 1;
            for (String parameterType : constructor.parameterTypes()) {
                LinkedHashMap<String, Object> parameterBlock = new LinkedHashMap<>();
                parameterBlock.put("type", parameterType);
                parameterBlock.put("name", "arg" + index++);
                parameters.add(parameterBlock);
            }
            descriptor.put("parameters", parameters);
        }
        return descriptor;
    }

    private Map<String, Object> buildSemanticBlock(SemanticAnalysis semanticAnalysis) {
        LinkedHashMap<String, Object> block = new LinkedHashMap<>();
        block.put("domainTypes", semanticAnalysis.domainTypes());
        block.put("staticCalls", buildStaticCalls(semanticAnalysis.staticCalls()));
        block.put("typeMethods", buildMethodBlock(semanticAnalysis.typeMethods()));
        block.put("typeConstructors", buildConstructorBlock(semanticAnalysis.typeConstructors()));
        return block;
    }

    private List<Map<String, Object>> buildStaticCalls(List<SemanticStaticCall> staticCalls) {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (SemanticStaticCall call : staticCalls) {
            if (call == null || call.ownerType().isBlank() || call.methodName().isBlank()) {
                continue;
            }
            LinkedHashMap<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("ownerType", call.ownerType());
            descriptor.put("methodName", call.methodName());
            descriptor.put("parameterTypes", call.parameterTypes());
            calls.add(descriptor);
        }
        return calls;
    }

    private Map<String, Object> buildMethodBlock(Map<String, List<SemanticTypeMethod>> typeMethods) {
        LinkedHashMap<String, Object> block = new LinkedHashMap<>();
        typeMethods.forEach((typeName, methods) -> {
            List<Map<String, Object>> descriptors = new ArrayList<>();
            for (SemanticTypeMethod method : methods) {
                if (method == null || method.name().isBlank()) {
                    continue;
                }
                LinkedHashMap<String, Object> descriptor = new LinkedHashMap<>();
                descriptor.put("name", method.name());
                descriptor.put("returnType", method.returnType());
                descriptor.put("static", method.isStatic());
                descriptor.put("parameterTypes", method.parameterTypes());
                descriptors.add(descriptor);
            }
            if (!descriptors.isEmpty()) {
                block.put(typeName, descriptors);
            }
        });
        return block;
    }

    private Map<String, Object> buildConstructorBlock(Map<String, List<SemanticTypeConstructor>> typeConstructors) {
        LinkedHashMap<String, Object> block = new LinkedHashMap<>();
        typeConstructors.forEach((typeName, constructors) -> {
            List<Map<String, Object>> descriptors = new ArrayList<>();
            for (SemanticTypeConstructor constructor : constructors) {
                descriptors.add(toConstructorDescriptor(constructor));
            }
            if (!descriptors.isEmpty()) {
                block.put(typeName, descriptors);
            }
        });
        return block;
    }

    private String formatMethodSignature(SemanticTypeMethod method) {
        StringBuilder builder = new StringBuilder();
        if (!method.returnType().isBlank()) {
            builder.append(method.returnType()).append(' ');
        }
        builder.append(method.name()).append('(');
        List<String> params = method.parameterTypes();
        for (int i = 0; i < params.size(); i++) {
            builder.append(params.get(i));
            if (i + 1 < params.size()) {
                builder.append(", ");
            }
        }
        builder.append(')');
        return builder.toString();
    }
}
