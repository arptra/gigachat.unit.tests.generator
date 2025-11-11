package com.testagent.entrypoint.pipeline.helpers.analyze;

import java.util.List;

/**
 * Aggregates the output of the method analysis step.
 */
public record MethodAnalysisResult(MethodMetadata method,
                                   List<DependencyInfo> dependencies,
                                   List<InvocationInfo> invocations,
                                   List<String> staticUsages,
                                   List<String> unresolved) {

    public MethodAnalysisResult {
        method = method == null ? new MethodMetadata("method", "method()", "void") : method;
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        invocations = invocations == null ? List.of() : List.copyOf(invocations);
        staticUsages = staticUsages == null ? List.of() : List.copyOf(staticUsages);
        unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
    }
}
