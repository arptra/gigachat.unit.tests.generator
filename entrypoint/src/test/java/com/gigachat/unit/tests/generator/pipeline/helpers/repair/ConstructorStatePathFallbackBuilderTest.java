package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructorStatePathFallbackBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRewriteThreeArgumentUserConstructorIntoAllowedConstructorAndMutators() {
        ConstructorStatePathFallbackBuilder builder =
                new ConstructorStatePathFallbackBuilder(new PipelineLogger(tempDir));
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserServiceTest",
                "testAverageLoginAttemptsWithUsers",
                """
                        @Test
                        void testAverageLoginAttemptsWithUsers() {
                            List<User> users = new ArrayList<>();
                            users.add(new User("Alice", "alice@example.com", 5));
                            users.add(new User("Bob", "bob@example.com", 3));
                        }
                        """,
                List.of());

        GeneratedTestSnippet fallback = builder.build(
                snippet,
                activeUserSummary(),
                "E104: Missing constructor metadata for User(\"Alice\", \"alice@example.com\", 5)");

        assertNotNull(fallback);
        assertFalse(fallback.methodBody().contains("new User(\"Alice\", \"alice@example.com\", 5)"));
        assertTrue(fallback.methodBody().contains("generatedUser1.incrementAttempts();"));
        assertTrue(fallback.methodBody().contains("users.add(generatedUser1);"));
    }

    @Test
    void shouldRewriteNoArgUserAndGuessedSettersIntoAllowedConstructorPath() {
        ConstructorStatePathFallbackBuilder builder =
                new ConstructorStatePathFallbackBuilder(new PipelineLogger(tempDir));
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserServiceTest",
                "testActiveUsernames",
                """
                        @Test
                        void testActiveUsernames() {
                            var alice = new User();
                            alice.setUsername("Alice");
                            alice.setActive(true);
                            var bob = new User();
                            bob.setUsername("Bob");
                            bob.setActive(false);
                        }
                        """,
                List.of());

        GeneratedTestSnippet fallback = builder.build(
                snippet,
                activeUserSummary(),
                "E104: Missing constructor metadata for User(); E102: Invented method User.setActive(true)");

        assertNotNull(fallback);
        assertTrue(fallback.methodBody().contains("var alice = new User(\"Alice\", \"alice@example.com\");"));
        assertTrue(fallback.methodBody().contains("var bob = new User(\"Bob\", \"bob@example.com\");"));
        assertTrue(fallback.methodBody().contains("bob.deactivate();"));
        assertFalse(fallback.methodBody().contains("setActive("));
        assertFalse(fallback.methodBody().contains("setUsername("));
    }

    @Test
    void shouldAlsoRewriteValidConstructorPlusInventedStateSetterOnPureE102() {
        ConstructorStatePathFallbackBuilder builder =
                new ConstructorStatePathFallbackBuilder(new PipelineLogger(tempDir));
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserServiceTest",
                "testActiveUsernames",
                """
                        @Test
                        void testActiveUsernames() {
                            User activeUser = new User("Alice", "alice@example.com");
                            activeUser.setActive(true);
                            User inactiveUser = new User("Bob", "bob@example.com");
                            inactiveUser.setActive(false);
                        }
                        """,
                List.of());

        GeneratedTestSnippet fallback = builder.build(
                snippet,
                activeUserSummary(),
                "E102: Invented method User.setActive(true); E102: Invented method User.setActive(false)");

        assertNotNull(fallback);
        assertFalse(fallback.methodBody().contains("setActive("));
        assertTrue(fallback.methodBody().contains("inactiveUser.deactivate();"));
    }

    @Test
    void shouldRewriteChainedConstructorStateMutatorsUsedAsCollectionValues() {
        ConstructorStatePathFallbackBuilder builder =
                new ConstructorStatePathFallbackBuilder(new PipelineLogger(tempDir));
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserServiceTest",
                "testAverageLoginAttemptsWithUsers",
                """
                        @Test
                        void testAverageLoginAttemptsWithUsers() {
                            List<User> users = new ArrayList<>();
                            users.add(new User("Alice", "alice@example.com").setLoginAttempts(4));
                            users.add(new User("Bob", "bob@example.com").setActive(false));
                        }
                        """,
                List.of());

        GeneratedTestSnippet fallback = builder.build(
                snippet,
                activeUserSummary(),
                "E102: Invented method User.setLoginAttempts(4); E102: Invented method User.setActive(false)");

        assertNotNull(fallback);
        assertFalse(fallback.methodBody().contains("setLoginAttempts("));
        assertFalse(fallback.methodBody().contains("setActive("));
        assertTrue(fallback.methodBody().contains("var generatedUser1 = new User(\"Alice\", \"alice@example.com\");"));
        assertTrue(fallback.methodBody().contains("users.add(generatedUser1);"));
        assertTrue(fallback.methodBody().contains("var generatedUser2 = new User(\"Bob\", \"bob@example.com\");"));
        assertTrue(fallback.methodBody().contains("generatedUser2.deactivate();"));
        assertTrue(fallback.methodBody().contains("users.add(generatedUser2);"));
        assertTrue(fallback.methodBody().indexOf("generatedUser1.incrementAttempts();")
                != fallback.methodBody().lastIndexOf("generatedUser1.incrementAttempts();"));
    }

    @Test
    void shouldRewriteChainedVoidMutatorUsedAsDeclarationValue() {
        ConstructorStatePathFallbackBuilder builder =
                new ConstructorStatePathFallbackBuilder(new PipelineLogger(tempDir));
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserServiceTest",
                "testInactiveUser",
                """
                        @Test
                        void testInactiveUser() {
                            User inactiveUser = new User("Bob", "bob@example.com").deactivate();
                        }
                        """,
                List.of());

        GeneratedTestSnippet fallback = builder.build(
                snippet,
                activeUserSummary(),
                "E113: void mutator used as value expression new User(\"Bob\", \"bob@example.com\").deactivate()");

        assertNotNull(fallback);
        assertFalse(fallback.methodBody().contains("new User(\"Bob\", \"bob@example.com\").deactivate()"));
        assertTrue(fallback.methodBody().contains("User inactiveUser = new User(\"Bob\", \"bob@example.com\");"));
        assertTrue(fallback.methodBody().contains("inactiveUser.deactivate();"));
    }

    private Analyze.AnalysisSummary activeUserSummary() {
        return new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                null,
                "",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.service.UserService", "service", true, false),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of("User", List.of(new ConstructorMetadata(
                        "User(String username, String email)",
                        List.of(
                                new ParameterMetadata("username", "String", List.of()),
                                new ParameterMetadata("email", "String", List.of())
                        )))),
                Map.of("User", List.of(
                        "String getUsername()",
                        "boolean isActive()",
                        "void activate()",
                        "void deactivate()",
                        "void incrementAttempts()"
                )),
                Set.of(),
                Set.of());
    }
}
