package com.testagent.entrypoint.pipeline.helpers.analyze;

import java.util.List;
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
                    .append("\"variable\": \"").append(escape(info.variableName())).append("\"}");
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
        builder.append('\n');
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
