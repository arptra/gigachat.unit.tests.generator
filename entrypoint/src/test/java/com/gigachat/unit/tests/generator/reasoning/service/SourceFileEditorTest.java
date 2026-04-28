package com.gigachat.unit.tests.generator.reasoning.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceFileEditorTest {

    @TempDir
    Path tempDir;

    @Test
    void applyPatchShouldFallbackToContextWhenHunkLineNumbersAreOff() throws IOException {
        Path file = tempDir.resolve("ApplicationTest.java");
        Files.writeString(file, """
                class ApplicationTest {
                    void test() {
                        verify(auditTrailService).recordEvent("HiddenFeatureActivated");
                        verify(auditTrailService).recordEvent("HiddenFeatureRecalibrated");
                    }
                }
                """);

        String patch = """
                @@ -40,2 +40,2 @@
                -        verify(auditTrailService).recordEvent("HiddenFeatureActivated");
                -        verify(auditTrailService).recordEvent("HiddenFeatureRecalibrated");
                +        verify(auditTrailService).recordEvent("Activated hidden feature with value 6");
                +        verify(auditTrailService).recordEvent("Recalibrated feature with factor 24");
                """;

        SourceFileEditor editor = new SourceFileEditor();
        String updated = editor.applyPatch(file, patch);

        assertTrue(updated.contains("Activated hidden feature with value 6"));
        assertTrue(updated.contains("Recalibrated feature with factor 24"));
    }

    @Test
    void applyPatchShouldAcceptUnifiedDiffHeadersWithTrailingContext() throws IOException {
        Path file = tempDir.resolve("CoverageGoalWorkflowServiceTest.java");
        Files.writeString(file, """
                class CoverageGoalWorkflowServiceTest {
                    void testPriority() {
                        assertEquals("priority", result);
                        verify(auditTrailService).recordEvent("priority-signal");
                    }
                }
                """);

        String patch = """
                @@ -2,4 +2,16 @@ class CoverageGoalWorkflowServiceTest {
                     void testPriority() {
                         assertEquals("priority", result);
                         verify(auditTrailService).recordEvent("priority-signal");
                     }
                +
                +    void testStandard() {
                +        assertEquals("standard", result);
                +        verify(auditTrailService).recordEvent("standard-signal");
                +    }
                 }
                """;

        SourceFileEditor editor = new SourceFileEditor();
        String updated = editor.applyPatch(file, patch);

        assertTrue(updated.contains("void testStandard()"));
        assertTrue(updated.contains("recordEvent(\"standard-signal\")"));
    }

    @Test
    void applyPatchShouldHandleLiveExecutionRepairHunkForCoverageGoalWorkflowService() throws IOException {
        Path file = tempDir.resolve("CoverageGoalWorkflowServiceTest.java");
        Files.writeString(file, """
                package com.example.app.service;

                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.BeforeEach;
                import static org.mockito.Mockito.*;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                public class CoverageGoalWorkflowServiceTest {

                    private AuditTrailService auditTrailService;

                    private CoverageGoalWorkflowService service;

                    @BeforeEach
                    void setUp() {
                        auditTrailService = mock(AuditTrailService.class);
                        service = new CoverageGoalWorkflowService(auditTrailService);
                    }

                    @Test
                    void testPriorityClassification() {
                    // Arrange
                    int score = 10;
                    boolean priorityAccount = true;
                    // Act
                    String result = service.classifySignal(score, priorityAccount);
                    // Assert
                    assertEquals("priority", result);
                    verify(auditTrailService).recordEvent("priority-signal");
                    }

                    @Test
                    void testPriorityClassificationCoverageVariant2() {
                    // Arrange
                    int score = 10;
                    boolean priorityAccount = false;
                    // Act
                    String result = service.classifySignal(score, priorityAccount);
                    // Assert
                    assertEquals("priority", result);
                    verify(auditTrailService).recordEvent("priority-signal");
                    }
                }
                """);

        String patch = """
                @@ -33,7 +33,7 @@ public class CoverageGoalWorkflowServiceTest {
                         // Arrange
                         int score = 10;
                         boolean priorityAccount = false;
                -        // Act
                +        // Act (score >= 10 but priorityAccount is false, so result should be "standard")
                         String result = service.classifySignal(score, priorityAccount);
                         // Assert
                -        assertEquals("priority", result);
                +        assertEquals("standard", result);
                         verify(auditTrailService).recordEvent("standard-signal");
                     }
                """;

        SourceFileEditor editor = new SourceFileEditor();
        String updated = editor.applyPatch(file, patch);

        assertTrue(updated.contains("assertEquals(\"standard\", result);"));
        assertTrue(updated.contains("recordEvent(\"standard-signal\")"));
        assertTrue(updated.contains("void testPriorityClassificationCoverageVariant2()"));
    }

    @Test
    void addImportShouldRemoveWrongSameSimpleNameImportWhenTargetLivesInSamePackage() throws IOException {
        Path file = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package com.example.app.service;

                import com.example.app.util.NotificationService;
                import org.junit.jupiter.api.Test;

                class UserServiceTest {
                    private NotificationService notificationService;
                }
                """);

        SourceFileEditor editor = new SourceFileEditor();
        String updated = editor.addImport(file, "com.example.app.service.NotificationService");

        assertFalse(updated.contains("import com.example.app.util.NotificationService;"));
        assertFalse(updated.contains("import com.example.app.service.NotificationService;"));
        assertTrue(updated.contains("private NotificationService notificationService;"));
    }

    @Test
    void addImportShouldSupportStaticImports() throws IOException {
        Path file = tempDir.resolve("src/test/java/com/example/app/service/UserServiceTest.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package com.example.app.service;

                import org.junit.jupiter.api.Test;

                class UserServiceTest {
                    @Test
                    void testCreateUser() {
                        assertEquals("ok", "ok");
                    }
                }
                """);

        SourceFileEditor editor = new SourceFileEditor();
        String updated = editor.addImport(file, "static org.junit.jupiter.api.Assertions.assertEquals");

        assertTrue(updated.contains("import static org.junit.jupiter.api.Assertions.assertEquals;"));
    }

}
