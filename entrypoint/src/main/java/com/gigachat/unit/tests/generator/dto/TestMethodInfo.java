package com.gigachat.unit.tests.generator.dto;

import java.util.Objects;

public record TestMethodInfo(String signature, String returnType, String body) {

    public TestMethodInfo {
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(body, "body");
    }
}
