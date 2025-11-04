package com.gigachat.unit.tests.generator.dto;

import java.util.Objects;

public class TestMethodInfo {
    private final String signature;
    private final String returnType;
    private final String body;

    public TestMethodInfo(String signature, String returnType, String body) {
        this.signature = Objects.requireNonNull(signature, "signature");
        this.returnType = Objects.requireNonNull(returnType, "returnType");
        this.body = Objects.requireNonNull(body, "body");
    }

    public String getSignature() {
        return signature;
    }

    public String getReturnType() {
        return returnType;
    }

    public String getBody() {
        return body;
    }
}
