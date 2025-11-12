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
        registry.registerMethod("com.example.UserRepository", "User save(User user)");
        registry.registerMethod("UserRepository", "Optional<User> findByUsername(String username)");
        ConstructorMetadata constructor = new ConstructorMetadata(
                "User(String username, String email)",
                List.of(
                        new ParameterMetadata("username", "String", List.of("final")),
                        new ParameterMetadata("email", "String", List.of())
                )
        );
        registry.registerConstructor("com.example.User", constructor);

        assertTrue(registry.methodExists("UserRepository", "save", 1));
        assertTrue(registry.methodExists("com.example.UserRepository", "findByUsername", 1));
        assertTrue(registry.constructorExists("User", 2));
        assertFalse(registry.methodExists("UserRepository", "missing", 0));
        assertFalse(registry.constructorExists("User", 1));

        Map<String, List<ConstructorMetadata>> detailed = registry.getConstructorsDetailed();
        assertTrue(detailed.containsKey("User"));
        ConstructorMetadata metadata = detailed.get("User").get(0);
        assertEquals("User(String username, String email)", metadata.signature());
        assertEquals(2, metadata.parameters().size());
        assertEquals("username", metadata.parameters().get(0).name());
        assertEquals("String", metadata.parameters().get(0).type());
        assertTrue(metadata.parameters().get(0).modifiers().contains("final"));

        List<ConstructorMetadata> constructorsForClass = registry.getConstructorsForClass("User");
        assertEquals(1, constructorsForClass.size());
        assertEquals("User(String username, String email)", constructorsForClass.get(0).signature());
    }
}
