package com.testagent.entrypoint.pipeline.helpers.analyze;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Serialises {@link MethodAnalysisResult} into a JSON structure consumable by the prompt builder.
 */
public class AnalysisFormatter {

    public String format(MethodAnalysisResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        appendMethodSection(builder, result.method());
        appendDependencies(builder, result.dependencies());
        appendStaticUsages(builder, result.staticUsages());
        appendInvocations(builder, result.invocations());
        appendUnresolved(builder, result.unresolved());
        appendSemanticAnalysis(builder, result.semanticAnalysis());
        builder.append('}');
        return builder.toString();
    }

    private void appendMethodSection(StringBuilder builder, MethodMetadata method) {
        builder.append("  \"method\": {\n");
        builder.append("    \"name\": \"").append(escape(method.name())).append("\",\n");
        builder.append("    \"signature\": \"").append(escape(method.signature())).append("\",\n");
        builder.append("    \"returnType\": \"").append(escape(method.returnType())).append("\"\n");
        builder.append("  },\n");
    }

    private void appendDependencies(StringBuilder builder, List<DependencyInfo> dependencies) {
        builder.append("  \"dependencies\": [");
        if (dependencies.isEmpty()) {
            builder.append("],\n");
            return;
        }
        builder.append('\n');
        for (int i = 0; i < dependencies.size(); i++) {
            DependencyInfo info = dependencies.get(i);
            builder.append("    {\"class\": \"").append(escape(info.className())).append("\", ")
                    .append("\"mockType\": \"").append(info.mockType()).append("\", ")
                    .append("\"usage\": \"").append(escape(info.context())).append("\", ")
                    .append("\"variable\": \"").append(escape(info.variableName())).append("\", ")
                    .append("\"externalDependency\": ").append(info.externalDependency()).append(", ")
                    .append("\"internalStructure\": ").append(info.internalStructure()).append('}');
            builder.append(i + 1 < dependencies.size() ? ",\n" : "\n");
        }
        builder.append("  ],\n");
    }

    private void appendStaticUsages(StringBuilder builder, List<String> staticUsages) {
        builder.append("  \"staticUsages\": ");
        appendArray(builder, staticUsages);
        builder.append(",\n");
    }

    private void appendInvocations(StringBuilder builder, List<InvocationInfo> invocations) {
        builder.append("  \"invocations\": [");
        if (invocations.isEmpty()) {
            builder.append("],\n");
            return;
        }
        builder.append('\n');
        for (int i = 0; i < invocations.size(); i++) {
            InvocationInfo info = invocations.get(i);
            builder.append("    {\"target\": \"").append(escape(info.target())).append("\", ")
                    .append("\"method\": \"").append(escape(info.methodName())).append("\", ")
                    .append("\"args\": ");
            appendArray(builder, info.argTypes());
            builder.append('}');
            builder.append(i + 1 < invocations.size() ? ",\n" : "\n");
        }
        builder.append("  ],\n");
    }

    private void appendUnresolved(StringBuilder builder, List<String> unresolved) {
        builder.append("  \"unresolved\": ");
        appendArray(builder, unresolved);
        builder.append(",\n");
    }

    private void appendSemanticAnalysis(StringBuilder builder, SemanticAnalysis semanticAnalysis) {
        builder.append("  \"semanticAnalysis\": {\n");
        appendDomainTypes(builder, semanticAnalysis.domainTypes());
        builder.append(",\n");
        appendSemanticStaticCalls(builder, semanticAnalysis.staticCalls());
        builder.append(",\n");
        appendSemanticMethods(builder, semanticAnalysis.typeMethods());
        builder.append(",\n");
        appendSemanticConstructors(builder, semanticAnalysis.typeConstructors());
        builder.append("  }\n");
    }

    private void appendDomainTypes(StringBuilder builder, List<String> domainTypes) {
        builder.append("    \"domainTypes\": ");
        appendArray(builder, domainTypes);
    }

