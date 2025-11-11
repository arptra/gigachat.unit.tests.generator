package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON renderer that keeps formatting predictable and allows embedding raw fragments.
 */
public class PromptJsonRenderer {
    private static final String INDENT = "  ";

    public String render(Map<String, Object> root) {
        StringBuilder builder = new StringBuilder();
        writeObject(builder, root, 0);
        return builder.toString();
    }

    private void writeObject(StringBuilder builder, Map<String, Object> object, int indent) {
        builder.append('{');
        if (object.isEmpty()) {
            builder.append('}');
            return;
        }
        builder.append('\n');
        Iterator<Map.Entry<String, Object>> iterator = object.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Object> entry = iterator.next();
            indent(builder, indent + 1);
            builder.append('"').append(escape(entry.getKey())).append('"').append(": ");
            writeValue(builder, entry.getValue(), indent + 1);
            if (iterator.hasNext()) {
                builder.append(',');
            }
            builder.append('\n');
        }
        indent(builder, indent);
        builder.append('}');
    }

    private void writeArray(StringBuilder builder, List<?> values, int indent) {
        builder.append('[');
        if (values.isEmpty()) {
            builder.append(']');
            return;
        }
        builder.append('\n');
        for (int i = 0; i < values.size(); i++) {
            indent(builder, indent + 1);
            writeValue(builder, values.get(i), indent + 1);
            if (i + 1 < values.size()) {
                builder.append(',');
            }
            builder.append('\n');
        }
        indent(builder, indent);
        builder.append(']');
    }

    @SuppressWarnings("unchecked")
    private void writeValue(StringBuilder builder, Object value, int indent) {
        if (value == null) {
            builder.append("null");
            return;
        }
        if (value instanceof RawJson raw) {
            appendRaw(builder, raw, indent);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            writeObject(builder, (Map<String, Object>) map, indent);
            return;
        }
        if (value instanceof List<?> list) {
            writeArray(builder, list, indent);
            return;
        }
        if (value instanceof Boolean booleanValue) {
            builder.append(booleanValue);
            return;
        }
        if (value instanceof Number number) {
            builder.append(number);
            return;
        }
        if (value instanceof Enum<?> enumeration) {
            builder.append('"').append(escape(enumeration.name())).append('"');
            return;
        }
        builder.append('"').append(escape(value.toString())).append('"');
    }

    private void appendRaw(StringBuilder builder, RawJson raw, int indent) {
        String content = raw.value().trim();
        if (content.isEmpty()) {
            builder.append("{}");
            return;
        }
        if (!content.contains("\n")) {
            builder.append(content);
            return;
        }
        String[] lines = content.split("\r?\n");
        builder.append(lines[0].trim());
        for (int i = 1; i < lines.length; i++) {
            builder.append('\n');
            indent(builder, indent);
            builder.append(lines[i].trim());
        }
    }

    private void indent(StringBuilder builder, int level) {
        builder.append(INDENT.repeat(Math.max(level, 0)));
    }

    private String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }

    public static RawJson raw(String value) {
        return new RawJson(value == null ? "" : value);
    }

    public record RawJson(String value) {
    }
}
