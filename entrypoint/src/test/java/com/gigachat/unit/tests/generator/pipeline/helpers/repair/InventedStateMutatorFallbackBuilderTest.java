package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

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

class InventedStateMutatorFallbackBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRewriteInventedCounterMutatorIntoRepeatedIncrementAttemptsCalls() {
        InventedStateMutatorFallbackBuilder builder =
                new InventedStateMutatorFallbackBuilder(new PipelineLogger(tempDir));
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                null,
                "",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.service.UserService", "service", true, false),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of("User", List.of(
                        "void incrementAttempts()",
                        "void activate()",
                        "void deactivate()"
                )),
                Set.of(),
                Set.of());
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserServiceTest",
                "shouldAverageLoginAttempts",
                """
                        @Test
                        void shouldAverageLoginAttempts() {
                            User firstUser = new User("alice", "alice@example.com");
                            firstUser.incrementLoginAttempts(2);
                        }
                        """,
                List.of(),
                List.of(),
                List.of(),
                List.of("""
                        void prepareUser(User user) {
                            user.incrementLoginAttempts(1);
                        }
                        """),
                """
                        class UserServiceTest {
                            @Test
                            void shouldAverageLoginAttempts() {
                                User firstUser = new User("alice", "alice@example.com");
                                firstUser.incrementLoginAttempts(2);
                            }
                        }
                        """);

        GeneratedTestSnippet fallback = builder.build(
                snippet,
                summary,
                "E102: Invented method User.incrementLoginAttempts(2)");

        assertNotNull(fallback);
        assertTrue(fallback.methodBody().contains("firstUser.incrementAttempts();"));
        assertFalse(fallback.methodBody().contains("incrementLoginAttempts("));
        assertTrue(fallback.helperMethods().get(0).contains("user.incrementAttempts();"));
    }

    @Test
    void shouldRewriteInventedSetLoginAttemptsIntoRepeatedIncrementAttemptsCalls() {
        InventedStateMutatorFallbackBuilder builder =
                new InventedStateMutatorFallbackBuilder(new PipelineLogger(tempDir));
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.NONE, List.of(), List.of()),
                null,
                "",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.service.UserService", "service", true, false),
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of("User", List.of("void incrementAttempts()")),
                Set.of(),
                Set.of());
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserServiceTest",
                "shouldAverageLoginAttempts",
                """
                        @Test
                        void shouldAverageLoginAttempts() {
                            User firstUser = new User("alice", "alice@example.com");
                            firstUser.setLoginAttempts(3);
                        }
                        """,
                List.of());

        GeneratedTestSnippet fallback = builder.build(
                snippet,
                summary,
                "E102: Invented method User.setLoginAttempts(3)");

        assertNotNull(fallback);
        assertFalse(fallback.methodBody().contains("setLoginAttempts("));
        assertTrue(fallback.methodBody().contains("firstUser.incrementAttempts();"));
        assertTrue(fallback.methodBody().indexOf("firstUser.incrementAttempts();")
                != fallback.methodBody().lastIndexOf("firstUser.incrementAttempts();"));
    }
}
