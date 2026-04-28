package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestClassWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void applyClassStructureReplacesLifecycleHelperWhenIncomingUsesMoreMocks() {
        TestClassWriter writer = new TestClassWriter(new PipelineLogger(tempDir));
        String source = """
                package com.example.app;

                import org.junit.jupiter.api.BeforeEach;
                import static org.mockito.Mockito.mock;

                public class ApplicationTest {

                    private UserService userService;
                    private AuditTrailService auditTrailService;
                    private LibraryComponent libraryComponent;
                    private FeatureToggleService featureToggleService;
                    private Application application;

                    @BeforeEach
                    void setUp() {
                        userService = mock(UserService.class);
                        auditTrailService = mock(AuditTrailService.class);
                        libraryComponent = mock(LibraryComponent.class);
                        featureToggleService = new FeatureToggleService();
                        application = new Application(userService, auditTrailService, libraryComponent, featureToggleService);
                    }
                }
                """;
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "ApplicationTest",
                "testDeployHiddenFeatureActivateSuccess",
                "@Test void testDeployHiddenFeatureActivateSuccess() {}",
                List.of(),
                List.of(),
                List.of(),
                List.of("""
                        @BeforeEach
                        void setUp() {
                            userService = mock(UserService.class);
                            auditTrailService = mock(AuditTrailService.class);
                            libraryComponent = mock(LibraryComponent.class);
                            featureToggleService = mock(FeatureToggleService.class);
                            application = new Application(userService, auditTrailService, libraryComponent, featureToggleService);
                        }
                        """),
                ""
        );

        String updated = writer.applyClassStructure(source, snippet);

        assertTrue(updated.contains("featureToggleService = mock(FeatureToggleService.class);"));
        assertFalse(updated.contains("featureToggleService = new FeatureToggleService();"));
    }

    @Test
    void applyClassStructureKeepsExistingLifecycleHelperWhenIncomingUsesFewerMocks() {
        TestClassWriter writer = new TestClassWriter(new PipelineLogger(tempDir));
        String source = """
                package com.example.app;

                import org.junit.jupiter.api.BeforeEach;
                import static org.mockito.Mockito.mock;

                public class ApplicationTest {

                    private UserService userService;
                    private AuditTrailService auditTrailService;
                    private LibraryComponent libraryComponent;
                    private FeatureToggleService featureToggleService;
                    private Application application;

                    @BeforeEach
                    void setUp() {
                        userService = mock(UserService.class);
                        auditTrailService = mock(AuditTrailService.class);
                        libraryComponent = mock(LibraryComponent.class);
                        featureToggleService = mock(FeatureToggleService.class);
                        application = new Application(userService, auditTrailService, libraryComponent, featureToggleService);
                    }
                }
                """;
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "ApplicationTest",
                "testStart",
                "@Test void testStart() {}",
                List.of(),
                List.of(),
                List.of(),
                List.of("""
                        @BeforeEach
                        void setUp() {
                            userService = mock(UserService.class);
                            auditTrailService = mock(AuditTrailService.class);
                            libraryComponent = mock(LibraryComponent.class);
                            featureToggleService = new FeatureToggleService();
                            application = new Application(userService, auditTrailService, libraryComponent, featureToggleService);
                        }
                        """),
                ""
        );

        String updated = writer.applyClassStructure(source, snippet);

        assertTrue(updated.contains("featureToggleService = mock(FeatureToggleService.class);"));
        assertFalse(updated.contains("featureToggleService = new FeatureToggleService();"));
    }

    @Test
    void appendMethodShouldRenameWhenGeneratedNameAlreadyExists() {
        TestClassWriter writer = new TestClassWriter(new PipelineLogger(tempDir));
        String source = """
                package com.example.app;

                import org.junit.jupiter.api.Test;

                public class ApplicationTest {

                    @Test
                    void shouldInvokeStart() {
                    }
                }
                """;
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "ApplicationTest",
                "shouldInvokeStart",
                """
                        @Test
                        void shouldInvokeStart() {
                            application.start();
                        }
                        """,
                List.of()
        );

        TestClassWriter.AppendResult result = writer.appendMethod(source, snippet);

        assertTrue(result.changed());
        assertNotEquals("shouldInvokeStart", result.mergedSnippet().methodName());
        assertTrue(result.mergedSnippet().methodName().startsWith("shouldInvokeStartVariant"));
        assertTrue(result.source().contains("void shouldInvokeStart()"));
        assertTrue(result.source().contains("void " + result.mergedSnippet().methodName() + "()"));
    }

    @Test
    void appendMethodShouldCollapseDuplicateTestAnnotations() {
        TestClassWriter writer = new TestClassWriter(new PipelineLogger(tempDir));
        String source = """
                package com.example.app;

                import org.junit.jupiter.api.Test;

                public class ApplicationTest {
                }
                """;
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "ApplicationTest",
                "shouldStart",
                """
                        @Test
                        @Test
                        void shouldStart() {
                            application.start();
                        }
                        """,
                List.of()
        );

        TestClassWriter.AppendResult result = writer.appendMethod(source, snippet);

        assertTrue(result.changed());
        assertEquals(1, countOccurrences(result.source(), "@Test"));
    }

    @Test
    void ensureImportsShouldNormalizeDuplicateImports() {
        TestClassWriter writer = new TestClassWriter(new PipelineLogger(tempDir));
        String source = """
                package com.example.app;

                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.Test;
                import java.util.List;

                public class ApplicationTest {
                }
                """;

        String updated = writer.ensureImports(source, List.of("java.util.List"));

        assertEquals(1, countOccurrences(updated, "import org.junit.jupiter.api.Test;"));
        assertEquals(1, countOccurrences(updated, "import java.util.List;"));
    }

    private int countOccurrences(String source, String token) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
