package com.gigachat.unit.tests.generator.analysis.semantic;

import com.gigachat.unit.tests.generator.analysis.api.ConstructorInfo;
import com.gigachat.unit.tests.generator.analysis.api.MethodAnalysisDTO;
import com.gigachat.unit.tests.generator.analysis.api.MethodAnalyzer;
import com.gigachat.unit.tests.generator.analysis.api.MethodInfo;
import com.gigachat.unit.tests.generator.analysis.api.StaticDependency;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class SemanticMethodAnalyzer implements MethodAnalyzer {
    private final SemanticNodeExtractor nodeExtractor;
    private final DependencyClassifier dependencyClassifier;
    private final ConstructorCollector constructorCollector;
    private final MethodCollector methodCollector;
    private final StaticDependencyResolver staticDependencyResolver;

    public SemanticMethodAnalyzer() {
        this(new SemanticNodeExtractor(),
                new DependencyClassifier(),
                new ConstructorCollector(),
                new MethodCollector(),
                new StaticDependencyResolver());
    }

    public SemanticMethodAnalyzer(SemanticNodeExtractor nodeExtractor,
                                  DependencyClassifier dependencyClassifier,
                                  ConstructorCollector constructorCollector,
                                  MethodCollector methodCollector,
                                  StaticDependencyResolver staticDependencyResolver) {
        this.nodeExtractor = nodeExtractor;
        this.dependencyClassifier = dependencyClassifier;
        this.constructorCollector = constructorCollector;
        this.methodCollector = methodCollector;
        this.staticDependencyResolver = staticDependencyResolver;
    }

    @Override
    public MethodAnalysisDTO analyze(TestMethodInfo info) {
        if (info == null || info.getDeclaration() == null) {
            return MethodAnalysisDTO.empty();
        }
        SemanticNodes nodes = nodeExtractor.extract(info.getDeclaration());
        ClassifiedDependencies classifiedDependencies = dependencyClassifier.classify(nodes, info);
        List<ConstructorInfo> constructors = constructorCollector.collect(nodes.getCreations());
        Map<String, List<MethodInfo>> methods = methodCollector.collect(classifiedDependencies);
        List<StaticDependency> staticDependencies = staticDependencyResolver.resolve(nodes.getStaticCalls());
        Set<String> collaboratorTypes = classifiedDependencies.getCollaboratorTypes();
        Set<String> domainTypes = classifiedDependencies.getDomainTypes();

        SemanticAnalysisResult semanticResult = new SemanticAnalysisResult(constructors,
                methods,
                staticDependencies,
                collaboratorTypes,
                domainTypes);
        return new MethodAnalysisDTO(semanticResult.getConstructors(),
                semanticResult.getMethods(),
                semanticResult.getStaticDependencies(),
                semanticResult.getCollaboratorTypes(),
                semanticResult.getDomainTypes());
    }
}
