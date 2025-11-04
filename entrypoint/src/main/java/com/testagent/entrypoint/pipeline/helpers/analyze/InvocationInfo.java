package com.testagent.entrypoint.pipeline.helpers.analyze;

import java.util.List;

/**
 * Describes a method invocation discovered in the analysed method body.
 */
public record InvocationInfo(String target,
                             String methodName,
                             List<String> argTypes) {

    public InvocationInfo {
        target = normalise(target, "this");
        methodName = normalise(methodName, "invoke");
        argTypes = argTypes == null ? List.of() : List.copyOf(argTypes);
    }

    private static String normalise(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }
}
