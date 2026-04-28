package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.compile.CompileResult;
import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.execute.ExecuteResult;
import com.gigachat.unit.tests.generator.resources.SiblingIsolationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreMergeScratchValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldValidateSnippetInScratchClassAndCleanArtifacts() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/SampleServiceTest.java");
        TestClassInfo classInfo = new TestClassInfo(
                "SampleService",
                "SampleServiceTest",
                testFile,
                List.of(),
                List.of()
        );
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "SampleServiceTest",
                "shouldExecutePerform",
                """
                        @Test
                        void shouldExecutePerform() {
                            org.junit.jupiter.api.Assertions.assertTrue(true);
                        }
                        """,
                List.of("import org.junit.jupiter.api.Test;", "import org.junit.jupiter.api.Assertions;")
        );

        AtomicReference<Path> compilePath = new AtomicReference<>();
        AtomicReference<String> compileSource = new AtomicReference<>("");
        AtomicReference<Path> executionPath = new AtomicReference<>();

        PreMergeScratchValidator validator = new PreMergeScratchValidator(
                new PipelineLogger(tempDir),
                (projectRoot, scratchPath, methodName) -> {
                    try {
                        compilePath.set(scratchPath);
                        compileSource.set(Files.readString(scratchPath));
                        Path compiledDir = tempDir.resolve("build/classes/java/test/com/example");
                        Files.createDirectories(compiledDir);
                        Files.writeString(compiledDir.resolve("SampleServiceTestPreMergeScratch.class"), "compiled");
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                    return new CompileResult(true, List.of(), "", "");
                },
                (projectRoot, scratchPath, methodName) -> {
                    executionPath.set(scratchPath);
                    return new ExecuteResult(true, List.of(), "", "");
                },
                new SiblingIsolationPolicy(true, true, true, false, true, true, true, true, "PreMergeScratch")
        );

        PreMergeScratchValidator.ValidationResult result = validator.validate(
                tempDir,
                classInfo,
                snippet,
                true,
                true);

        assertTrue(result.success());
        assertTrue(compilePath.get().getFileName().toString().contains("PreMergeScratch"));
        assertTrue(executionPath.get().equals(compilePath.get()));
        assertTrue(compileSource.get().contains("public class SampleServiceTestPreMergeScratch"));
        assertFalse(Files.exists(compilePath.get()), "scratch source should be cleaned after validation");
        assertFalse(Files.exists(tempDir.resolve("build/classes/java/test/com/example/SampleServiceTestPreMergeScratch.class")),
                "compiled scratch artifact should be cleaned after validation");
    }

    @Test
    void shouldIgnoreMalformedModelImportsBeforeScratchParsing() {
        Path testFile = tempDir.resolve("src/test/java/com/example/SampleServiceTest.java");
        TestClassInfo classInfo = new TestClassInfo(
                "SampleService",
                "SampleServiceTest",
                testFile,
                List.of(),
                List.of()
        );
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "SampleServiceTest",
                "shouldIgnoreBadImport",
                """
                        @Test
                        void shouldIgnoreBadImport() {
                            org.junit.jupiter.api.Assertions.assertTrue(true);
                        }
                        """,
                List.of(
                        "import //Corrected import",
                        "java.util.List // corrected import",
                        "import static org.junit.jupiter.api.Assertions.*;"
                ),
                List.of(),
                List.of(),
                List.of(),
                """
                        package com.example;

                        import //Corrected import
                        import java.util.Map // corrected import

                        public class SampleServiceTest {

                            @Test
                            void shouldIgnoreBadImport() {
                                org.junit.jupiter.api.Assertions.assertTrue(true);
                            }
                        }
                        """
        );

        AtomicReference<String> compileSource = new AtomicReference<>("");
        PreMergeScratchValidator validator = new PreMergeScratchValidator(
                new PipelineLogger(tempDir),
                (projectRoot, scratchPath, methodName) -> {
                    try {
                        compileSource.set(Files.readString(scratchPath));
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                    return new CompileResult(true, List.of(), "", "");
                },
                (projectRoot, scratchPath, methodName) -> new ExecuteResult(true, List.of(), "", ""),
                new SiblingIsolationPolicy(true, true, false, false, true, true, true, true, "PreMergeScratch")
        );

        PreMergeScratchValidator.ValidationResult result = validator.validate(
                tempDir,
                classInfo,
                snippet,
                true,
                false);

        assertTrue(result.success());
        assertTrue(compileSource.get().contains("import java.util.Map;"));
        assertTrue(compileSource.get().contains("import java.util.List;"));
        assertFalse(compileSource.get().contains("Corrected import"));
        assertFalse(compileSource.get().contains("import //"));
    }

    @Test
    void shouldFailScratchExecutionWhenTargetClassAlreadyHasSiblings() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/SampleServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example;

                import org.junit.jupiter.api.Test;

                public class SampleServiceTest {

                    @Test
                    void existingSiblingShouldStay() {
                        org.junit.jupiter.api.Assertions.assertTrue(true);
                    }
                }
                """);

        TestClassInfo classInfo = new TestClassInfo(
                "SampleService",
                "SampleServiceTest",
                testFile,
                List.of(),
                List.of()
        );
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "SampleServiceTest",
                "shouldExecutePerform",
                """
                        @Test
                        void shouldExecutePerform() {
                            org.junit.jupiter.api.Assertions.assertTrue(true);
                        }
                        """,
                List.of("import org.junit.jupiter.api.Test;", "import org.junit.jupiter.api.Assertions;")
        );

        AtomicBoolean executionCalled = new AtomicBoolean(false);
        PreMergeScratchValidator validator = new PreMergeScratchValidator(
                new PipelineLogger(tempDir),
                (projectRoot, scratchPath, methodName) -> new CompileResult(true, List.of(), "", ""),
                (projectRoot, scratchPath, methodName) -> {
                    executionCalled.set(true);
                    return new ExecuteResult(false,
                            List.of("com.example.SampleServiceTestPreMergeScratch." + methodName),
                            "",
                            "synthetic scratch execution failure");
                },
                new SiblingIsolationPolicy(true, true, true, true, true, true, true, true, "PreMergeScratch")
        );

        PreMergeScratchValidator.ValidationResult result = validator.validate(
                tempDir,
                classInfo,
                snippet,
                true,
                true);

        assertFalse(result.success());
        assertEquals("SCRATCH_EXECUTION_FAILED", result.failureReason());
        assertTrue(executionCalled.get(), "Execution gate should run once the target class already has siblings");
    }

    @Test
    void shouldAutoAddResolvableProjectImportsBeforeScratchCompilation() throws Exception {
        Path projectType = tempDir.resolve("src/main/java/com/example/app/util/MathUtil.java");
        Files.createDirectories(projectType.getParent());
        Files.writeString(projectType, """
                package com.example.app.util;

                public final class MathUtil {
                    private MathUtil() {
                    }

                    public static int sum(int left, int right) {
                        return left + right;
                    }
                }
                """);

        Path testFile = tempDir.resolve("src/test/java/com/example/app/legacy/LegacyScoreRulesTest.java");
        TestClassInfo classInfo = new TestClassInfo(
                "LegacyScoreRules",
                "LegacyScoreRulesTest",
                testFile,
                List.of(),
                List.of()
        );
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "LegacyScoreRulesTest",
                "shouldKeepHelperMethodsCompilable",
                """
                        @Test
                        void shouldKeepHelperMethodsCompilable() {
                            org.junit.jupiter.api.Assertions.assertEquals(3, MathUtil.sum(1, 2));
                        }
                        """,
                List.of("import org.junit.jupiter.api.Test;", "import static org.junit.jupiter.api.Assertions.assertEquals;"),
                List.of(),
                List.of(),
                List.of("""
                        @Test
                        void shouldKeepSiblingVariantCompilable() {
                            org.junit.jupiter.api.Assertions.assertEquals(5, MathUtil.sum(2, 3));
                        }
                        """),
                """
                        package com.example.app.legacy;

                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertEquals;

                        public class LegacyScoreRulesTest {

                            @Test
                            void shouldKeepHelperMethodsCompilable() {
                                org.junit.jupiter.api.Assertions.assertEquals(3, MathUtil.sum(1, 2));
                            }

                            @Test
                            void shouldKeepSiblingVariantCompilable() {
                                org.junit.jupiter.api.Assertions.assertEquals(5, MathUtil.sum(2, 3));
                            }
                        }
                        """
        );

        AtomicReference<String> compileSource = new AtomicReference<>("");
        PreMergeScratchValidator validator = new PreMergeScratchValidator(
                new PipelineLogger(tempDir),
                (projectRoot, scratchPath, methodName) -> {
                    try {
                        compileSource.set(Files.readString(scratchPath));
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                    return new CompileResult(true, List.of(), "", "");
                },
                (projectRoot, scratchPath, methodName) -> new ExecuteResult(true, List.of(), "", ""),
                new SiblingIsolationPolicy(true, true, true, false, true, true, true, true, "PreMergeScratch")
        );

        PreMergeScratchValidator.ValidationResult result = validator.validate(
                tempDir,
                classInfo,
                snippet,
                true,
                false);

        assertTrue(result.success());
        assertTrue(compileSource.get().contains("import com.example.app.util.MathUtil;"));
    }

    @Test
    void shouldExecuteWholeScratchSuiteWhenSnippetContainsSiblingTests() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/SampleServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example;

                import org.junit.jupiter.api.Test;

                public class SampleServiceTest {

                    @Test
                    void existingSiblingShouldStay() {
                        org.junit.jupiter.api.Assertions.assertTrue(true);
                    }
                }
                """);

        TestClassInfo classInfo = new TestClassInfo(
                "SampleService",
                "SampleServiceTest",
                testFile,
                List.of(),
                List.of()
        );
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "SampleServiceTest",
                "shouldExecutePrimarySnippet",
                """
                        @Test
                        void shouldExecutePrimarySnippet() {
                            org.junit.jupiter.api.Assertions.assertTrue(true);
                        }
                        """,
                List.of("import org.junit.jupiter.api.Test;", "import org.junit.jupiter.api.Assertions;"),
                List.of(),
                List.of(),
                List.of("""
                        @Test
                        void shouldExecuteSiblingSnippet() {
                            org.junit.jupiter.api.Assertions.assertTrue(true);
                        }
                        """),
                """
                        package com.example;

                        import org.junit.jupiter.api.Test;

                        public class SampleServiceTest {

                            @Test
                            void shouldExecutePrimarySnippet() {
                                org.junit.jupiter.api.Assertions.assertTrue(true);
                            }

                            @Test
                            void shouldExecuteSiblingSnippet() {
                                org.junit.jupiter.api.Assertions.assertTrue(true);
                            }
                        }
                        """
        );

        AtomicReference<String> executedMethodName = new AtomicReference<>("UNSET");
        PreMergeScratchValidator validator = new PreMergeScratchValidator(
                new PipelineLogger(tempDir),
                (projectRoot, scratchPath, methodName) -> new CompileResult(true, List.of(), "", ""),
                (projectRoot, scratchPath, methodName) -> {
                    executedMethodName.set(methodName);
                    return new ExecuteResult(true, List.of(), "", "");
                },
                new SiblingIsolationPolicy(true, true, true, true, true, true, true, true, "PreMergeScratch")
        );

        PreMergeScratchValidator.ValidationResult result = validator.validate(
                tempDir,
                classInfo,
                snippet,
                true,
                true);

        assertTrue(result.success());
        assertEquals(null, executedMethodName.get());
    }

    @Test
    void shouldValidateMergedScratchClassWhenTargetClassAlreadyHasSiblingTests() throws Exception {
        Path testFile = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example.app.service;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.mockito.ArgumentMatchers.any;
                import static org.mockito.Mockito.eq;
                import static org.mockito.Mockito.verify;
                import static org.mockito.Mockito.when;
                import com.example.app.model.User;
                import com.example.app.repository.UserRepository;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;

                @ExtendWith(MockitoExtension.class)
                public class UserServiceTest {

                    @Mock
                    private UserRepository repository;

                    @Mock
                    private AuditTrailService auditTrailService;

                    @Mock
                    private NotificationService notificationService;

                    @InjectMocks
                    private UserService service;

                    @Test
                    void createUserShouldSaveUserAndSendNotifications() {
                        User expectedUser = new User("john_doe", "johndoe@example.com");
                        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
                        User createdUser = service.createUser("john_doe", "johndoe@example.com");
                        assertEquals(expectedUser.getUsername(), createdUser.getUsername());
                        verify(auditTrailService).recordEvent("Created user john_doe");
                        verify(notificationService).sendWelcome(eq(expectedUser));
                    }
                }
                """);

        TestClassInfo classInfo = new TestClassInfo(
                "UserService",
                "UserServiceTest",
                testFile,
                List.of(),
                List.of()
        );
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserServiceTest",
                "testAverageLoginAttempts_NoUsers_ReturnZero",
                """
                        @Test
                        public void testAverageLoginAttempts_NoUsers_ReturnZero() {
                            when(repository.findAll()).thenReturn(new java.util.ArrayList<>());
                            double result = service.averageLoginAttempts();
                            assertEquals(0, result);
                        }
                        """,
                List.of(
                        "import org.junit.jupiter.api.Test;",
                        "import static org.junit.jupiter.api.Assertions.assertEquals;",
                        "import org.junit.jupiter.api.BeforeEach;",
                        "import org.mockito.MockitoAnnotations;",
                        "import java.util.ArrayList;"
                ),
                List.of(),
                List.of(),
                List.of("""
                        @BeforeEach
                        public void setUp() {
                            MockitoAnnotations.openMocks(this);
                            service = new UserService(repository, new AuditTrailService(), org.mockito.Mockito.mock(NotificationService.class));
                        }
                        """),
                ""
        );

        AtomicReference<String> compileSource = new AtomicReference<>("");
        AtomicReference<String> executedMethodName = new AtomicReference<>("UNSET");
        PreMergeScratchValidator validator = new PreMergeScratchValidator(
                new PipelineLogger(tempDir),
                (projectRoot, scratchPath, methodName) -> {
                    try {
                        compileSource.set(Files.readString(scratchPath));
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                    return new CompileResult(true, List.of(), "", "");
                },
                (projectRoot, scratchPath, methodName) -> {
                    executedMethodName.set(methodName);
                    return new ExecuteResult(false, List.of("com.example.app.service.UserServiceTestPreMergeScratch"), "", "synthetic merged sibling regression");
                },
                new SiblingIsolationPolicy(true, true, true, true, true, true, true, true, "PreMergeScratch")
        );

        PreMergeScratchValidator.ValidationResult result = validator.validate(tempDir, classInfo, snippet, true, true);

        assertFalse(result.success());
        assertEquals("SCRATCH_EXECUTION_FAILED", result.failureReason());
        assertTrue(compileSource.get().contains("createUserShouldSaveUserAndSendNotifications"));
        assertTrue(compileSource.get().contains("testAverageLoginAttempts_NoUsers_ReturnZero"));
        assertTrue(compileSource.get().contains("public void setUp()"));
        assertEquals(null, executedMethodName.get());
    }
}
