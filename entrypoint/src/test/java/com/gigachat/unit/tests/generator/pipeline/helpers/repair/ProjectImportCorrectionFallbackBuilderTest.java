package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectImportCorrectionFallbackBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRewriteWrongProjectImportToAuthoritativeType() {
        ProjectImportCorrectionFallbackBuilder builder =
                new ProjectImportCorrectionFallbackBuilder(new PipelineLogger(tempDir));
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserServiceTest",
                "disableUser_WhenUserExists_ShouldDisableAndNotify",
                """
                        @Test
                        void disableUser_WhenUserExists_ShouldDisableAndNotify() {
                            notificationService.sendDeactivationNotice(user);
                        }
                        """,
                List.of(
                        "import com.example.app.model.User;",
                        "import com.example.app.util.NotificationService;"
                ),
                List.of(),
                List.of("private com.example.app.util.NotificationService notificationService;"),
                List.of(),
                """
                        package com.example.app.service;

                        import com.example.app.util.NotificationService;

                        class UserServiceTest {
                            private com.example.app.util.NotificationService notificationService;
                        }
                        """
        );

        GeneratedTestSnippet fallback = builder.build(
                snippet,
                "E111: project import does not resolve and should use the authoritative in-project type "
                        + "[com.example.app.util.NotificationService -> com.example.app.service.NotificationService]");

        assertNotNull(fallback);
        assertTrue(fallback.imports().contains("import com.example.app.service.NotificationService;"));
        assertTrue(fallback.fieldDeclarations().get(0).contains("com.example.app.service.NotificationService"));
        assertTrue(fallback.fullClassSource().contains("import com.example.app.service.NotificationService;"));
        assertTrue(fallback.fullClassSource().contains("private com.example.app.service.NotificationService notificationService;"));
    }

    @Test
    void shouldReturnNullWhenSnippetAlreadyMatchesValidatedImport() {
        ProjectImportCorrectionFallbackBuilder builder =
                new ProjectImportCorrectionFallbackBuilder(new PipelineLogger(tempDir));
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "UserServiceTest",
                "disableUser_WhenUserExists_ShouldDisableAndNotify",
                "@Test void disableUser_WhenUserExists_ShouldDisableAndNotify() {}",
                List.of("import com.example.app.service.NotificationService;")
        );

        GeneratedTestSnippet fallback = builder.build(
                snippet,
                "E111: project import does not resolve and should use the authoritative in-project type "
                        + "[com.example.app.util.NotificationService -> com.example.app.service.NotificationService]");

        assertNull(fallback);
    }
}
