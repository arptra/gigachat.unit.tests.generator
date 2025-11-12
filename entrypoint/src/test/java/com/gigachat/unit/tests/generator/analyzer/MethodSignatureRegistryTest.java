package com.gigachat.unit.tests.generator.analyzer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodSignatureRegistryTest {

    @Test
    void registersMethodsAndConstructorsBySimpleName() {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("com.example.UserRepository", "save(User user)");
        registry.registerMethod("UserRepository", "findByUsername(String username)");
        registry.registerConstructor("com.example.User", "User(String username, String email)");

        assertTrue(registry.methodExists("UserRepository", "save", 1));
        assertTrue(registry.methodExists("com.example.UserRepository", "findByUsername", 1));
        assertTrue(registry.constructorExists("User", 2));
        assertFalse(registry.methodExists("UserRepository", "missing", 0));
        assertFalse(registry.constructorExists("User", 3));
    }
}
