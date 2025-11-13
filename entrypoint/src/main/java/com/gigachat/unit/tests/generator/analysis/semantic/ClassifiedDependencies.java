package com.gigachat.unit.tests.generator.analysis.semantic;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ClassifiedDependencies {
    private final Map<DependencyType, List<MethodCallExpr>> methodCalls;
    private final Map<String, String> symbolTypes;
    private final Set<String> collaboratorTypes;
    private final Set<String> domainTypes;
    private final List<Expression> returnExpressions;

    public ClassifiedDependencies(Map<DependencyType, List<MethodCallExpr>> methodCalls,
                                  Map<String, String> symbolTypes,
                                  Set<String> collaboratorTypes,
                                  Set<String> domainTypes,
                                  List<Expression> returnExpressions) {
        Map<DependencyType, List<MethodCallExpr>> copy = new EnumMap<>(DependencyType.class);
        if (methodCalls != null) {
            methodCalls.forEach((type, calls) -> copy.put(type, calls == null ? List.of() : List.copyOf(calls)));
        }
        this.methodCalls = Collections.unmodifiableMap(copy);
        this.symbolTypes = symbolTypes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(symbolTypes));
        this.collaboratorTypes = collaboratorTypes == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(collaboratorTypes));
        this.domainTypes = domainTypes == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(domainTypes));
        this.returnExpressions = returnExpressions == null ? List.of() : List.copyOf(returnExpressions);
    }

    public List<MethodCallExpr> getCalls(DependencyType type) {
        return methodCalls.getOrDefault(type, List.of());
    }

    public Map<String, String> getSymbolTypes() {
        return symbolTypes;
    }

    public Set<String> getCollaboratorTypes() {
        return collaboratorTypes;
    }

    public Set<String> getDomainTypes() {
        return domainTypes;
    }

    public List<Expression> getReturnExpressions() {
        return returnExpressions;
    }
}
