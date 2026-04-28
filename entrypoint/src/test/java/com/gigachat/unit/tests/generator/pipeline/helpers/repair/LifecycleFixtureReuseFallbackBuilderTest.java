package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleFixtureReuseFallbackBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPreserveNonLifecycleSiblingTestsWhenDroppingDuplicateSetup() throws Exception {
        Path targetPath = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(targetPath.getParent());
        Files.writeString(targetPath, """
                package com.example.app.service;

                import org.junit.jupiter.api.BeforeEach;
                import org.mockito.Mock;
                import org.mockito.MockitoAnnotations;
                import com.example.app.repository.UserRepository;

                public class UserServiceTest {

                    @Mock
                    private UserRepository repository;

                    private UserService service;

                    @BeforeEach
                    void setUp() {
                        MockitoAnnotations.openMocks(this);
                        service = new UserService(repository, new AuditTrailService(), org.mockito.Mockito.mock(NotificationService.class));
                    }
                }
                """);

        TestClassInfo classInfo = new TestClassInfo(
                "UserService",
                "UserServiceTest",
                targetPath,
                List.of(),
                List.of()
        );

        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserServiceTest",
                "testFindUserValidIndex",
                """
                        @Test
                        void testFindUserValidIndex() {
                            when(repository.findAll()).thenReturn(new java.util.ArrayList<>());
                            service.findUser(0);
                        }
                        """,
                List.of("import org.junit.jupiter.api.Test;"),
                List.of(),
                List.of(
                        "@Mock\nprivate UserRepository repository;",
                        "private UserService service;"
                ),
                List.of(
                        """
                        @BeforeEach
                        void setUp() {
                            MockitoAnnotations.openMocks(this);
                            service = new UserService(repository, new AuditTrailService(), org.mockito.Mockito.mock(NotificationService.class));
                        }
                        """,
                        """
                        @Test
                        void testFindUserNegativeIndex() {
                            when(repository.findAll()).thenReturn(new java.util.ArrayList<>());
                            service.findUser(-1);
                        }
                        """
                ),
                ""
        );

        LifecycleFixtureReuseFallbackBuilder builder =
                new LifecycleFixtureReuseFallbackBuilder(new PipelineLogger(tempDir));
        GeneratedTestSnippet fallback = builder.build(
                classInfo,
                snippet,
                "E112: existing generated test class already defines lifecycle setup for \"service\"; "
                        + "do not add another lifecycle helper that reinitializes the class under test.");

        assertNotNull(fallback);
        assertEquals(1, fallback.helperMethods().size());
        assertTrue(fallback.helperMethods().get(0).contains("testFindUserNegativeIndex"));
        assertTrue(fallback.helperMethods().stream().noneMatch(helper -> helper.contains("@BeforeEach")));
    }
}
