package com.gigachat.unit.tests.generator.dto;

import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.Objects;

public class TestMethodInfo {
    private final String signature;
    private final String returnType;
    private final String body;
    private final MethodDeclaration declaration;

    public TestMethodInfo(String signature, String returnType, String body) {
        this(signature, returnType, body, null);
    }

    public TestMethodInfo(String signature,
                          String returnType,
                          String body,
                          MethodDeclaration declaration) {
        this.signature = Objects.requireNonNull(signature, "signature");
        this.returnType = Objects.requireNonNull(returnType, "returnType");
        this.body = Objects.requireNonNull(body, "body");
        this.declaration = declaration;
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

    public MethodDeclaration getDeclaration() {
        return declaration;
    }
}
