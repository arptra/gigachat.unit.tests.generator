package com.gigachat.unit.tests.generator.analysis.semantic;

import com.github.javaparser.ast.expr.Expression;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stores type information discovered while traversing a method body.
 */
public class SemanticTypeContext {
    private final Map<String, ResolvedType> parameters = new LinkedHashMap<>();
    private final Map<String, ResolvedType> fields = new LinkedHashMap<>();
    private final Map<String, ResolvedType> locals = new LinkedHashMap<>();
    private final Map<String, ResolvedType> lambdaParameters = new LinkedHashMap<>();
    private final Map<Expression, ResolvedType> expressionTypes = new IdentityHashMap<>();

    public void registerParameter(String name, ResolvedType type) {
        register(parameters, name, type);
    }

    public void registerField(String name, ResolvedType type) {
        register(fields, name, type);
    }

    public void registerLocal(String name, ResolvedType type) {
        register(locals, name, type);
    }

    public void registerLambdaParameter(String name, ResolvedType type) {
        register(lambdaParameters, name, type);
    }

    public void removeLambdaParameter(String name) {
        lambdaParameters.remove(name);
    }

    public Optional<ResolvedType> getLambdaParameter(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(lambdaParameters.get(name));
    }

    public Optional<ResolvedType> resolveSymbol(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String symbol = name.trim();
        if (locals.containsKey(symbol)) {
            return Optional.ofNullable(locals.get(symbol));
        }
        if (lambdaParameters.containsKey(symbol)) {
            return Optional.ofNullable(lambdaParameters.get(symbol));
        }
        if (parameters.containsKey(symbol)) {
            return Optional.ofNullable(parameters.get(symbol));
        }
        return Optional.ofNullable(fields.get(symbol));
    }

    public Optional<ResolvedType> resolveField(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(fields.get(name));
    }

    public void registerExpressionType(Expression expression, ResolvedType type) {
        if (expression == null || type == null || type.isUnknown()) {
            return;
        }
        expressionTypes.put(expression, type);
    }

    public Optional<ResolvedType> getExpressionType(Expression expression) {
        if (expression == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(expressionTypes.get(expression));
    }

    public Map<String, ResolvedType> getParameters() {
        return parameters;
    }

    public Map<String, ResolvedType> getFields() {
        return fields;
    }

    public Map<String, ResolvedType> getLocals() {
        return locals;
    }

    private void register(Map<String, ResolvedType> target, String name, ResolvedType type) {
        if (name == null || name.isBlank() || type == null) {
            return;
        }
        target.put(name.trim(), type);
    }
}
