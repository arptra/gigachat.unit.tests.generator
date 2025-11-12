package com.gigachat.unit.tests.generator.analyzer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodSignatureRegistryTest {

    @Test
    void registersMethodsAndConstructorsBySimpleName() {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("com.example.UserRepository", "save(User user)");
        registry.registerMethod("UserRepository", "findByUsername(String username)");
        ConstructorMetadata constructor = new ConstructorMetadata(
                "public User(String username, String email)",
                List.of(
                        new ParameterMetadata("username", "String", "unique user identifier"),
                        new ParameterMetadata("email", "String", "contact address")
                ),
                List.of()
        );
        registry.registerConstructor("com.example.User", constructor);

        assertTrue(registry.methodExists("UserRepository", "save", 1));
        assertTrue(registry.methodExists("com.example.UserRepository", "findByUsername", 1));
        assertTrue(registry.constructorExists("User", 2));
        assertFalse(registry.methodExists("UserRepository", "missing", 0));
        assertFalse(registry.constructorExists("User", 3));

        Map<String, List<ConstructorMetadata>> detailed = registry.getConstructorsDetailed();
        assertTrue(detailed.containsKey("User"));
        ConstructorMetadata metadata = detailed.get("User").get(0);
        assertEquals("public User(String username, String email)", metadata.signature());
        assertEquals(2, metadata.parameters().size());
        assertEquals("username", metadata.parameters().get(0).name());
        assertEquals("String", metadata.parameters().get(0).type());
        assertEquals("unique user identifier", metadata.parameters().get(0).description());
    }
}
