package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.coverage.CoverageResult;
import com.gigachat.unit.tests.generator.coverage.CoverageSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicCoverageRecipeBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldBuildBooleanBranchSiblingRecipeFromCurrentGeneratedTest() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/NotificationServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                class NotificationServiceTest {
                    @Test
                    void shouldSendWelcomeEmailWithoutError() {
                        boolean priorityAccount = false;
                        assertTrue(true);
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "shouldSendWelcomeEmailWithoutError",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("NotificationService", "sendWelcome", 2, 1, 0, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertEquals(1, recipes.size());
        assertEquals("ADD_BOOLEAN_BRANCH_SIBLING_TEST_SENDWELCOME", recipes.get(0).get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("shouldSendWelcomeEmailWithoutErrorCoverageVariant"));
        assertTrue(methodSource.contains("boolean priorityAccount = true;"));
    }

    @Test
    void shouldBuildNullGuardSiblingRecipeFromCurrentGeneratedTest() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/CoverageGoalWorkflowServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertNotNull;

                class CoverageGoalWorkflowServiceTest {
                    @Test
                    void classifySignal_returnsPriority() {
                        String source = null;
                        assertNotNull(source);
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "classifySignal_returnsPriority",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("CoverageGoalWorkflowService", "classifySignal", 2, 1, 0, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertTrue(recipes.stream().anyMatch(recipe -> "ADD_NULL_GUARD_SIBLING_TEST_CLASSIFYSIGNAL".equals(recipe.get("id"))));
        Map<String, Object> recipe = recipes.stream()
                .filter(candidate -> "ADD_NULL_GUARD_SIBLING_TEST_CLASSIFYSIGNAL".equals(candidate.get("id")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipe.get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("String source = \"coverage\";"));
        assertTrue(methodSource.contains("classifySignal_returnsPriorityCoverageVariant"));
    }

    @Test
    void shouldBuildEmptyInputSiblingRecipeFromCurrentGeneratedTest() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/LegacyWorkflowServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example;

                import org.junit.jupiter.api.Test;
                import java.util.List;

                class LegacyWorkflowServiceTest {
                    @Test
                    void synchronizeLegacyUpgrade_keepsState() {
                        List<String> upgrades = List.of();
                        org.junit.jupiter.api.Assertions.assertTrue(upgrades.isEmpty());
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "synchronizeLegacyUpgrade_keepsState",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("LegacyWorkflowService", "synchronizeLegacyUpgrade", 3, 1, 0, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertTrue(recipes.stream().anyMatch(recipe -> "ADD_EMPTY_INPUT_BRANCH_SIBLING_TEST_SYNCHRONIZELEGACYUPGRADE".equals(recipe.get("id"))));
        Map<String, Object> recipe = recipes.stream()
                .filter(candidate -> "ADD_EMPTY_INPUT_BRANCH_SIBLING_TEST_SYNCHRONIZELEGACYUPGRADE".equals(candidate.get("id")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipe.get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("java.util.List.of(\"coverage\")"));
    }

    @Test
    void shouldBuildTypedNonEmptyAggregationSiblingRecipeForAverageLoginAttemptsFromEmptyBaseline() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import com.example.app.model.User;
                import org.junit.jupiter.api.Test;
                import java.util.Collections;

                class UserServiceTest {
                    @Test
                    void testAverageLoginAttempts_NoUsers_ReturnZero() {
                        when(repository.findAll()).thenReturn(Collections.emptyList());
                        double result = service.averageLoginAttempts();
                        org.junit.jupiter.api.Assertions.assertEquals(0, result);
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "testAverageLoginAttempts_NoUsers_ReturnZero",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("UserService", "averageLoginAttempts", 3, 1, 0, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertEquals(1, recipes.size());
        assertTrue(recipes.stream().anyMatch(recipe -> "ADD_COLLECTION_ELEMENT_TYPE_SAFE_SIBLING_TEST_AVERAGELOGINATTEMPTS".equals(recipe.get("id"))));
        Map<String, Object> recipe = recipes.stream()
                .filter(candidate -> "ADD_COLLECTION_ELEMENT_TYPE_SAFE_SIBLING_TEST_AVERAGELOGINATTEMPTS".equals(candidate.get("id")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipe.get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("com.example.app.model.User coverageUser = new com.example.app.model.User(\"coverage-user\", \"coverage@example.com\");"));
        assertTrue(methodSource.contains("coverageUser.incrementAttempts();"));
        assertTrue(methodSource.contains("when(repository.findAll()).thenReturn(java.util.List.of(coverageUser))"));
        assertTrue(methodSource.contains("assertEquals(2.0, result);"));
        assertFalse(methodSource.contains("assertThrows"));
    }

    @Test
    void shouldBuildTypedNonEmptyAggregationSiblingRecipeForAverageLoginAttemptsFromNewArrayListBaseline() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import org.junit.jupiter.api.Test;
                import java.util.ArrayList;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.mockito.Mockito.when;

                class UserServiceTest {
                    @Test
                    void averageLoginAttempts_NoUsers_ReturnZero() {
                        when(repository.findAll()).thenReturn(new ArrayList<>());
                        double result = service.averageLoginAttempts();
                        assertEquals(0, result);
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "averageLoginAttempts_NoUsers_ReturnZero",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("UserService", "averageLoginAttempts", 3, 1, 0, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertEquals(1, recipes.size());
        assertEquals("ADD_COLLECTION_ELEMENT_TYPE_SAFE_SIBLING_TEST_AVERAGELOGINATTEMPTS", recipes.get(0).get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("when(repository.findAll()).thenReturn(java.util.List.of(coverageUser))"));
        assertTrue(methodSource.contains("assertEquals(2.0, result);"));
    }

    @Test
    void shouldBuildTypedNonEmptyAggregationSiblingRecipeForAverageLoginAttemptsFromBddEmptyBaseline() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import org.junit.jupiter.api.Test;
                import java.util.ArrayList;
                import static org.assertj.core.api.Assertions.assertThat;
                import static org.mockito.BDDMockito.given;

                class UserServiceTest {
                    @Test
                    void averageLoginAttempts_emptyUsers_returnsZero() {
                        given(repository.findAll()).willReturn(new ArrayList<>());
                        double result = service.averageLoginAttempts();
                        assertThat(result).isEqualTo(0);
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "averageLoginAttempts_emptyUsers_returnsZero",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("UserService", "averageLoginAttempts", 3, 1, 0, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertEquals(1, recipes.size());
        assertEquals("ADD_COLLECTION_ELEMENT_TYPE_SAFE_SIBLING_TEST_AVERAGELOGINATTEMPTS", recipes.get(0).get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("given(repository.findAll()).willReturn(java.util.List.of(coverageUser))"));
        assertTrue(methodSource.contains("assertThat(result).isEqualTo(2.0);"));
        assertFalse(methodSource.contains("assertThrows"));
    }

    @Test
    void shouldBuildTypedEmptyInputSiblingRecipeForAverageLoginAttemptsFromNonEmptyUsersBaseline() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import com.example.app.model.User;
                import org.junit.jupiter.api.Test;
                import java.util.ArrayList;
                import java.util.List;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.mockito.Mockito.when;

                class UserServiceTest {
                    @Test
                    void testAverageLoginAttempts_WithUsers() {
                        List<User> users = new ArrayList<>();
                        users.add(new User("Alice", "alice@example.com"));
                        users.add(new User("Bob", "bob@example.com"));
                        when(repository.findAll()).thenReturn(users);
                        double result = service.averageLoginAttempts();
                        assertEquals(0, result);
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "testAverageLoginAttempts_WithUsers",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("UserService", "averageLoginAttempts", 4, 1, 1, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertEquals(1, recipes.size());
        assertEquals("ADD_TYPED_COLLECTION_EARLY_RETURN_SIBLING_TEST_AVERAGELOGINATTEMPTS", recipes.get(0).get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("when(repository.findAll()).thenReturn(java.util.List.of())"));
        assertTrue(methodSource.contains("assertEquals(0.0, result);"));
        assertFalse(methodSource.contains("assertThrows"));
        assertFalse(methodSource.contains("IllegalArgumentException"));
    }

    @Test
    void shouldBuildNumericBoundarySiblingRecipeFromCurrentGeneratedTest() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/LegacyScoreRulesTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                class LegacyScoreRulesTest {
                    @Test
                    void shouldEscalate_whenScoreHigh() {
                        int score = 10;
                        assertTrue(score > 0);
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "shouldEscalate_whenScoreHigh",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("LegacyScoreRules", "shouldEscalate", 3, 1, 0, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertTrue(recipes.stream().anyMatch(recipe -> "ADD_NUMERIC_BOUNDARY_SIBLING_TEST_SHOULDESCALATE".equals(recipe.get("id"))));
        Map<String, Object> recipe = recipes.stream()
                .filter(candidate -> "ADD_NUMERIC_BOUNDARY_SIBLING_TEST_SHOULDESCALATE".equals(candidate.get("id")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipe.get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("int score = 9;"));
        assertFalse(methodSource.contains("int score = 10;"));
    }

    @Test
    void shouldBuildSourceDerivedReturnBranchRecipeForAverageZeroGuard() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/util/MathUtilTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.util;

                import org.junit.jupiter.api.Test;
                import static org.assertj.core.api.Assertions.assertThat;

                class MathUtilTest {
                    @Test
                    void shouldReturnZeroWhenCountIsZero() {
                        int total = 10;
                        int count = 0;
                        double result = MathUtil.average(total, count);
                        assertThat(result).isEqualTo(0.0);
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "shouldReturnZeroWhenCountIsZero",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("MathUtil", "average", 2, 1, 0, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertEquals(1, recipes.size());
        assertEquals("ADD_SOURCE_DERIVED_RETURN_BRANCH_SIBLING_TEST_AVERAGE", recipes.get(0).get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("int count = 2;"));
        assertTrue(methodSource.contains("assertThat(result).isEqualTo(5.0);"));
        assertFalse(methodSource.contains("assertThrows"));
        assertFalse(methodSource.contains("IllegalArgumentException"));
    }

    @Test
    void shouldBuildSourceDerivedReturnBranchRecipeForAverageNonZeroBaseline() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/util/MathUtilTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.util;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                class MathUtilTest {
                    @Test
                    void shouldReturnAverageWhenCountIsNonZero() {
                        double result = MathUtil.average(10, 2);
                        assertEquals(5.0, result);
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "shouldReturnAverageWhenCountIsNonZero",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("MathUtil", "average", 2, 1, 0, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertEquals(1, recipes.size());
        assertEquals("ADD_SOURCE_DERIVED_RETURN_BRANCH_SIBLING_TEST_AVERAGE", recipes.get(0).get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("MathUtil.average(10, 0)"));
        assertTrue(methodSource.contains("assertEquals(0.0, result);"));
        assertFalse(methodSource.contains("assertThrows"));
    }

    @Test
    void shouldBuildOutOfBoundsFindUserBoundaryVariantThatAssertsNull() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import java.util.ArrayList;
                import java.util.List;

                class UserServiceTest {
                    @Test
                    void testFindUserWithValidIndex() {
                        List<User> users = new ArrayList<>();
                        users.add(new User("Alice", "alice@example.com"));
                        users.add(new User("Bob", "bob@example.com"));
                        int index = 1;
                        User result = service.findUser(index);
                        assertEquals("Bob", result.getUsername());
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "testFindUserWithValidIndex",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("UserService", "findUser", 3, 1, 0, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertTrue(recipes.stream().anyMatch(recipe -> "ADD_NUMERIC_BOUNDARY_SIBLING_TEST_FINDUSER".equals(recipe.get("id"))));
        Map<String, Object> recipe = recipes.stream()
                .filter(candidate -> "ADD_NUMERIC_BOUNDARY_SIBLING_TEST_FINDUSER".equals(candidate.get("id")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipe.get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("int index = users.size();"));
        assertTrue(methodSource.contains("assertNull(result);"));
        assertFalse(methodSource.contains("assertEquals(\"Bob\", result.getUsername());"));
    }

    @Test
    void shouldBuildExceptionGuardSiblingRecipeFromCurrentGeneratedTest() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/FeatureValidationServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                class FeatureValidationServiceTest {
                    private final FeatureValidationService service = new FeatureValidationService();

                    @Test
                    void validateFeature_acceptsKnownFeature() {
                        String featureName = "dark-mode";
                        boolean enabled = service.validateFeature(featureName);
                        assertTrue(enabled);
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "validateFeature_acceptsKnownFeature",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("FeatureValidationService", "validateFeature", 2, 1, 0, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertTrue(recipes.stream().anyMatch(recipe -> "ADD_EXCEPTION_GUARD_SIBLING_TEST_VALIDATEFEATURE".equals(recipe.get("id"))));
        Map<String, Object> recipe = recipes.stream()
                .filter(candidate -> "ADD_EXCEPTION_GUARD_SIBLING_TEST_VALIDATEFEATURE".equals(candidate.get("id")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipe.get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("String featureName = null;"));
        assertTrue(methodSource.contains("org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> service.validateFeature(featureName));"));
        assertFalse(methodSource.contains("boolean enabled = service.validateFeature(featureName);"));
        assertFalse(methodSource.contains("assertTrue(enabled);"));
    }

    @Test
    void shouldBuildStateToggleBooleanSiblingForNoArgBooleanMutator() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/model/UserTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.model;

                import org.junit.jupiter.api.Test;
                import static org.assertj.core.api.Assertions.assertThat;

                class UserTest {
                    @Test
                    void shouldReturnFalseAndSetLastLoginWhenInactive() {
                        User user = new User("JohnDoe", "john@example.com");
                        user.deactivate();
                        boolean result = user.markLoggedIn();
                        assertThat(result).isFalse();
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "shouldReturnFalseAndSetLastLoginWhenInactive",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("User", "markLoggedIn", 3, 4, 1, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertTrue(recipes.stream().anyMatch(recipe -> "ADD_BOOLEAN_BRANCH_SIBLING_TEST_MARKLOGGEDIN".equals(recipe.get("id"))));
        assertFalse(recipes.stream().anyMatch(recipe -> "ADD_EXCEPTION_GUARD_SIBLING_TEST_MARKLOGGEDIN".equals(recipe.get("id"))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("user.activate();"));
        assertTrue(methodSource.contains("boolean result = user.markLoggedIn();"));
        assertTrue(methodSource.contains("assertThat(result).isTrue();"));
        assertFalse(methodSource.contains("assertThat(result).isFalse();"));
    }

    @Test
    void shouldProduceBooleanAndNumericRecipesForCoverageGoalWorkflowRegressionBaseline() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/CoverageGoalWorkflowServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.mockito.Mockito.*;
                import org.junit.jupiter.api.BeforeEach;
                import org.mockito.Mock;
                import org.mockito.MockitoAnnotations;

                public class CoverageGoalWorkflowServiceTest {

                    @Mock
                    AuditTrailService auditTrailService;

                    private CoverageGoalWorkflowService service;

                    @BeforeEach
                    void setUp() {
                        MockitoAnnotations.openMocks(this);
                        service = new CoverageGoalWorkflowService(auditTrailService);
                    }

                    @Test
                    void testPriorityClassificationWithHighScoreAndPriorityAccount() {
                        int highScore = 10;
                        boolean priorityAccount = true;
                        String result = service.classifySignal(highScore, priorityAccount);
                        verify(auditTrailService).recordEvent("priority-signal");
                        assertEquals("priority", result);
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "testPriorityClassificationWithHighScoreAndPriorityAccount",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("CoverageGoalWorkflowService", "classifySignal", 3, 5, 2, 4),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertTrue(recipes.stream().anyMatch(recipe -> "ADD_BOOLEAN_BRANCH_SIBLING_TEST_CLASSIFYSIGNAL".equals(recipe.get("id"))));
        assertTrue(recipes.stream().anyMatch(recipe -> "ADD_NUMERIC_BOUNDARY_SIBLING_TEST_CLASSIFYSIGNAL".equals(recipe.get("id"))));
    }

    @Test
    void shouldProduceBooleanAndNumericRecipesForLegacyScoreRulesRegressionBaseline() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/legacy/LegacyScoreRulesTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.legacy;

                import org.junit.jupiter.api.Test;
                import static org.assertj.core.api.Assertions.assertThat;

                public class LegacyScoreRulesTest {

                    @Test
                    void shouldEscalate_ActiveUserWithHighNormalizedSignal_ReturnTrue() {
                        int normalizedSignal = 15;
                        boolean activeUser = true;
                        boolean result = LegacyScoreRules.shouldEscalate(normalizedSignal, activeUser);
                        assertThat(result).isTrue();
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "shouldEscalate_ActiveUserWithHighNormalizedSignal_ReturnTrue",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("LegacyScoreRules", "shouldEscalate", 1, 0, 2, 4),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertTrue(recipes.stream().anyMatch(recipe -> "ADD_BOOLEAN_BRANCH_SIBLING_TEST_SHOULDESCALATE".equals(recipe.get("id"))));
        assertTrue(recipes.stream().anyMatch(recipe -> "ADD_NUMERIC_BOUNDARY_SIBLING_TEST_SHOULDESCALATE".equals(recipe.get("id"))));
    }

    @Test
    void shouldBuildReboundFactorRecipeThatRewritesExpectedAssertion() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/legacy/LegacyScoreRulesTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.legacy;

                import org.junit.jupiter.api.Test;
                import static org.assertj.core.api.Assertions.assertThat;

                public class LegacyScoreRulesTest {

                    @Test
                    void shouldCalculateReboundFactorForInactiveUserWithZeroAttempts() {
                        final int attempts = 0;
                        final boolean activeUser = false;
                        int result = LegacyScoreRules.reboundFactor(attempts, activeUser);
                        assertThat(result).isEqualTo(3);
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "shouldCalculateReboundFactorForInactiveUserWithZeroAttempts",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("LegacyScoreRules", "reboundFactor", 2, 1, 0, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertEquals(1, recipes.size());
        assertEquals("ADD_REBOUND_FACTOR_BRANCH_SIBLING_TEST_REBOUNDFACTOR", recipes.get(0).get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("final boolean activeUser = true;"));
        assertTrue(methodSource.contains("assertThat(result).isEqualTo(4);"));
        assertFalse(methodSource.contains("assertThat(result).isEqualTo(3);"));
    }

    @Test
    void shouldBuildConstructorLocalPromotionRecipeForLegacyUpgradeRegressionBaseline() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/legacy/LegacyUpgradeSessionTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.legacy;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.mockito.Mockito.*;
                import com.example.app.model.User;

                class LegacyUpgradeSessionTest {
                    private FeatureToggleService featureToggleService;
                    private AuditTrailService auditTrailService;
                    private NotificationService notificationService;
                    private LegacyUpgradeSession session;

                    @Test
                    void testProcessWithFeatureDisabled() {
                        User user = new User("JohnDoe", "john@example.com");
                        when(featureToggleService.isEnabled("legacy-upgrade")).thenReturn(false);
                        boolean result = session.process(user, 42);
                        assertFalse(result);
                        verify(auditTrailService).recordEvent("Legacy upgrade skipped for JohnDoe");
                        verifyNoInteractions(notificationService);
                        verify(notificationService, never()).sendWelcome(any());
                        verify(notificationService, never()).sendDeactivationNotice(any());
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "testProcessWithFeatureDisabled",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("LegacyUpgradeSession", "process", 6, 9, 1, 3),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertEquals(1, recipes.size());
        assertEquals("ADD_CONSTRUCTOR_LOCAL_PROMOTION_SIBLING_TEST_PROCESS", recipes.get(0).get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("thenReturn(true)"));
        assertTrue(methodSource.contains("assertTrue(result);"));
        assertTrue(methodSource.contains("verify(auditTrailService).recordEvent(startsWith(\"Legacy upgrade promoted \"))"));
        assertTrue(methodSource.contains("verify(notificationService).sendWelcome(user);"));
        assertFalse(methodSource.contains("verifyNoInteractions(notificationService)"));
        assertFalse(methodSource.contains("never()).sendWelcome"));
        assertFalse(methodSource.contains("never()).sendDeactivationNotice"));
        assertFalse(methodSource.contains("Legacy upgrade skipped"));
        @SuppressWarnings("unchecked")
        List<String> requiredImports = (List<String>) recipes.get(0).get("requiredImports");
        assertTrue(requiredImports.contains("static org.junit.jupiter.api.Assertions.assertTrue"));
    }

    @Test
    void shouldBuildConstructorLocalPromotionRecipeForWorkflowMethodUsingConstructorLocalSession() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/LegacyWorkflowServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.mockito.Mockito.*;
                import com.example.app.model.User;

                class LegacyWorkflowServiceTest {
                    private FeatureToggleService featureToggleService;
                    private AuditTrailService auditTrailService;
                    private NotificationService notificationService;
                    private LibraryComponent libraryComponent;
                    private LegacyWorkflowService service;

                    @Test
                    void shouldSynchronizeLegacyUpgradeWhenLegacyUpgradeDisabled() {
                        User user = new User("John Doe", "john.doe@example.com");
                        int rawSignal = 10;
                        when(featureToggleService.isEnabled("legacy-upgrade")).thenReturn(false);
                        boolean result = service.synchronizeLegacyUpgrade(user, rawSignal);
                        assertFalse(result);
                        verify(auditTrailService).recordEvent("Legacy upgrade skipped for " + user.getUsername());
                        verify(libraryComponent).connect();
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "shouldSynchronizeLegacyUpgradeWhenLegacyUpgradeDisabled",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("LegacyWorkflowService", "synchronizeLegacyUpgrade", 3, 1, 1, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertEquals(1, recipes.size());
        assertEquals("ADD_CONSTRUCTOR_LOCAL_PROMOTION_SIBLING_TEST_SYNCHRONIZELEGACYUPGRADE", recipes.get(0).get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("shouldSynchronizeLegacyUpgradeWhenLegacyUpgradeDisabledCoverageVariant"));
        assertTrue(methodSource.contains("thenReturn(true)"));
        assertTrue(methodSource.contains("boolean result = service.synchronizeLegacyUpgrade(user, rawSignal);"));
        assertTrue(methodSource.contains("assertTrue(result);"));
        assertTrue(methodSource.contains("verify(auditTrailService).recordEvent(startsWith(\"Legacy upgrade promoted \"))"));
        assertTrue(methodSource.contains("verify(notificationService).sendWelcome(user);"));
        assertTrue(methodSource.contains("verify(libraryComponent).reload();"));
        assertFalse(methodSource.contains("assertFalse(result);"));
        assertFalse(methodSource.contains("Legacy upgrade skipped"));
        assertFalse(methodSource.contains("verify(libraryComponent).connect();"));
        @SuppressWarnings("unchecked")
        List<String> requiredImports = (List<String>) recipes.get(0).get("requiredImports");
        assertTrue(requiredImports.contains("static org.junit.jupiter.api.Assertions.assertTrue"));
        assertTrue(requiredImports.contains("static org.mockito.ArgumentMatchers.startsWith"));
    }

    @Test
    void shouldBuildInheritedShadowRejectedBranchRecipeFromPromotionBaseline() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/legacy/InheritedShadowUpgradeSessionTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.legacy;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                import static org.mockito.Mockito.*;
                import static org.mockito.ArgumentMatchers.startsWith;
                import com.example.app.model.User;

                class InheritedShadowUpgradeSessionTest {
                    private FeatureToggleService featureToggleService;
                    private AuditTrailService auditTrailService;
                    private NotificationService notificationService;
                    private InheritedShadowUpgradeSession session;

                    @Test
                    void testUpgradePromotesWhenFeatureIsEnabledAndNormalizedScoreHighEnough() {
                        User user = new User("Alice", "alice@example.com");
                        when(featureToggleService.isEnabled("inherited-shadow")).thenReturn(true);
                        boolean result = session.upgrade(user, 42);
                        assertTrue(result);
                        verify(notificationService).sendWelcome(user);
                        verify(auditTrailService).recordEvent(startsWith("Inherited shadow promoted "));
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "testUpgradePromotesWhenFeatureIsEnabledAndNormalizedScoreHighEnough",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("InheritedShadowUpgradeSession", "upgrade", 6, 8, 2, 2),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertEquals(1, recipes.size());
        assertEquals("ADD_LEGACY_BOOLEAN_BRANCH_SIBLING_TEST_UPGRADE", recipes.get(0).get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("thenReturn(true)"));
        assertTrue(methodSource.contains("session.upgrade(user, 1)"));
        assertTrue(methodSource.contains("assertFalse(result);"));
        assertTrue(methodSource.contains("verify(notificationService).sendDeactivationNotice(user);"));
        assertTrue(methodSource.contains("verify(auditTrailService).recordEvent(startsWith(\"Inherited shadow rejected \"))"));
        assertFalse(methodSource.contains("sendWelcome(user)"));
        @SuppressWarnings("unchecked")
        List<String> requiredImports = (List<String>) recipes.get(0).get("requiredImports");
        assertTrue(requiredImports.contains("static org.junit.jupiter.api.Assertions.assertFalse"));
        assertTrue(requiredImports.contains("static org.mockito.ArgumentMatchers.startsWith"));
    }

    @Test
    void shouldBuildInheritedShadowPromotionRecipeFromDisabledAssertJBaseline() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/legacy/InheritedShadowUpgradeSessionTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.legacy;

                import org.junit.jupiter.api.Test;
                import static org.assertj.core.api.Assertions.assertThat;
                import static org.mockito.Mockito.*;
                import static org.mockito.ArgumentMatchers.eq;
                import com.example.app.model.User;

                class InheritedShadowUpgradeSessionTest {
                    private FeatureToggleService featureToggleService;
                    private AuditTrailService auditTrailService;
                    private NotificationService notificationService;
                    private InheritedShadowUpgradeSession session;

                    @Test
                    void testUpgradeWithDisabledFeature() {
                        User user = new User("Alice", "alice@example.com");
                        when(featureToggleService.isEnabled(eq("inherited-shadow"))).thenReturn(false);
                        boolean result = session.upgrade(user, 8);
                        assertThat(result).isFalse();
                        verify(auditTrailService, times(1)).recordEvent("Inherited shadow skipped for Alice");
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "testUpgradeWithDisabledFeature",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("InheritedShadowUpgradeSession", "upgrade", 5, 9, 1, 3),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertEquals(1, recipes.size());
        assertEquals("ADD_CONSTRUCTOR_LOCAL_PROMOTION_SIBLING_TEST_UPGRADE", recipes.get(0).get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("thenReturn(true)"));
        assertTrue(methodSource.contains("session.upgrade(user, 10)"));
        assertTrue(methodSource.contains("assertThat(result).isTrue();"));
        assertTrue(methodSource.contains("verify(auditTrailService, times(1)).recordEvent(startsWith(\"Inherited shadow promoted \"))"));
        assertTrue(methodSource.contains("verify(notificationService).sendWelcome(user);"));
        assertFalse(methodSource.contains("Inherited shadow skipped"));
    }

    @Test
    void shouldBuildInheritedShadowWorkflowPromotionRecipeFromDisabledBaseline() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/InheritedStaticVoidWorkflowServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import org.junit.jupiter.api.Test;
                import com.example.app.legacy.LegacyConnectionGateway;
                import com.example.app.model.User;
                import com.example.lib.LibraryComponent;
                import org.mockito.MockedStatic;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.mockito.Mockito.mock;
                import static org.mockito.Mockito.mockStatic;
                import static org.mockito.Mockito.verify;
                import static org.mockito.Mockito.when;

                class InheritedStaticVoidWorkflowServiceTest {
                    @Test
                    void shouldExecuteInheritedShadowUpgradeWhenInheritedShadowDisabled() {
                        FeatureToggleService featureToggleService = mock(FeatureToggleService.class);
                        AuditTrailService auditTrailService = mock(AuditTrailService.class);
                        NotificationService notificationService = mock(NotificationService.class);
                        LibraryComponent libraryComponent = mock(LibraryComponent.class);
                        InheritedStaticVoidWorkflowService service = new InheritedStaticVoidWorkflowService(
                                featureToggleService,
                                auditTrailService,
                                notificationService,
                                libraryComponent);
                        User user = new User("John Doe", "john.doe@example.com");
                        int rawSignal = 3;
                        when(featureToggleService.isEnabled("inherited-shadow")).thenReturn(false);
                        boolean result;
                        try (MockedStatic<LegacyConnectionGateway> legacyConnectionGatewayMock = mockStatic(LegacyConnectionGateway.class)) {
                            legacyConnectionGatewayMock.when(() -> LegacyConnectionGateway.openRequiredChannel("legacy-shadow-db"))
                                    .thenAnswer(invocation -> null);
                            result = service.executeInheritedShadowUpgrade(user, rawSignal);
                        }
                        assertFalse(result);
                        verify(auditTrailService).recordEvent("Inherited shadow skipped for " + user.getUsername());
                        verifyNoInteractions(notificationService);
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "shouldExecuteInheritedShadowUpgradeWhenInheritedShadowDisabled",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("InheritedStaticVoidWorkflowService", "executeInheritedShadowUpgrade", 3, 1, 1, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertEquals(1, recipes.size());
        assertEquals("ADD_CONSTRUCTOR_LOCAL_PROMOTION_SIBLING_TEST_EXECUTEINHERITEDSHADOWUPGRADE", recipes.get(0).get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("shouldExecuteInheritedShadowUpgradeWhenInheritedShadowDisabledCoverageVariant"));
        assertTrue(methodSource.contains("thenReturn(true)"));
        assertTrue(methodSource.contains("int rawSignal = 10;"));
        assertTrue(methodSource.contains("LegacyConnectionGateway.openRequiredChannel(\"legacy-shadow-db\")"));
        assertTrue(methodSource.contains("result = service.executeInheritedShadowUpgrade(user, rawSignal);"));
        assertTrue(methodSource.contains("assertTrue(result);"));
        assertTrue(methodSource.contains("verify(auditTrailService).recordEvent(startsWith(\"Inherited shadow upgrade completed for \"))"));
        assertTrue(methodSource.contains("verify(notificationService).sendWelcome(user);"));
        assertTrue(methodSource.contains("verify(libraryComponent).reload();"));
        assertFalse(methodSource.contains("assertFalse(result);"));
        assertFalse(methodSource.contains("Inherited shadow skipped"));
        assertFalse(methodSource.contains("verifyNoInteractions(notificationService)"));
        @SuppressWarnings("unchecked")
        List<String> requiredImports = (List<String>) recipes.get(0).get("requiredImports");
        assertTrue(requiredImports.contains("static org.junit.jupiter.api.Assertions.assertTrue"));
        assertTrue(requiredImports.contains("static org.mockito.ArgumentMatchers.startsWith"));
    }

    @Test
    void shouldBuildConstructorLocalReboundRecipeForCoordinateShadowRollbackRegressionBaseline() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/LegacyWorkflowServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.mockito.Mockito.*;
                import com.example.app.model.User;

                class LegacyWorkflowServiceTest {
                    private FeatureToggleService featureToggleService;
                    private AuditTrailService auditTrailService;
                    private NotificationService notificationService;
                    private LibraryComponent libraryComponent;
                    private LegacyWorkflowService service;

                    @Test
                    void coordinateShadowRollback_whenShadowRollbackIsDisabled_thenReturnsFalseAndLoadsLibrary() {
                        User user = new User("John Doe", "john.doe@example.com");
                        when(featureToggleService.isEnabled("shadow-rollback")).thenReturn(false);
                        boolean result = service.coordinateShadowRollback(user);
                        assertFalse(result);
                        verify(auditTrailService).recordEvent("Shadow rollback skipped for John Doe");
                        verify(libraryComponent).load();
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "coordinateShadowRollback_whenShadowRollbackIsDisabled_thenReturnsFalseAndLoadsLibrary",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("LegacyWorkflowService", "coordinateShadowRollback", 2, 1, 1, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertEquals(1, recipes.size());
        assertEquals("ADD_CONSTRUCTOR_LOCAL_REBOUND_SIBLING_TEST_COORDINATESHADOWROLLBACK", recipes.get(0).get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("coordinateShadowRollback_whenShadowRollbackIsDisabled_thenReturnsFalseAndLoadsLibraryCoverageVariant"));
        assertTrue(methodSource.contains("thenReturn(true)"));
        assertTrue(methodSource.contains("user.incrementAttempts();"));
        assertTrue(methodSource.contains("assertTrue(result);"));
        assertTrue(methodSource.contains("verify(auditTrailService).recordEvent(startsWith(\"Shadow rollback rebound \"))"));
        assertTrue(methodSource.contains("verify(libraryComponent).close();"));
        assertTrue(methodSource.contains("verify(notificationService).sendDeactivationNotice(user);"));
        assertFalse(methodSource.contains("never()).sendDeactivationNotice"));
        assertFalse(methodSource.contains("never()).sendWelcome"));
        @SuppressWarnings("unchecked")
        List<String> requiredImports = (List<String>) recipes.get(0).get("requiredImports");
        assertTrue(requiredImports.contains("static org.junit.jupiter.api.Assertions.assertTrue"));
    }

    @Test
    void shouldBuildDirectShadowRollbackReboundRecipeForDisabledBaseline() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/legacy/ShadowRollbackSessionTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.legacy;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.mockito.Mockito.*;
                import com.example.app.model.User;

                class ShadowRollbackSessionTest {
                    private FeatureToggleService featureToggleService;
                    private AuditTrailService auditTrailService;
                    private NotificationService notificationService;
                    private ShadowRollbackSession session;

                    @Test
                    void testRollbackDisabledFeature() {
                        User user = new User("john.doe", "john@example.com");
                        when(featureToggleService.isEnabled("shadow-rollback")).thenReturn(false);
                        boolean result = session.rollback(user);
                        assertFalse(result);
                        verify(auditTrailService).recordEvent("Shadow rollback skipped for john.doe");
                    }
                }
                """);

        DeterministicCoverageRecipeBuilder builder = new DeterministicCoverageRecipeBuilder();
        List<Map<String, Object>> recipes = builder.build(
                testFile,
                "testRollbackDisabledFeature",
                new CoverageResult(true,
                        true,
                        new CoverageSummary("ShadowRollbackSession", "rollback", 4, 2, 1, 1),
                        tempDir.resolve("jacoco.xml"),
                        "",
                        ""),
                80);

        assertEquals(1, recipes.size());
        assertEquals("ADD_REBOUND_THRESHOLD_SIBLING_TEST_ROLLBACK", recipes.get(0).get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) recipes.get(0).get("operations");
        String methodSource = operations.get(0).get("methodSource").toString();
        assertTrue(methodSource.contains("testRollbackDisabledFeatureCoverageVariant"));
        assertTrue(methodSource.contains("thenReturn(true)"));
        assertEquals(3, countOccurrences(methodSource, "user.incrementAttempts();"));
        assertTrue(methodSource.contains("assertTrue(result);"));
        assertTrue(methodSource.contains("verify(auditTrailService).recordEvent(startsWith(\"Shadow rollback rebound \"))"));
        assertTrue(methodSource.contains("verify(notificationService).sendDeactivationNotice(user);"));
        assertFalse(methodSource.contains("Shadow rollback skipped"));
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
