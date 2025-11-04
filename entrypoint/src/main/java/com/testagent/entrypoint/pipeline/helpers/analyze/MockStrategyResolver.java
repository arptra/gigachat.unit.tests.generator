package com.testagent.entrypoint.pipeline.helpers.analyze;

import com.gigachat.unit.tests.generator.config.AnalysisConfig;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.dto.MockTarget;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.ThisExpr;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Heuristics used to infer the most appropriate mock type for a dependency usage.
 */
public class MockStrategyResolver {

    public MockType resolve(ObjectCreationExpr expression) {
        return MockType.CONSTRUCTOR;
    }

    public MockType resolve(FieldAccessExpr expression) {
        Expression scope = expression.getScope();
        if (scope instanceof ThisExpr || scope instanceof NameExpr) {
            return MockType.FIELD;
        }
        return MockType.UNKNOWN;
    }

    public MockType resolve(MethodCallExpr expression) {
        if (expression.getScope().isEmpty()) {
            return MockType.UNKNOWN;
        }
        Expression scope = expression.getScope().get();
        if (scope instanceof MethodCallExpr) {
            return MockType.CHAIN;
        }
        if (isLikelyStaticScope(scope)) {
            return MockType.STATIC;
        }
        return MockType.FIELD;
    }

    public MockPlan createPlan(MethodAnalysisResult analysis, AnalysisConfig config) {
        AnalysisConfig effectiveConfig = config == null ? AnalysisConfig.from(Map.of()) : config;
        FilterPolicy filterPolicy = new FilterPolicy(effectiveConfig);
        Map<String, MockTarget> targets = new LinkedHashMap<>();
        Set<String> shouldMock = new LinkedHashSet<>();
        Set<String> shouldNotMock = new LinkedHashSet<>();

        boolean hasFieldMocks = false;
        boolean hasChainMocks = false;
        boolean hasStaticMocks = false;
        boolean hasConstructorOnly = true;
        boolean hasUnknown = false;

        for (DependencyInfo dependency : analysis.dependencies()) {
            MockType type = dependency.mockType();
            String dependencyKey = dependency.className() + "#" + dependency.variableName();
            switch (type) {
                case CONSTRUCTOR -> {
                    hasConstructorOnly &= true;
                    shouldNotMock.add(formatDependencyName(dependency));
                }
                case FIELD -> {
                    hasConstructorOnly = false;
                    if (filterPolicy.shouldSkip(dependency.className())) {
                        shouldNotMock.add(formatDependencyName(dependency));
                    } else {
                        hasFieldMocks = true;
                        shouldMock.add(dependency.variableName());
                        targets.putIfAbsent(dependencyKey, new MockTarget(dependency.className(), dependency.variableName()));
                    }
                }
                case STATIC -> {
                    hasConstructorOnly = false;
                    if (filterPolicy.shouldSkipStatic(dependency.className())) {
                        shouldNotMock.add(formatStaticName(dependency));
                    } else {
                        hasStaticMocks = true;
                        String staticIdentifier = dependency.className();
                        shouldMock.add(staticIdentifier);
                        targets.putIfAbsent(dependencyKey, new MockTarget(dependency.className(), dependency.variableName()));
                    }
                }
                case CHAIN -> {
                    hasConstructorOnly = false;
                    if (filterPolicy.shouldSkip(dependency.className())) {
                        shouldNotMock.add(formatDependencyName(dependency));
                    } else {
                        hasChainMocks = true;
                        shouldMock.add(dependency.variableName());
                        targets.putIfAbsent(dependencyKey, new MockTarget(dependency.className(), dependency.variableName()));
                    }
                }
                default -> {
                    hasConstructorOnly = false;
                    hasUnknown = true;
                    if (filterPolicy.shouldSkip(dependency.className())) {
                        shouldNotMock.add(formatDependencyName(dependency));
                    } else {
                        shouldMock.add(dependency.variableName());
                    }
                }
            }
        }

        for (String staticUsage : analysis.staticUsages()) {
            if (filterPolicy.shouldSkipStatic(staticUsage)) {
                shouldNotMock.add(staticUsage);
            }
        }

        MockStrategy strategy = determineStrategy(hasStaticMocks,
                hasChainMocks,
                hasFieldMocks,
                hasUnknown,
                hasConstructorOnly,
                !shouldMock.isEmpty(),
                !analysis.staticUsages().isEmpty());

        return new MockPlan(List.copyOf(targets.values()),
                strategy,
                List.copyOf(shouldMock),
                List.copyOf(shouldNotMock));
    }

    private MockStrategy determineStrategy(boolean hasStaticMocks,
                                           boolean hasChainMocks,
                                           boolean hasFieldMocks,
                                           boolean hasUnknown,
                                           boolean hasConstructorOnly,
                                           boolean hasMockTargets,
                                           boolean hasStaticUsages) {
        if (hasStaticMocks) {
            return MockStrategy.STATIC;
        }
        if (hasChainMocks) {
            return MockStrategy.CHAIN_PARTIAL;
        }
        if (hasFieldMocks) {
            return MockStrategy.MOCKITO;
        }
        if (hasConstructorOnly && hasStaticUsages) {
            return MockStrategy.STATIC_SKIP;
        }
        if (hasConstructorOnly && hasMockTargets) {
            return MockStrategy.SPY;
        }
        if (hasUnknown && hasMockTargets) {
            return MockStrategy.LLM_ASSISTED;
        }
        if (hasConstructorOnly) {
            return MockStrategy.SPY;
        }
        return hasMockTargets ? MockStrategy.MOCKITO : MockStrategy.NONE;
    }

    private boolean isLikelyStaticScope(Expression scope) {
        if (scope instanceof NameExpr nameExpr) {
            return startsWithUppercase(nameExpr.getName());
        }
        if (scope instanceof FieldAccessExpr fieldAccessExpr) {
            return isLikelyStaticScope(fieldAccessExpr.getScope());
        }
        return false;
    }

    private boolean startsWithUppercase(SimpleName name) {
        if (name == null) {
            return false;
        }
        String identifier = name.getIdentifier();
        return !identifier.isEmpty() && Character.isUpperCase(identifier.charAt(0));
    }

    private String formatDependencyName(DependencyInfo dependency) {
        String variable = dependency.variableName();
        if (variable != null && !variable.isBlank()) {
            return variable;
        }
        return dependency.className();
    }

    private String formatStaticName(DependencyInfo dependency) {
        if (dependency.context() != null && dependency.context().contains(".")) {
            return dependency.context();
        }
        return dependency.className();
    }

    private static final class FilterPolicy {
        private static final List<String> STANDARD_PREFIXES = List.of(
                "java.",
                "javax.",
                "jakarta.",
                "org.slf4j.",
                "kotlin."
        );

        private final AnalysisConfig config;

        private FilterPolicy(AnalysisConfig config) {
            this.config = config;
        }

        boolean shouldSkip(String qualifiedName) {
            if (qualifiedName == null || qualifiedName.isBlank()) {
                return false;
            }
            String trimmed = qualifiedName.trim();
            if (config.isExcluded(trimmed)) {
                return true;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            return STANDARD_PREFIXES.stream().anyMatch(lower::startsWith);
        }

        boolean shouldSkipStatic(String qualifiedName) {
            if (qualifiedName == null) {
                return false;
            }
            return shouldSkip(qualifiedName) || qualifiedName.endsWith("Util") || qualifiedName.endsWith("Utils");
        }
    }
}
