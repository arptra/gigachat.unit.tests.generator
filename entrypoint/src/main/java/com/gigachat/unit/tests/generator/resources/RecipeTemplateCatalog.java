package com.gigachat.unit.tests.generator.resources;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads recipe templates from editable resources and renders them with bindings.
 */
public class RecipeTemplateCatalog {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(?<name>[^}]+)}");

    private final String resourcePath;
    private final String catalogName;
    private final Map<String, Object> templates;

    public RecipeTemplateCatalog(String resourcePath, String catalogName) {
        this(new ResourceTextLoader(), resourcePath, catalogName);
    }

    public RecipeTemplateCatalog(ResourceTextLoader loader, String resourcePath, String catalogName) {
        Objects.requireNonNull(loader, "loader");
        this.resourcePath = Objects.requireNonNull(resourcePath, "resourcePath");
        this.catalogName = catalogName == null || catalogName.isBlank() ? "recipe" : catalogName;
        JSONObject root = new JSONObject(loader.readText(resourcePath));
        this.templates = jsonObjectToMap(root);
    }

    public Map<String, Object> render(String templateId, Map<String, Object> bindings) {
        Objects.requireNonNull(templateId, "templateId");
        @SuppressWarnings("unchecked")
        Map<String, Object> template = (Map<String, Object>) templates.get(templateId);
        if (template == null || template.isEmpty()) {
            throw new IllegalArgumentException("Unknown " + catalogName + " recipe template: " + templateId);
        }
        Object rendered = renderValue(template, bindings == null ? Map.of() : bindings);
        if (!(rendered instanceof Map<?, ?> renderedMap)) {
            throw new IllegalStateException(catalogName + " recipe template did not render to an object: " + templateId);
        }
        return castObjectMap(renderedMap);
    }

    public String resourcePath() {
        return resourcePath;
    }

    private Object renderValue(Object template, Map<String, Object> bindings) {
        if (template instanceof String text) {
            return substitute(text, bindings);
        }
        if (template instanceof Map<?, ?> map) {
            if (map.size() == 1 && map.containsKey("$bind")) {
                Object key = map.get("$bind");
                Object bound = bindings.get(key == null ? "" : key.toString());
                return deepCopy(bound);
            }
            LinkedHashMap<String, Object> rendered = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                rendered.put(String.valueOf(entry.getKey()), renderValue(entry.getValue(), bindings));
            }
            return rendered;
        }
        if (template instanceof List<?> list) {
            List<Object> rendered = new ArrayList<>(list.size());
            for (Object value : list) {
                rendered.add(renderValue(value, bindings));
            }
            return List.copyOf(rendered);
        }
        return template;
    }

    private Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object entry : list) {
                copy.add(deepCopy(entry));
            }
            return List.copyOf(copy);
        }
        return value;
    }

    private String substitute(String template, Map<String, Object> bindings) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group("name");
            Object bound = bindings.get(key);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(bound == null ? "" : String.valueOf(bound)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private Map<String, Object> jsonObjectToMap(JSONObject object) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (String key : object.keySet()) {
            map.put(key, jsonValueToJava(object.get(key)));
        }
        return Map.copyOf(map);
    }

    private Object jsonValueToJava(Object value) {
        if (value instanceof JSONObject nestedObject) {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            for (String key : nestedObject.keySet()) {
                map.put(key, jsonValueToJava(nestedObject.get(key)));
            }
            return map;
        }
        if (value instanceof JSONArray array) {
            List<Object> list = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                list.add(jsonValueToJava(array.get(index)));
            }
            return List.copyOf(list);
        }
        return value;
    }

    private Map<String, Object> castObjectMap(Map<?, ?> source) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Map.copyOf(map);
    }
}
