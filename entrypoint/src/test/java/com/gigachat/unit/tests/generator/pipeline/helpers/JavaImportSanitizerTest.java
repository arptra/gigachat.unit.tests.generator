package com.gigachat.unit.tests.generator.pipeline.helpers;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaImportSanitizerTest {

    @Test
    void shouldKeepValidImportsAndDropCommentOnlyImports() {
        List<String> imports = JavaImportSanitizer.sanitizeImports(List.of(
                "import //Corrected import",
                "java.util.List // Corrected import",
                "import static org.mockito.Mockito.*; // ok",
                "import com.example.User;"
        ));

        assertEquals(List.of(
                "import java.util.List;",
                "import static org.mockito.Mockito.*;",
                "import com.example.User;"
        ), imports);
    }

    @Test
    void shouldSanitizeImportLinesInsideFullSource() {
        String source = """
                package com.example;

                import //Corrected import
                import java.util.List; // ok

                class SampleTest {
                }
                """;

        String sanitized = JavaImportSanitizer.sanitizeSourceImports(source);

        assertFalse(sanitized.contains("import //Corrected import"));
        assertTrue(sanitized.contains("import java.util.List;"));
        assertTrue(sanitized.contains("class SampleTest"));
    }
}