    private void appendSemanticStaticCalls(StringBuilder builder, List<SemanticStaticCall> staticCalls) {
        builder.append("    \"staticCalls\": [");
        if (staticCalls.isEmpty()) {
            builder.append(']');
            return;
        }
        builder.append('\n');
        for (int i = 0; i < staticCalls.size(); i++) {
            SemanticStaticCall call = staticCalls.get(i);
            builder.append("      {\"ownerType\": \"").append(escape(call.ownerType())).append("\", ")
                    .append("\"methodName\": \"").append(escape(call.methodName())).append("\", ")
                    .append("\"parameterTypes\": ");
            appendArray(builder, call.parameterTypes());
            builder.append('}');
            builder.append(i + 1 < staticCalls.size() ? ",\n" : "\n");
        }
        builder.append("    ]");
    }

    private void appendSemanticMethods(StringBuilder builder, Map<String, List<SemanticTypeMethod>> typeMethods) {
        builder.append("    \"typeMethods\": {");
        if (typeMethods.isEmpty()) {
            builder.append("}\n");
            return;
        }
        builder.append('\n');
        int index = 0;
        for (Map.Entry<String, List<SemanticTypeMethod>> entry : typeMethods.entrySet()) {
            builder.append("      \"").append(escape(entry.getKey())).append("\": [");
            List<SemanticTypeMethod> methods = entry.getValue();
            if (methods.isEmpty()) {
                builder.append(']');
            } else {
                builder.append('\n');
                for (int i = 0; i < methods.size(); i++) {
                    SemanticTypeMethod method = methods.get(i);
                    builder.append("        {\"name\": \"").append(escape(method.name())).append("\", ")
                            .append("\"returnType\": \"").append(escape(method.returnType())).append("\", ")
                            .append("\"static\": ").append(method.isStatic()).append(", ")
                            .append("\"parameterTypes\": ");
                    appendArray(builder, method.parameterTypes());
                    builder.append('}');
                    builder.append(i + 1 < methods.size() ? ",\n" : "\n");
                }
                builder.append("      ]");
            }
            builder.append(++index < typeMethods.size() ? ",\n" : "\n");
        }
        builder.append("    }\n");
    }

    private void appendSemanticConstructors(StringBuilder builder,
                                            Map<String, List<SemanticTypeConstructor>> typeConstructors) {
        builder.append("    \"typeConstructors\": {");
        if (typeConstructors.isEmpty()) {
            builder.append("}\n");
            return;
        }
        builder.append('\n');
        int index = 0;
        for (Map.Entry<String, List<SemanticTypeConstructor>> entry : typeConstructors.entrySet()) {
            builder.append("      \"").append(escape(entry.getKey())).append("\": [");
            List<SemanticTypeConstructor> constructors = entry.getValue();
            if (constructors.isEmpty()) {
                builder.append(']');
            } else {
                builder.append('\n');
                for (int i = 0; i < constructors.size(); i++) {
                    SemanticTypeConstructor constructor = constructors.get(i);
                    builder.append("        {\"className\": \"").append(escape(constructor.className())).append("\", ")
                            .append("\"signature\": \"").append(escape(constructor.signature())).append("\", ")
                            .append("\"parameterTypes\": ");
                    appendArray(builder, constructor.parameterTypes());
                    builder.append('}');
                    builder.append(i + 1 < constructors.size() ? ",\n" : "\n");
                }
                builder.append("      ]");
            }
            builder.append(++index < typeConstructors.size() ? ",\n" : "\n");
        }
        builder.append("    }\n");
    }

    private void appendArray(StringBuilder builder, List<String> values) {
        builder.append('[');
        if (values.isEmpty()) {
            builder.append(']');
            return;
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (String value : values) {
            joiner.add("\"" + escape(value) + "\"");
        }
        builder.append(joiner);
        builder.append(']');
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\t", " ");
    }
}
